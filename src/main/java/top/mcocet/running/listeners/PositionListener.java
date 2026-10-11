package top.mcocet.running.listeners;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import top.mcocet.running.bot.BotState;
import top.mcocet.running.bot.MovementEngine;
import top.mcocet.running.math.Vec3;

import java.util.List;

/**
 * 处理服务器的位置同步（传送）包。
 *
 * <p>服务器通过 ClientboundPlayerPositionPacket 强制校正机器人位置：
 * 必须回执 ServerboundAcceptTeleportationPacket（携带传送 ID），
 * 否则服务器会持续重发传送包直至超时踢出；回执之后再回报一次
 * 位置包（onGround 与水平碰撞均填 false，对齐官方），让服务器
 * 确认机器人已就位（对齐官方 {@code ClientPacketListener.handleMovePlayer}
 * 的处理顺序）。</p>
 *
 * <p>各字段按 relatives 列表决定绝对/相对，对齐官方
 * {@code PositionMoveRotation.calculateAbsolute}：
 * <ul>
 *   <li>位置与朝向：相对字段叠加当前值，否则采用包内绝对值；
 *       pitch 叠加后夹取到 ±90°；</li>
 *   <li>ROTATE_DELTA：当前速度按"旧朝向 - 新朝向"的角度差旋转
 *       （先 xRot 后 yRot，对齐官方 Vec3 旋转顺序）；</li>
 *   <li>DELTA_X/Y/Z：速度增量叠加在（旋转后的）当前速度上，
 *       否则直接采用包内速度。</li>
 * </ul>
 * 官方在传送时只写入位置、朝向与速度（setPos + setDeltaMovement +
 * setYRot/setXRot），不改变冲刺、碰撞与跳跃冷却状态。</p>
 *
 * <p>基数取 {@link BotState#snapshot()} 以保证多字段读取的一致性。</p>
 */
public final class PositionListener extends SessionAdapter {
    private final BotState state;
    private final MovementEngine movementEngine;

    public PositionListener(BotState state, MovementEngine movementEngine) {
        this.state = state;
        this.movementEngine = movementEngine;
    }

    @Override
    public void packetReceived(Session session, Packet packet) {
        if (!(packet instanceof ClientboundPlayerPositionPacket positionPacket)) return;

        List<PositionElement> relatives = positionPacket.getRelatives();
        BotState.Snapshot base = state.snapshot();

        // 1) 位置与朝向：相对字段叠加当前值，否则采用包内绝对值；pitch 夹取 ±90°
        double x = (relatives.contains(PositionElement.X) ? base.x() : 0.0)
                + positionPacket.getPosition().getX();
        double y = (relatives.contains(PositionElement.Y) ? base.y() : 0.0)
                + positionPacket.getPosition().getY();
        double z = (relatives.contains(PositionElement.Z) ? base.z() : 0.0)
                + positionPacket.getPosition().getZ();
        float yaw = (relatives.contains(PositionElement.Y_ROT) ? base.yaw() : 0.0F)
                + positionPacket.getYRot();
        float pitch = Math.max(-90.0F, Math.min(90.0F,
                (relatives.contains(PositionElement.X_ROT) ? base.pitch() : 0.0F)
                        + positionPacket.getXRot()));

        // 2) 速度：ROTATE_DELTA 时先按角度差旋转当前速度，再叠加 DELTA_* 增量
        double currentDeltaX = base.velocityX();
        double currentDeltaY = base.velocityY();
        double currentDeltaZ = base.velocityZ();
        if (relatives.contains(PositionElement.ROTATE_DELTA)) {
            float diffYRot = base.yaw() - yaw;
            float diffXRot = base.pitch() - pitch;
            Vec3 rotated = new Vec3(currentDeltaX, currentDeltaY, currentDeltaZ)
                    .xRot((float) Math.toRadians(diffXRot))
                    .yRot((float) Math.toRadians(diffYRot));
            currentDeltaX = rotated.x;
            currentDeltaY = rotated.y;
            currentDeltaZ = rotated.z;
        }
        double packetDeltaX = positionPacket.getDeltaMovement().getX();
        double packetDeltaY = positionPacket.getDeltaMovement().getY();
        double packetDeltaZ = positionPacket.getDeltaMovement().getZ();
        double deltaX = relatives.contains(PositionElement.DELTA_X)
                ? currentDeltaX + packetDeltaX : packetDeltaX;
        double deltaY = relatives.contains(PositionElement.DELTA_Y)
                ? currentDeltaY + packetDeltaY : packetDeltaY;
        double deltaZ = relatives.contains(PositionElement.DELTA_Z)
                ? currentDeltaZ + packetDeltaZ : packetDeltaZ;

        // 3) 绝对重置物理状态（位置/朝向/速度；不改变冲刺、碰撞与跳跃冷却）
        movementEngine.handleTeleport(x, y, z, yaw, pitch, deltaX, deltaY, deltaZ);

        // 4) 确认收到传送（必须）
        session.send(new ServerboundAcceptTeleportationPacket(positionPacket.getId()));

        // 5) 回报校正后的位置，完成同步（官方顺序：先回执、后位置；两标志官方填 false）
        session.send(new ServerboundMovePlayerPosRotPacket(false, false, x, y, z, yaw, pitch));
    }
}
