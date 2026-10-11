package top.mcocet.running.listeners;

import net.kyori.adventure.key.Key;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.bot.BotState;
import top.mcocet.running.world.RunningWorld;

import java.util.List;

/**
 * 连接层面的必要应答与登录/重生信息处理。
 *
 * <p>职责：
 * <ul>
 *   <li>应答服务器 Ping（confirmation）包——收到即回 Pong，对齐官方
 *       ClientCommonPacketListenerImpl.handlePing；反作弊用它检测客户端
 *       响应性，不回会被踢出；</li>
 *   <li>缓存 dimension_type 注册表（取世界 min_y / height），
 *       并根据生物群系注册表大小推算调色板位宽；</li>
 *   <li>登录包中记录实体 ID，并按当前维度 id 应用世界尺寸；</li>
 *   <li>重生包（含维度切换）按新维度重新应用世界尺寸
 *       （对齐官方客户端重生后重建关卡的处理）。</li>
 * </ul></p>
 *
 * <p>注意：KeepAlive 应答由 MCProtocolLib 内置的 ClientListener 自动完成
 * （AUTOMATIC_KEEP_ALIVE_MANAGEMENT 标志默认为 true），本监听器不得重复回传：
 * 同一挑战被应答两次时，服务器会把第二个应答视为无匹配 challenge 并以
 * "Timed out" 断开连接。运行日志中典型的服务器侧提示：
 * "sending keepalive response (...) without matching challenge"。
 * 与此相对，Ping 包（common 协议层，反作弊的 confirmation 包）不会被自动
 * 应答，必须由本监听器手动回 Pong。</p>
 */
public final class ConnectionListener extends SessionAdapter {
    private static final Logger log = LoggerFactory.getLogger(ConnectionListener.class.getSimpleName());

    private static final Key DIMENSION_TYPE = Key.key("minecraft", "dimension_type");
    private static final Key BIOME_REGISTRY = Key.key("minecraft", "worldgen/biome");

    private static final int DEFAULT_MIN_Y = -64;
    private static final int DEFAULT_HEIGHT = 384;

    private final BotState state;
    private final RunningWorld world;

    /** dimension_type 注册表条目（维度 id 即列表下标） */
    private volatile List<RegistryEntry> dimensionTypes;

    public ConnectionListener(BotState state, RunningWorld world) {
        this.state = state;
        this.world = world;
    }

    @Override
    public void packetReceived(Session session, Packet packet) {
        if (packet instanceof ClientboundPingPacket ping) {
            handlePing(session, ping);
        } else if (packet instanceof ClientboundRegistryDataPacket registryData) {
            handleRegistryData(registryData);
        } else if (packet instanceof ClientboundLoginPacket login) {
            handleLogin(login);
        } else if (packet instanceof ClientboundRespawnPacket respawn) {
            handleRespawn(respawn);
        }
    }

    /**
     * 应答服务器 Ping（confirmation）包：对齐官方客户端 handlePing 的无条件立即回传。
     *
     * <p>反作弊（如 Vulcan 的 confirmation 检查）会周期性发送 Ping 并等待 Pong，
     * 超时未收到（默认 30 秒）会以伪装的 ReadTimeoutException 踢出玩家；
     * 回传的 id 必须与收到的一致（Vulcan 还会校验到达顺序）。</p>
     */
    private void handlePing(Session session, ClientboundPingPacket ping) {
        session.send(new ServerboundPongPacket(ping.getId()));
    }

    private void handleRegistryData(ClientboundRegistryDataPacket registryData) {
        if (DIMENSION_TYPE.equals(registryData.getRegistry())) {
            dimensionTypes = registryData.getEntries();
        } else if (BIOME_REGISTRY.equals(registryData.getRegistry())) {
            world.setBiomeRegistrySize(registryData.getEntries().size());
        }
    }

    private void handleLogin(ClientboundLoginPacket login) {
        state.setEntityId(login.getEntityId());
        int dimensionId = applyDimension(login.getCommonPlayerSpawnInfo().getDimension());
        log.info("[Running] 已登录：实体 ID={}，维度 ID={}，世界高度范围=[{}, {})。",
                login.getEntityId(), dimensionId, world.getMinY(), world.getMinY() + world.getHeight());
    }

    /**
     * 重生包：维度可能变化（传送门/末地/死亡重生），按新生效的维度
     * 重新应用世界尺寸（RunningWorld 在维度变化时清空区块缓存，
     * 由随后的区块包重建）。
     */
    private void handleRespawn(ClientboundRespawnPacket respawn) {
        int dimensionId = applyDimension(respawn.getCommonPlayerSpawnInfo().getDimension());
        log.info("[Running] 已重生：维度 ID={}，世界高度范围=[{}, {})。",
                dimensionId, world.getMinY(), world.getMinY() + world.getHeight());
    }

    /** 按维度 id 应用世界尺寸（注册表可用时取 min_y/height，否则退回默认值） */
    private int applyDimension(int dimensionId) {
        List<RegistryEntry> types = this.dimensionTypes;
        if (types != null && dimensionId >= 0 && dimensionId < types.size()) {
            NbtMap data = types.get(dimensionId).getData();
            int minY = data.getInt("min_y", DEFAULT_MIN_Y);
            int height = data.getInt("height", DEFAULT_HEIGHT);
            world.setDimension(dimensionId, minY, height);
        } else {
            world.setDimension(dimensionId, DEFAULT_MIN_Y, DEFAULT_HEIGHT);
        }
        return dimensionId;
    }
}
