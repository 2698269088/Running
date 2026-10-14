package top.mcocet.running.listeners;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import top.mcocet.running.bot.BotState;
import top.mcocet.running.world.EntityTracker;

import java.util.List;
import java.util.Locale;

/**
 * 实体位置包监听器：把实体增删改位置类包翻译为 {@link EntityTracker} 更新，
 * 供玩家物理还原"被附近实体推动"的移动效果（对齐官方客户端
 * ClientPacketListener 的实体包处理链）。
 *
 * <p>逐包对齐关系：
 * <ul>
 *   <li>{@code handleAddEntity} → {@link EntityTracker#addEntity}（跳过本地玩家自身，
 *       对齐客户端 {@code getPushableEntities} 的 player != pusher 自排除；仅会推
 *       本地玩家的类型进入跟踪表）；</li>
 *   <li>{@code handleMoveEntity} 增量位置/朝向包 → {@code moveRelative / rotate}；</li>
 *   <li>{@code handleEntityPositionSync} 绝对位置包 → {@code moveAbsolute}
 *       （更新增量基准）；</li>
 *   <li>{@code handleTeleportEntity} 绝对位置包（relatives 相对字段）→ {@code teleport}
 *       （不更新增量基准）；</li>
 *   <li>{@code handleRemoveEntities} → {@code removeEntities}；</li>
 *   <li>{@code handleLogin} → 无条件清空（重新登录后实体列表整体重建）；</li>
 *   <li>{@code handleRespawn} → 仅维度变化时清空（对齐官方 dimensionChanged 门控：
 *       同维度死亡重生保留全部实体）。</li>
 * </ul></p>
 *
 * <p>已知近似：旁观者玩家的 noPhysics 推挤阻断需元数据判定，未经元数据解析
 * 无法识别，按普通玩家处理；队伍碰撞规则（CollisionRule）无数据，按默认
 * ALWAYS 处理。</p>
 */
public final class EntityListener extends SessionAdapter {

    private final BotState state;
    private final EntityTracker tracker;

    /** 已生效的维度 id（Login/Respawn 更新；Respawn 清空门控用） */
    private int dimensionId = -1;

    public EntityListener(BotState state, EntityTracker tracker) {
        this.state = state;
        this.tracker = tracker;
    }

    @Override
    public void packetReceived(Session session, Packet packet) {
        if (packet instanceof ClientboundAddEntityPacket add) {
            handleAddEntity(add);
        } else if (packet instanceof ClientboundMoveEntityPosRotPacket posRot) {
            tracker.moveRelative(posRot.getEntityId(), posRot.getMoveX(), posRot.getMoveY(), posRot.getMoveZ());
            tracker.rotate(posRot.getEntityId(), posRot.getYaw(), posRot.getPitch());
        } else if (packet instanceof ClientboundMoveEntityPosPacket pos) {
            tracker.moveRelative(pos.getEntityId(), pos.getMoveX(), pos.getMoveY(), pos.getMoveZ());
        } else if (packet instanceof ClientboundMoveEntityRotPacket rot) {
            tracker.rotate(rot.getEntityId(), rot.getYaw(), rot.getPitch());
        } else if (packet instanceof ClientboundEntityPositionSyncPacket sync) {
            tracker.moveAbsolute(sync.getId(),
                    sync.getPosition().getX(), sync.getPosition().getY(), sync.getPosition().getZ(), true);
        } else if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            handleTeleport(teleport);
        } else if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
            tracker.removeEntities(remove.getEntityIds());
        } else if (packet instanceof ClientboundLoginPacket login) {
            tracker.clear();
            dimensionId = login.getCommonPlayerSpawnInfo().getDimension();
        } else if (packet instanceof ClientboundRespawnPacket respawn) {
            handleRespawn(respawn);
        }
    }

    /** 新增实体（对齐 handleAddEntity：按包内位置直接放置；非推挤类型由跟踪表过滤） */
    private void handleAddEntity(ClientboundAddEntityPacket add) {
        if (add.getEntityId() == state.getEntityId()) {
            return;
        }
        EntityType type = add.getType();
        if (type == null) {
            return;
        }
        tracker.addEntity(add.getEntityId(), type.name().toLowerCase(Locale.ROOT),
                add.getX(), add.getY(), add.getZ(), add.getYaw(), add.getPitch());
    }

    /**
     * 传送包：relatives 含 X/Y/Z 的分量为相对当前（插值后）位置的偏移
     * （对齐官方 {@code setValuesFromPositionPacket} 的 relatives 处理）。
     */
    private void handleTeleport(ClientboundTeleportEntityPacket teleport) {
        List<PositionElement> relatives = teleport.getRelatives();
        tracker.teleport(teleport.getId(),
                teleport.getPosition().getX(), teleport.getPosition().getY(), teleport.getPosition().getZ(),
                relatives.contains(PositionElement.X),
                relatives.contains(PositionElement.Y),
                relatives.contains(PositionElement.Z));
    }

    /**
     * 重生包：仅维度变化时清空跟踪表（对齐官方 handleRespawn 的 dimensionChanged
     * 门控——同维度死亡重生不重建世界，实体列表保留）。
     */
    private void handleRespawn(ClientboundRespawnPacket respawn) {
        int newDimension = respawn.getCommonPlayerSpawnInfo().getDimension();
        if (newDimension != dimensionId) {
            tracker.clear();
        }
        dimensionId = newDimension;
    }
}
