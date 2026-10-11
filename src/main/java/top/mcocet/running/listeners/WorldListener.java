package top.mcocet.running.listeners;

import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSectionBlocksUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket;
import top.mcocet.running.world.RunningWorld;

/**
 * 把服务器下发的世界数据同步到 {@link RunningWorld}。
 *
 * <p>处理内容：
 * <ul>
 *   <li>区块加载（LevelChunkWithLight）与卸载（ForgetLevelChunk）；</li>
 *   <li>单方块更新（BlockUpdate）与区域内方块批量更新（SectionBlocksUpdate）；</li>
 *   <li>登录时清空旧世界缓存；</li>
 *   <li>区块批量发送完毕（ChunkBatchFinished）后回执 ChunkBatchReceived，
 *       否则服务器将停止继续下发区块。</li>
 * </ul></p>
 */
public final class WorldListener extends SessionAdapter {
    /** 每 tick 期望接收的区块数（回执用；参考原版客户端的典型取值） */
    private static final float DESIRED_CHUNKS_PER_TICK = 7.0F;

    private final RunningWorld world;

    public WorldListener(RunningWorld world) {
        this.world = world;
    }

    @Override
    public void packetReceived(Session session, Packet packet) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
            world.loadChunk(chunkPacket);
        } else if (packet instanceof ClientboundBlockUpdatePacket blockUpdate) {
            applyEntry(world, blockUpdate.getEntry());
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket sectionUpdate) {
            // 该包中的条目坐标已经是绝对世界坐标
            for (BlockChangeEntry entry : sectionUpdate.getEntries()) {
                applyEntry(world, entry);
            }
        } else if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
            world.unloadChunk(forget.getX(), forget.getZ());
        } else if (packet instanceof ClientboundLoginPacket) {
            world.clear();
        } else if (packet instanceof ClientboundChunkBatchFinishedPacket) {
            session.send(new ServerboundChunkBatchReceivedPacket(DESIRED_CHUNKS_PER_TICK));
        }
    }

    private static void applyEntry(RunningWorld world, BlockChangeEntry entry) {
        Vector3i position = entry.getPosition();
        world.setBlockStateId(position.getX(), position.getY(), position.getZ(), entry.getBlock());
    }
}
