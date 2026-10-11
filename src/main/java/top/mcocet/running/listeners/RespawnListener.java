package top.mcocet.running.listeners;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerCombatKillPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.bot.BotState;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自动重生：检测到本地玩家死亡后立即发送重生请求。
 *
 * <p>死亡信号为 {@link ClientboundPlayerCombatKillPacket}（对齐官方客户端：
 * 仅当包内玩家实体 ID 与本地玩家一致时进入死亡处理），处理方式等价于
 * 官方死亡屏点击"重生"按钮——发送
 * {@link ServerboundClientCommandPacket}（{@link ClientCommand#RESPAWN}）。
 * 服务器完成重生后下发 {@link ClientboundRespawnPacket}（其维度应用由
 * {@code ConnectionListener} 处理），本监听器借此复位请求标志。</p>
 *
 * <p>防重复：同一死亡周期只请求一次；重生确认（Respawn 包）或重新登录
 * （Login 包）后复位标志。重生引起的位置校正（服务器下发玩家位置包）
 * 由 {@code PositionListener} → 移动引擎物理重置链路自动完成。</p>
 */
public final class RespawnListener extends SessionAdapter {
    private static final Logger log = LoggerFactory.getLogger(RespawnListener.class.getSimpleName());

    private final BotState state;

    /** 本次死亡是否已发送重生请求（防止同一死亡周期重复请求） */
    private final AtomicBoolean respawnRequested = new AtomicBoolean();

    public RespawnListener(BotState state) {
        this.state = state;
    }

    @Override
    public void packetReceived(Session session, Packet packet) {
        if (packet instanceof ClientboundPlayerCombatKillPacket combatKill) {
            handleCombatKill(session, combatKill);
        } else if (packet instanceof ClientboundRespawnPacket) {
            // 服务器已确认重生，允许处理下一次死亡
            if (respawnRequested.compareAndSet(true, false)) {
                log.info("[Running] 已重生。");
            }
        } else if (packet instanceof ClientboundLoginPacket) {
            // 重新登录：复位状态（防御上一会话遗留的未确认请求）
            respawnRequested.set(false);
        }
    }

    private void handleCombatKill(Session session, ClientboundPlayerCombatKillPacket packet) {
        int entityId = state.getEntityId();
        if (entityId < 0 || packet.getPlayerId() != entityId) return;

        if (!respawnRequested.compareAndSet(false, true)) return;
        session.send(new ServerboundClientCommandPacket(ClientCommand.RESPAWN));
        log.info("[Running] 检测到死亡，已自动发送重生请求。");
    }
}
