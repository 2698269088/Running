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
 *   <li><b>挖掘耗时对齐原版进度累加</b>：复刻 {@code MultiPlayerGameMode#continueDestroyBlock}，
 *       每 tick 按<b>实际手持工具</b>的 {@code getDestroyProgress}（由调用方以
 *       {@code MiningCalculator#destroyProgress} 算出并传入）累加破坏进度，累加到
 *       {@code >= 1.0} 的那一 tick 立即发 FINISH_DIGGING——与服务端自主推进的裂纹同步，
 *       既不会提前触发 {@code fastbreak}，也不会像旧的固定 tick 数那样挖到裂纹充满后
 *       还盲按数秒才破块；</li>
 *   <li><b>触及距离门控</b>：眼到交互命中点（放置为声称面上的光标命中点，挖掘为方块中心）
 *       距离超过生存交互距离时直接放弃，避免触发 reach 判定；</li>
 *   <li><b>放置瞄准真实命中点 + Type M 偏转</b>：Vulcan 的 Scaffold “Expand / Invalid block face”
 *       （Type M）在 {@code BlockPlaceEvent} 上比较两个面——真实放置面 {@code var18 =
 *       getBlockAgainst().getFace(getBlock())} 与由<b>移动包 yaw</b> 推算的视线面 {@code var19 =
 *       Vulcan_m((yaw-90)%360)}；{@code var19} 只会落在水平面 {W,N,E,S}（对角为 SELF）、<b>永不含
 *       UP/DOWN，且不读 pitch</b>，而违规的必要条件是 {@code var18 == var19}。因此：
 *       <ul>
 *         <li>光标取声称面的真实命中点（{@code blockPos + cursor}）而非方块中心，保证服务端
 *             放置有效且面自洽；</li>
 *         <li>当点击<b>水平侧面</b>（横向搭桥 / 忍者桥）时，把同步给服务器的 yaw 偏到一个与所点
 *             面垂直的方向带，使 {@code var19 != var18}——由于放置成功只取决于 {@code USE_ITEM_ON}
 *             的 {@code position+face+cursor}、与移动包 yaw 无关，而 Type M 又只看 yaw，这一偏转可
 *             从结构上规避 Invalid block face；</li>
 *         <li>UP/DOWN 面天然 {@code var19(水平) != var18(垂直)}，保持自然瞄准、无需偏转。</li>
 *       </ul>
 *       眼高取当前姿势（站立/蹲伏）的真实值，与服务端视线计算一致。</li>
 * </ul>
 *
 * <p>放置为“尽力而为”：以手持物品点击 {@link #blockPos} 的 {@link #face} 面，把新方块放进相邻空格。
 * Grim 的 invalid-place / 命中一致性等模块更严格，此手动命令仅用于连通性验证，正式执行留待 Stage 5。</p>
 */
public final class BlockActionGoal implements MovementGoal {
    private static final Logger log = LoggerFactory.getLogger(BlockActionGoal.class.getSimpleName());

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
    /** DIG：每 tick 破坏进度（原版 {@code getDestroyProgress} 语义），由调用方按
     *  实际（或即将手持的）工具经 {@code MiningCalculator#destroyProgress} 算出；
     *  累加到 {@code >= 1.0} 时发送 FINISH_DIGGING */
    private final double perTickProgress;

    // 引擎线程私有状态
    private int tickCount;
    private boolean started;
    private boolean finished;
    /** DIG：已累计的破坏进度（对齐原版客户端 destroyProgress） */
    private double digProgress;

    private BlockActionGoal(Mode mode, Vector3i blockPos, Direction face,
                            float cursorX, float cursorY, float cursorZ, double perTickProgress) {
        this.mode = mode;
        this.blockPos = blockPos;
        this.face = face;
        this.cursorX = cursorX;
        this.cursorY = cursorY;
        this.cursorZ = cursorZ;
        // 下限保护：进度非正会让挖掘永不结束，调用方误算时退化为“快而不过头”的一拍
        this.perTickProgress = perTickProgress > 0.0D ? perTickProgress : 1.0D;
    }

    /** 挖掘目标：在 {@code blockPos} 处按原版进度累加逐 tick 破坏，进度满即结束。
     *
     * @param perTickProgress 实际手持工具对该方块的每 tick 破坏进度
     *                        （{@code MiningCalculator#destroyProgress}）
     */
    public static BlockActionGoal dig(Vector3i blockPos, Direction face, double perTickProgress) {
        return new BlockActionGoal(Mode.DIG, blockPos, face, 0.5F, 0.5F, 0.5F, perTickProgress);
    }

    /** 放置目标：点击 {@code clickPos} 的 {@code face} 面，把相邻空格填入。 */
    public static BlockActionGoal place(Vector3i clickPos, Direction face, float cursorX, float cursorY, float cursorZ) {
        return new BlockActionGoal(Mode.PLACE, clickPos, face, cursorX, cursorY, cursorZ, 1.0D);
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

        // 交互命中点（世界坐标）：放置用声称面上的实际光标命中点（反作弊会比对
        // 视线命中面与包声称面，瞄向方块中心会被判 Invalid block face），挖掘仍用方块中心
        double cx = blockPos.getX() + (mode == Mode.PLACE ? cursorX : 0.5D);
        double cy = blockPos.getY() + (mode == Mode.PLACE ? cursorY : 0.5D);
        double cz = blockPos.getZ() + (mode == Mode.PLACE ? cursorZ : 0.5D);

        // 始终面向命中点：由引擎在位置包里把朝向同步给服务器；
        // 眼高取当前姿势值（站立 1.62 / 蹲伏 1.27），与服务端视线计算一致
        double eyeX = physics.x();
        double eyeY = physics.y() + physics.eyeHeight();
        double eyeZ = physics.z();
        aimAt(engine, eyeX, eyeY, eyeZ, cx, cy, cz);

        if (mode == Mode.DIG) {
            tickDig(engine, physics, eyeX, eyeY, eyeZ, cx, cy, cz);
        } else {
            tickPlace(engine, physics, eyeX, eyeY, eyeZ, cx, cy, cz);
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
        }
        // 原版 continueDestroyBlock：起手的那一 tick 也计入进度
        digProgress += perTickProgress;
        if (digProgress >= 1.0D) {
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
        // 被替换 / 停止且挖掘已开始但未结束：撤销，避免方块残留破坏进度。
        // 注意：对齐官方 MultiPlayerGameMode.stopDestroyBlock，ABORT_DESTROY_BLOCK
        // 的面恒为 Direction.DOWN（faceId 0）；若带真实挖掘面，Grim PositionBreakB
        // 会记下该面并要求下一个挖掘包同面，否则判 "Cancelled block breaking with
        // an invalid block face"。
        if (mode == Mode.DIG && started && !finished) {
            send(new ServerboundPlayerActionPacket(PlayerAction.CANCEL_DIGGING, blockPos, Direction.DOWN, nextSequence()));
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
        // 放置水平侧面时偏转 yaw，规避 Vulcan Scaffold Type M（详见类注释）；UP/DOWN 与挖掘保持自然瞄准
        if (mode == Mode.PLACE) {
            yaw = scaffoldSafeYaw(face, yaw);
        }
        engine.look(yaw, pitch);
    }

    /**
     * Type M 安全 yaw：Vulcan 的 {@code Vulcan_m} 把 {@code var4=(yaw-90) mod 360} 按 45° 边界映射为
     * {@code [315,360)∪[0,45)→WEST, [45,135)→NORTH, [135,225)→EAST, [225,315)→SOUTH}。违规的必要条件
     * 是「真实放置面 var18（= 所点 face）== 由 yaw 推算的 var19」。对水平侧面，这里返回一个使
     * {@code var19} 落在与 {@code face} 垂直方向带中心的 yaw，保证 {@code var19 != var18}。UP/DOWN
     * 天然不相等（var19 恒为水平），故原样返回自然瞄准角。
     */
    private static float scaffoldSafeYaw(Direction face, float naturalYaw) {
        return switch (face) {
            case NORTH -> 270.0F;  // var19=EAST ≠ NORTH
            case SOUTH -> 112.5F;  // var19=WEST ≠ SOUTH
            case EAST -> 0.0F;     // var19=SOUTH ≠ EAST
            case WEST -> 180.0F;   // var19=NORTH ≠ WEST
            default -> naturalYaw; // UP / DOWN：无需偏转
        };
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
