package top.mcocet.running.world;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.math.AABB;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.List;

/**
 * 逐状态权威碰撞盒表（{@code collision_shapes.bin}）。
 *
 * <p>数据由原版 {@code BlockBehaviour.BlockStateBase.getCollisionShape()} 在
 * {@code CollisionContext.empty()} 下逐状态导出，按协议状态 ID 直接索引，
 * 因此不需要按方块名猜测形状、也不需要解码 blocks.json 的状态位序 ——
 * 这两个环节正是历史上"部分方块减速 / 被跳过 / 卡住"的错误来源。</p>
 *
 * <p>二进制格式（大端）：</p>
 * <pre>
 *   4s   magic  "MSCS"
 *   I    format version（当前 1）
 *   I    state count
 *   重复 state count 次：
 *     B    该状态的碰撞盒数量（0 = 无碰撞形状）
 *     N 次 × 6f   minX, minY, minZ, maxX, maxY, maxZ（方块局部坐标，0..1，
 *                 栅栏/墙等可越出 1.0）
 * </pre>
 *
 * <p>加载策略与 {@link BlockNameTable} 一致：优先工作目录下的外部文件（便于按
 * 服务器版本替换），缺失时回退 jar 内置资源。状态数与 blocks.json 不一致时
 * <b>整表作废</b>并记录告警 —— 静默错位比没有数据危险得多。</p>
 *
 * <p>本文件与 {@code blocks.json} 是<b>同版本的一对</b>：两者都按协议状态 ID
 * 索引，换版本必须同时换（两套可用数据存于工程
 * {@code tools/datasets/<版本>/}，只替换 jar 内 resources 或工作目录中的两个文件即可）。
 * 只换其一则状态数不同 → 整表作废；两者同换成其它版本的服务端则不会报错，
 * 因此请核对启动日志中打印的版本名与服务端是否一致。</p>
 */
public final class CollisionShapes {
    private static final Logger log = LoggerFactory.getLogger(CollisionShapes.class.getSimpleName());

    private static final int MAGIC = 0x4D534353; // "MSCS"
    private static final int FORMAT_VERSION = 1;
    private static final String FILE_NAME = "collision_shapes.bin";
    /** 内置资源候选路径（优先命名空间路径，兼容类根目录） */
    private static final String[] EMBEDDED_RESOURCES = {
            "/top/mcocet/running/" + FILE_NAME, "/" + FILE_NAME};

    /** 浮点比较容差（数据本身是 float，1e-6 足以区分 1/16 网格） */
    private static final double EPS = 1.0E-6;

    /** 全局单例；null 表示数据不可用（调用方退化为名称启发式） */
    private static volatile CollisionShapes instance;

    private final int stateCount;
    /** 各状态盒数据在 {@link #coords} 中的起始下标（长度 stateCount + 1） */
    private final int[] starts;
    /** 所有盒坐标展平存放：每盒 6 个 float（minX,minY,minZ,maxX,maxY,maxZ） */
    private final float[] coords;
    /** 各状态局部坐标最大 Y（无盒为 0） */
    private final float[] maxHeights;
    /** 各状态标志位：bit0 = 形状恰为完整立方体（用于窒息判定） */
    private final byte[] flags;

    private CollisionShapes(int stateCount, int[] starts, float[] coords,
                            float[] maxHeights, byte[] flags) {
        this.stateCount = stateCount;
        this.starts = starts;
        this.coords = coords;
        this.maxHeights = maxHeights;
        this.flags = flags;
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    /**
     * 默认加载：优先工作目录外部文件，缺失时回退内置资源。
     * 任何失败都只返回 null（不抛异常），由调用方退化处理。
     *
     * @param expectedStateCount blocks.json 推出的协议状态总数；0 表示不做校验
     */
    public static synchronized CollisionShapes loadDefault(int expectedStateCount) {
        if (instance != null) {
            return matches(instance, expectedStateCount) ? instance : null;
        }
        byte[] external = readExternal();
        if (external != null) {
            CollisionShapes loaded = tryDecode(external, FILE_NAME + "（工作目录）", expectedStateCount);
            if (loaded != null) {
                instance = loaded;
                return loaded;
            }
        }
        byte[] embedded = readEmbedded();
        if (embedded != null) {
            CollisionShapes loaded = tryDecode(embedded, "内置资源", expectedStateCount);
            if (loaded != null) {
                instance = loaded;
                return loaded;
            }
        }
        if (external == null && embedded == null) {
            log.warn("[Running] 未找到碰撞形状表 {}，方块碰撞将退化为名称启发式近似。", FILE_NAME);
        }
        return null;
    }

    /** 解析并校验单个数据源；状态数不符或解析失败时告警并返回 null（由调用方继续尝试下一个源） */
    private static CollisionShapes tryDecode(byte[] data, String source, int expectedStateCount) {
        try {
            CollisionShapes loaded = decode(data);
            if (!matches(loaded, expectedStateCount)) {
                log.warn("[Running] {} 的碰撞形状表状态数 {} 与 blocks.json 的 {} 不一致（协议版本不匹配），已弃用该源。",
                        source, loaded.stateCount, expectedStateCount);
                return null;
            }
            log.info("[Running] 已加载碰撞形状表：{} 个状态 / {} 个碰撞盒（来源 {}）。",
                    loaded.stateCount, loaded.boxCountTotal(), source);
            return loaded;
        } catch (IOException e) {
            log.warn("[Running] 解析{}的碰撞形状表失败: {}", source, e.toString());
            return null;
        }
    }

    /** 已加载的实例（可能为 null） */
    public static CollisionShapes get() {
        return instance;
    }

    private static boolean matches(CollisionShapes shapes, int expectedStateCount) {
        return expectedStateCount <= 0 || shapes.stateCount == expectedStateCount;
    }

    private static byte[] readExternal() {
        File file = new File(FILE_NAME);
        if (!file.isFile()) {
            return null;
        }
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            log.warn("[Running] 读取外部碰撞形状表失败: {}", e.toString());
            return null;
        }
    }

    private static byte[] readEmbedded() {
        for (String path : EMBEDDED_RESOURCES) {
            try (InputStream in = CollisionShapes.class.getResourceAsStream(path)) {
                if (in == null) {
                    continue;
                }
                ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 20);
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, read);
                }
                return buf.toByteArray();
            } catch (IOException e) {
                log.warn("[Running] 读取内置碰撞形状表 {} 失败: {}", path, e.toString());
                return null;
            }
        }
        return null;
    }

    /** 解析二进制；格式或长度不符抛 IOException */
    static CollisionShapes decode(byte[] data) throws IOException {
        if (data.length < 12) {
            throw new IOException("碰撞形状表长度不足（" + data.length + " 字节）");
        }
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        if (bb.getInt() != MAGIC) {
            throw new IOException("碰撞形状表魔数不符");
        }
        int version = bb.getInt();
        if (version != FORMAT_VERSION) {
            throw new IOException("不支持的碰撞形状表格式版本 " + version);
        }
        int stateCount = bb.getInt();
        if (stateCount <= 0 || stateCount > (1 << 24)) {
            throw new IOException("非法的状态数 " + stateCount);
        }

        int[] starts = new int[stateCount + 1];
        float[] maxHeights = new float[stateCount];
        byte[] flags = new byte[stateCount];
        // 先扫描一遍求出总盒数，再一次性分配（避免中途扩容）
        int probe = 12;
        int totalBoxes = 0;
        for (int s = 0; s < stateCount; s++) {
            if (probe >= data.length) {
                throw new IOException("碰撞形状表在状态 " + s + " 处被截断");
            }
            int count = data[probe] & 0xFF;
            probe += 1 + count * 24;
            if (probe > data.length) {
                throw new IOException("碰撞形状表在状态 " + s + " 的盒数据处越界");
            }
            totalBoxes += count;
        }
        if (probe != data.length) {
            throw new IOException("碰撞形状表尾部有 " + (data.length - probe) + " 个多余字节");
        }

        float[] coords = new float[totalBoxes * 6];
        int cursor = 12;
        int coordAt = 0;
        int skipped = 0;
        for (int s = 0; s < stateCount; s++) {
            starts[s] = coordAt;
            int count = data[cursor] & 0xFF;
            cursor++;
            float maxY = 0.0F;
            int kept = 0;
            for (int i = 0; i < count; i++) {
                float[] box = new float[6];
                for (int f = 0; f < 6; f++) {
                    float v = bb.getFloat(cursor + f * 4);
                    if (!Float.isFinite(v)) {
                        throw new IOException("状态 " + s + " 的盒坐标非有限值");
                    }
                    box[f] = v;
                }
                cursor += 24;
                if (box[0] >= box[3] || box[1] >= box[4] || box[2] >= box[5]) {
                    // 零体积盒对碰撞无意义（也永远不可能构成完整立方体），丢弃而不是让整表作废
                    skipped++;
                    continue;
                }
                System.arraycopy(box, 0, coords, coordAt, 6);
                coordAt += 6;
                maxY = Math.max(maxY, box[4]);
                kept++;
            }
            maxHeights[s] = maxY;
            flags[s] = (byte) (isFullCube(coords, starts[s], kept) ? 1 : 0);
        }
        starts[stateCount] = coordAt;
        if (skipped > 0) {
            log.warn("[Running] 碰撞形状表中丢弃了 {} 个零体积盒。", skipped);
        }
        return new CollisionShapes(stateCount, starts, coords, maxHeights, flags);
    }

    /** 该状态的盒并集是否恰好铺满 [0,1]³（对齐原版 isCollisionShapeFullBlock） */
    private static boolean isFullCube(float[] coords, int from, int count) {
        if (count == 0) {
            return false;
        }
        if (count == 1) {
            int b = from;
            return near(coords[b], 0.0F) && near(coords[b + 1], 0.0F) && near(coords[b + 2], 0.0F)
                    && near(coords[b + 3], 1.0F) && near(coords[b + 4], 1.0F) && near(coords[b + 5], 1.0F);
        }
        // 多盒：按 1/16 网格逐体素覆盖检查（原版形状均落在该网格上）
        boolean[] filled = new boolean[16 * 16 * 16];
        int coveredCells = 0;
        for (int i = 0; i < count; i++) {
            int b = from + i * 6;
            int x0 = cell(coords[b]), x1 = cell(coords[b + 3]);
            int y0 = cell(coords[b + 1]), y1 = cell(coords[b + 4]);
            int z0 = cell(coords[b + 2]), z1 = cell(coords[b + 5]);
            if (x0 < 0 || y0 < 0 || z0 < 0 || x1 > 16 || y1 > 16 || z1 > 16) {
                return false; // 越出本格的形状（栅栏/墙等）不可能是完整立方体
            }
            for (int x = x0; x < x1; x++) {
                for (int y = y0; y < y1; y++) {
                    for (int z = z0; z < z1; z++) {
                        int idx = (x * 16 + y) * 16 + z;
                        if (!filled[idx]) {
                            filled[idx] = true;
                            coveredCells++;
                        }
                    }
                }
            }
        }
        return coveredCells == 16 * 16 * 16;
    }

    private static int cell(float v) {
        return (int) Math.floor(v * 16.0F + 1.0E-4F);
    }

    private static boolean near(float a, double b) {
        return Math.abs(a - b) < EPS;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public int stateCount() {
        return stateCount;
    }

    /** 覆盖范围内的最大状态 ID（不含）；等价 stateCount */
    public boolean covers(int stateId) {
        return stateId >= 0 && stateId < stateCount;
    }

    /** 该状态的碰撞盒数量（0 = 无碰撞形状，可穿过） */
    public int boxCount(int stateId) {
        if (!covers(stateId)) {
            return 0;
        }
        return starts[stateId + 1] - starts[stateId];
    }

    /** 该状态是否完全无碰撞形状 */
    public boolean isEmpty(int stateId) {
        return boxCount(stateId) == 0;
    }

    /** 形状是否恰为完整立方体（窒息判定用） */
    public boolean isFullCube(int stateId) {
        if (!covers(stateId)) {
            return false;
        }
        return flags[stateId] != 0;
    }

    /** 碰撞形状相对方块底部的最大顶面高度（局部坐标，可 &gt; 1.0）；无碰撞为 0 */
    public double maxHeight(int stateId) {
        if (!covers(stateId)) {
            return 0.0;
        }
        return maxHeights[stateId];
    }

    /** 把该状态的碰撞盒平移到世界方块坐标后追加到列表 */
    public void addBoxes(int stateId, int bx, int by, int bz, List<AABB> out) {
        if (!covers(stateId)) {
            return;
        }
        int end = starts[stateId + 1];
        for (int i = starts[stateId]; i < end; i += 6) {
            out.add(new AABB(
                    bx + coords[i], by + coords[i + 1], bz + coords[i + 2],
                    bx + coords[i + 3], by + coords[i + 4], bz + coords[i + 5]));
        }
    }

    /** 总盒数（仅用于启动日志） */
    public int boxCountTotal() {
        return starts[stateCount] / 6;
    }
}
