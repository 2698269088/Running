package top.mcocet.running.listeners;

import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityMotionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundExplodePacket;
import top.mcocet.running.RunningConfig;
import top.mcocet.running.bot.BotState;
import top.mcocet.running.bot.MovementEngine;

/**
 * 击退与冲量同步：把服务器下发的速度修正写回物理引擎。
 *
 * <p>对齐官方客户端的两条处理链：
 * <ul>
 *   <li>{@link ClientboundSetEntityMotionPacket}（自身实体 ID）：官方
 *       {@code ClientPacketListener.handleSetEntityMotion → entity.lerpMotion}
 *       —— 用包内速度整体替换当前速度。近战/弓箭/钓鱼竿击退、实体推挤
 *       与 mace 冲击均经由该包下发；</li>
 *   <li>{@link ClientboundExplodePacket}（playerKnockback 非空时）：官方
 *       {@code handleExplosion → player.addDeltaMovement}
 *       —— 爆炸冲量叠加在现有速度上。</li>
 * </ul>
 * 速度写入经由 {@link MovementEngine} 下派 {@code PlayerPhysics}（加锁），
 * 与物理 tick 串行执行，等效于官方"包在刻间生效"的时序。受击动画
 * （{@code ClientboundHurtAnimationPacket}）与伤害记录仅影响渲染/统计，
 * 无头机器人不处理。</p>
 */
public final class KnockbackListener extends SessionAdapter {
    private final BotState state;
    private final MovementEngine movementEngine;
    /** 击退/爆炸冲量开关（{@code physics.knockback}，默认开启；关闭时忽略所有速度修正包） */
    private final boolean knockbackEnabled;

    public KnockbackListener(BotState state, MovementEngine movementEngine) {
        this(state, movementEngine, RunningConfig.DEFAULT_KNOCKBACK);
    }

    public KnockbackListener(BotState state, MovementEngine movementEngine, boolean knockbackEnabled) {
        this.state = state;
        this.movementEngine = movementEngine;
        this.knockbackEnabled = knockbackEnabled;
    }

    @Override
    public void packetReceived(Session session, Packet packet) {
        if (!knockbackEnabled) {
            return; // 配置关闭：忽略一切击退/爆炸冲量
        }
        if (packet instanceof ClientboundSetEntityMotionPacket motionPacket) {
            // 仅自身实体：官方 level.getEntity(id) 命中 LocalPlayer 时才应用
            if (motionPacket.getEntityId() == state.getEntityId()) {
                Vector3d movement = motionPacket.getMovement();
                movementEngine.handleSetMotion(movement.getX(), movement.getY(), movement.getZ());
            }
        } else if (packet instanceof ClientboundExplodePacket explodePacket) {
            // 爆炸冲量对本地玩家：官方 playerKnockback().ifPresent(player::addDeltaMovement)
            Vector3d playerKnockback = explodePacket.getPlayerKnockback();
            if (playerKnockback != null) {
                movementEngine.handleExplosionKnockback(
                        playerKnockback.getX(), playerKnockback.getY(), playerKnockback.getZ());
            }
        }
    }
}
