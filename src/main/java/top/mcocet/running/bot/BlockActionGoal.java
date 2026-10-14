package top.mcocet.running.bot;

import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bbtt.mcbot.Bot;

/**
 * 方块交互目标：由 {@link MovementEngine} 以 20 TPS 驱动，逐 tick 完成一次“挖掘”或“放置”，
 * 用于在协议层验证破坏/摆放链路是否可用（Stage 5 分相位执行器的手动前身）。
 *
 * <p>发包全部落在引擎 tick 内（{@code driveGoal} 阶段，即本 tick 的位置包与
 * {@code ClientTickEndPacket} 之前），因此每 tick 仍恰有一个 tick 结束包，不触碰 Grim
 * 的 TickTimer（type=flying / end）顺序校验。</p>
 *
 * <p>Grim 友好的关键设计：</p>
 * <ul>
 *   <li><b>起手持握朝向预热</b>：前若干 tick 只转向目标（由引擎在位置包里把朝向同步给服务器），
 *       之后才发交互包——保证服务器收到的“最后一次飞行包朝向”确实指向目标方块，避免 aim 误判；</li>
 *   <li><b>挖掘耗时按最慢（徒手）模型</b>：{@code requiredTicks} 由命令以“空手速度”算出，
 *       实际若手持正确的工具，服务器允许更快，但我们刻意按住更久——只可能偏慢不可能偏快，
 *       因此绝不触发 {@code fastbreak}；</li>
 *   <li><b>触及距离门控</b>：眼到方块中心距离超过生存交互距离时直接放弃，避免触发 reach 判定。</li>
 * </ul>
 *
 * <p>放置为“尽力而为”：以手持物品点击 {@link #blockPos} 的 {@link #face} 面，把新方块放进相邻空格。
 * Grim 的 invalid-place / 命中一致性等模块更严格，此手动命令仅用于连通性验证，正式执行留待 Stage 5。</p>
 */
public final class BlockActionGoal implements MovementGoal {
    private static final Logger log = LoggerFactory.getLogger(BlockActionGoal.class.getSimpleName());

    /** 站立眼高（相对脚部 y；蹲伏略低，测试命令按站立近似足够） */
    private static final double EYE_HEIGHT = 1.62D;
    /** 生存交互到达距离（格），留一点余量以贴近原版 4.5 上限 */
    private static final double REACH = 4.5D;
    /** 起手朝向预热 tick 数：确保至少一个带新朝向的飞行包先于交互包抵达服务器 */
    private static final int AIM_WARMUP_TICKS = 2;

    /** 交互模式 */
    public enum Mode {
        /** 挖掘：START → 逐 tick 计时 → FINISH */
        DIG,
        /** 放置：单次 USE_ITEM_ON + SWING */
        PLACE
    }

    private final Mode mode;
    private final Vector3i blockPos;
    private final Direction face;
    /** 放置命中点（相对被点方块的 0..1 局部坐标）；DIG 忽略 */
    private final float cursorX;
    private final float cursorY;
    private final float cursorZ;
    /** DIG：按住（START→FINISH）所需的最少 tick 数（已含安全余量），按徒手模型算出 */
    private final int requiredTicks;

    // 引擎线程私有状态
    private int tickCount;
    private boolean started;
    private boolean finished;

    private BlockActionGoal(Mode mode, Vector3i blockPos, Direction face,
                            float cursorX, float cursorY, float cursorZ, int requiredTicks) {
        this.mode = mode;
        this.blockPos = blockPos;
        this.face = face;
        this.cursorX = cursorX;
        this.cursorY = cursorY;
        this.cursorZ = cursorZ;
        this.requiredTicks = Math.max(1, requiredTicks);
    }

    /** 挖掘目标：在 {@code blockPos} 处按时长逐 tick 破坏。 */
    public static BlockActionGoal dig(Vector3i blockPos, Direction face, int requiredTicks) {
        return new BlockActionGoal(Mode.DIG, blockPos, face, 0.5F, 0.5F, 0.5F, requiredTicks);
    }

    /** 放置目标：点击 {@code clickPos} 的 {@code face} 面，把相邻空格填入。 */
    public static BlockActionGoal place(Vector3i clickPos, Direction face, float cursorX, float cursorY, float cursorZ) {
        return new BlockActionGoal(Mode.PLACE, clickPos, face, cursorX, cursorY, cursorZ, 1);
    }

    @Override
    public void tick(MovementEngine engine) {
        if (finished) {
            return;
        }
        PlayerPhysics physics = engine.physics();
        if (!physics.isPositioned()) {
            return;
        }

        tickCount++;

        // 目标方块中心（世界坐标）
        double centerX = blockPos.getX() + 0.5D;
        double centerY = blockPos.getY() + 0.5D;
        double centerZ = blockPos.getZ() + 0.5D;

        // 始终面向目标中心：由引擎在位置包里把朝向同步给服务器
        double eyeX = physics.x();
        double eyeY = physics.y() + EYE_HEIGHT;
        double eyeZ = physics.z();
        aimAt(engine, eyeX, eyeY, eyeZ, centerX, centerY, centerZ);

        if (mode == Mode.DIG) {
            tickDig(engine, physics, eyeX, eyeY, eyeZ, centerX, centerY, centerZ);
        } else {
            tickPlace(engine, physics, eyeX, eyeY, eyeZ, centerX, centerY, centerZ);
        }
    }

    private void tickDig(MovementEngine engine, PlayerPhysics physics,
                         double eyeX, double eyeY, double eyeZ,
                         double cx, double cy, double cz) {
        if (!started) {
            if (tickCount <= AIM_WARMUP_TICKS) {
                return; // 预热：只转向，等待朝向同步给服务器
            }
            if (outOfReach(eyeX, eyeY, eyeZ, cx, cy, cz)) {
                log.info("[Running] 挖掘目标超出触及距离，已放弃。");
                finished = true;
                return;
            }
            send(new ServerboundPlayerActionPacket(PlayerAction.START_DIGGING, blockPos, face, nextSequence()));
            send(new ServerboundSwingPacket(Hand.MAIN_HAND));
            started = true;
            return;
        }
        // 已起手：按住到点再结束（held 从起手后的 tick 起算）
        int heldTicks = tickCount - AIM_WARMUP_TICKS - 1;
        if (heldTicks >= requiredTicks) {
            send(new ServerboundPlayerActionPacket(PlayerAction.FINISH_DIGGING, blockPos, face, nextSequence()));
            finished = true;
        }
    }

    private void tickPlace(MovementEngine engine, PlayerPhysics physics,
                           double eyeX, double eyeY, double eyeZ,
                           double cx, double cy, double cz) {
        if (tickCount <= AIM_WARMUP_TICKS) {
            return; // 预热：只转向
        }
        if (outOfReach(eyeX, eyeY, eyeZ, cx, cy, cz)) {
            log.info("[Running] 放置目标超出触及距离，已放弃。");
            finished = true;
            return;
        }
        send(new ServerboundUseItemOnPacket(blockPos, face, Hand.MAIN_HAND,
                cursorX, cursorY, cursorZ, false, false, nextSequence()));
        send(new ServerboundSwingPacket(Hand.MAIN_HAND));
        finished = true;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public void onFinish(MovementEngine engine, boolean completed) {
        // 被替换 / 停止且挖掘已开始但未结束：撤销，避免方块残留破坏进度
        if (mode == Mode.DIG && started && !finished) {
            send(new ServerboundPlayerActionPacket(PlayerAction.CANCEL_DIGGING, blockPos, face, nextSequence()));
        }
        finished = true;
    }

    // ------------------------------------------------------------------
    // 朝向与几何辅助
    // ------------------------------------------------------------------

    /** 计算指向目标中心的 yaw/pitch 并交给引擎（朝向同步由引擎位置包完成）。 */
    private void aimAt(MovementEngine engine, double eyeX, double eyeY, double eyeZ,
                       double tx, double ty, double tz) {
        double dx = tx - eyeX;
        double dy = ty - eyeY;
        double dz = tz - eyeZ;
        double horizontal = Math.hypot(dx, dz);
        // MC 朝向向量水平分量 = (-sin(yaw), cos(yaw))，故 yaw = atan2(-dx, dz)
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        // 目标在上方（dy>0）应为负 pitch（抬头）
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, horizontal));
        engine.look(yaw, pitch);
    }

    private static boolean outOfReach(double eyeX, double eyeY, double eyeZ, double cx, double cy, double cz) {
        double dx = cx - eyeX;
        double dy = cy - eyeY;
        double dz = cz - eyeZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) > REACH;
    }

    private static int nextSequence() {
        return Bot.INSTANCE.getAndIncreaseSequence();
    }

    private static void send(Packet packet) {
        ClientSession session = Bot.INSTANCE.getSession();
        if (session == null || !session.isConnected()) {
            return;
        }
        session.send(packet);
    }
}
