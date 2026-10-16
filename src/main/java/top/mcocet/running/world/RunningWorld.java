package top.mcocet.running.world;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 机器人侧的世界数据缓存。
 *
 * <p>只缓存服务器实际下发的区块（含方块状态数据），用于脚下方块识别、
 * 以及后续移动引擎需要的碰撞查询。区块会在收到 ForgetLevelChunk、
 * 切换维度或重新登录时被清除。</p>
 *
 * <p>区块数据由网络线程写入，由控制台命令线程与移动引擎线程读取：
 * 区块表使用 ConcurrentHashMap，单个区块内部的读写用同步块保护。</p>
 */
public final class RunningWorld {
    private static final Logger log = LoggerFactory.getLogger(RunningWorld.class.getSimpleName());

    /** 查询位置所在的区块尚未加载 */
    public static final int UNLOADED = -1;
    /** 空气方块的默认状态 ID */
    public static final int AIR = 0;

    /**
     * 方块全局调色板的元数据位宽（1.21.x 为 15）。
     * 仅作为 DataPalette 的元数据传入，实际读取位宽由数据流自身携带。
     */
    private static final int BLOCK_GLOBAL_PALETTE_BITS = 15;

    private final Map<Long, ChunkColumn> chunks = new ConcurrentHashMap<>();

    private volatile int dimension = Integer.MIN_VALUE;
    private volatile int minY = -64;
    private volatile int height = 384;
    private volatile int biomePaletteBits = 6;

    // ------------------------------------------------------------------
    // 维度 / 世界尺寸
    // ------------------------------------------------------------------

    /**
     * 设置当前维度及其高度范围（min_y / height 来自 dimension_type 注册表）。
     * 维度变化时清空全部区块缓存。
     */
    public void setDimension(int dimensionId, int worldMinY, int worldHeight) {
        boolean dimensionChanged = this.dimension != dimensionId;
        this.dimension = dimensionId;
        this.minY = worldMinY;
        this.height = worldHeight;
        if (dimensionChanged) {
            chunks.clear();
        }
    }

    public int getDimension() {
        return dimension;
    }

    public int getMinY() {
        return minY;
    }

    public int getHeight() {
        return height;
    }

    /** 依据生物群系注册表大小推算生物群系调色板的全局位宽 */
    public void setBiomeRegistrySize(int size) {
        int bits = size <= 1 ? 1 : 32 - Integer.numberOfLeadingZeros(size - 1);
        this.biomePaletteBits = Math.max(1, bits);
    }

    // ------------------------------------------------------------------
    // 区块加载 / 卸载
    // ------------------------------------------------------------------

    /**
     * 解析并缓存一个区块列。
     *
     * @return 是否解析成功
     */
    public boolean loadChunk(ClientboundLevelChunkWithLightPacket packet) {
        byte[] data = packet.getChunkData();
        int sectionCount = (height + 15) >> 4;
        ChunkSection[] sections = new ChunkSection[sectionCount];

        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            int biomeBits = this.biomePaletteBits;
            for (int i = 0; i < sectionCount; i++) {
                sections[i] = MinecraftTypes.readChunkSection(buf, BLOCK_GLOBAL_PALETTE_BITS, biomeBits);
            }
        } catch (Exception e) {
            log.warn("[Running] 解析区块 ({}, {}) 失败: {}", packet.getX(), packet.getZ(), e.toString());
            return false;
        } finally {
            buf.release();
        }

        chunks.put(chunkKey(packet.getX(), packet.getZ()), new ChunkColumn(minY, sections));
        return true;
    }

    public void unloadChunk(int chunkX, int chunkZ) {
        chunks.remove(chunkKey(chunkX, chunkZ));
    }

    /** 清空全部区块缓存 */
    public void clear() {
        chunks.clear();
    }

    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        return chunks.containsKey(chunkKey(chunkX, chunkZ));
    }

    public int getLoadedChunkCount() {
        return chunks.size();
    }

    // ------------------------------------------------------------------
    // 方块查询 / 更新
    // ------------------------------------------------------------------

    /**
     * 查询指定坐标的方块状态 ID。
     *
     * @return 方块状态 ID；若该位置所属区块未加载则返回 {@link #UNLOADED}；
     *         世界高度范围之外返回 {@link #AIR}
     */
    public int getBlockStateId(int x, int y, int z) {
        int worldMinY = minY;
        if (y < worldMinY || y >= worldMinY + height) return AIR;
        ChunkColumn column = chunks.get(chunkKey(x >> 4, z >> 4));
        if (column == null) return UNLOADED;
        return column.getBlock(x & 15, y, z & 15);
    }

    /** 应用单个方块变化（来自 BlockUpdate / SectionBlocksUpdate） */
    public void setBlockStateId(int x, int y, int z, int stateId) {
        ChunkColumn column = chunks.get(chunkKey(x >> 4, z >> 4));
        if (column == null) return;
        column.setBlock(x & 15, y, z & 15, stateId);
    }

    /**
     * 推断机器人脚底是否踩在实心方块上。
     *
     * <p>仅当 y 位于整数方块高度上时才会着地；脚下方块未知
     * （区块未加载）时返回 fallback。</p>
     */
    public boolean computeOnGround(double x, double y, double z, boolean fallback) {
        double frac = y - Math.floor(y);
        if (frac > 0.001) return false;

        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y) - 1;
        int blockZ = (int) Math.floor(z);
        int state = getBlockStateId(blockX, blockY, blockZ);
        if (state == UNLOADED) return fallback;
        return state != AIR;
    }

    // ------------------------------------------------------------------
    // 内部结构
    // ------------------------------------------------------------------

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** 单个区块列：竖直方向由多个 16³ 的 ChunkSection 组成 */
    private static final class ChunkColumn {
        private final int minY;
        private final ChunkSection[] sections;

        ChunkColumn(int minY, ChunkSection[] sections) {
            this.minY = minY;
            this.sections = sections;
        }

        synchronized int getBlock(int localX, int y, int localZ) {
            int index = (y - minY) >> 4;
            if (index < 0 || index >= sections.length) return AIR;
            ChunkSection section = sections[index];
            if (section == null) return AIR;
            return section.getBlock(localX & 15, y & 15, localZ & 15);
        }

        synchronized void setBlock(int localX, int y, int localZ, int stateId) {
            int index = (y - minY) >> 4;
            if (index < 0 || index >= sections.length) return;
            ChunkSection section = sections[index];
            if (section == null) return;
            section.setBlock(localX & 15, y & 15, localZ & 15, stateId);
        }
    }
}
