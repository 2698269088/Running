package top.mcocet.running.pathfinding;

import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.bot.BlockActionGoal;
import top.mcocet.running.bot.MovementEngine;
import top.mcocet.running.bot.MovementGoal;
import top.mcocet.running.bot.PlayerInput;
import top.mcocet.running.bot.PlayerPhysics;
import xin.bbtt.mcbot.Bot;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 路径执行器（异步状态机）：把 {@link Path} 逐步转化为按键输入，交由 {@link MovementEngine}
 * 的 20 TPS 物理驱动；寻路计算全部提交到专用单线程 {@link ExecutorService}，引擎线程
 * 只负责跟随、决策与结果消费，绝不运行 A*。
 *
 * <p>每个引擎 tick：</p>
 * <ol>
 *   <li>消费执行器线程写入的 {@link PathFinder.Result}（若有）并采纳路径；</li>
 *   <li>已在目标 → 结束；</li>
 *   <li>有活动路径 → 平滑跟随（朝向限速、越过跳过、条件冲刺、上台阶/贴墙起跳），并做卡住检测；</li>
 *   <li>路径走完但未达目标（跨加载边界分段）→ 清空并异步续算；未加载导致失败 →
 *       等待新区块加载后重试（连续失败超限才判不可达）。</li>
 * </ol>
 *
 * <p>线程：{@link #tick} 仅运行于引擎线程；{@code path/index/卡住/平滑/计数} 均为引擎线程私有。
 * 跨线程仅 {@link #pendingResult}（{@link AtomicReference}）、{@link #computing}
 * （{@link AtomicBoolean}）与 {@link #generation}/{@link #cancelled}（{@code volatile}）。
 * {@link PathFinder} 只在执行器单线程上运行；{@link PathWorld}/{@code RunningWorld} 为只读且线程安全。</p>
 */
public final class PathingGoal implements MovementGoal {
    private static final Logger log = LoggerFactory.getLogger(PathingGoal.class.getSimpleName());

    /** 首次 / 续算搜索超时（毫秒，与旧 GotoCommand 一致） */
    private static final long PRIMARY_TIMEOUT_MS = 400;
    private static final long FAILURE_TIMEOUT_MS = 1500;
    /** 卡住重算的短超时（毫秒，避免长时间占用执行器） */
    private static final long REPLAN_PRIMARY_TIMEOUT_MS = 150;
    private static final long REPLAN_FAILURE_TIMEOUT_MS = 150;

    /** 到达判定的 Y 容差（格） */
    private static final double ARRIVE_Y_TOLERANCE = 0.6;
    /** 卡住判定：所需连续无进展 tick 数 */
    private static final int STUCK_TICKS = 25;
    /** 卡住时最小水平位移（小于视为无进展） */
    private static final double STUCK_EPSILON = 0.03;

    /** 无路径失败后等待新区块加载的重试间隔（tick） */
    private static final int CHUNK_RETRY_DELAY_TICKS = 10;
    /** 连续失败上限，超过判定目标不可达 */
    private static final int CHUNK_RETRY_MAX = 30;
    /** 总重算次数上限（病态循环兜底） */
    private static final int MAX_TOTAL_REPLANS = 256;
    
    /** 单个破块步的挖块总 tick 预算上限（超时未打通→放弃重算，防止无限卡挖） */
    private static final int MAX_MINE_TICKS_PER_STEP = 400;
    /** 站立眼高（相对脚部 y），用于计算挖掘朝向 */
    private static final double EYE_HEIGHT = 1.62D;

    /** 每 tick 最大转向角（度）——朝向限速，避免急转抖动 */
    private static final float MAX_YAW_STEP_DEG = 30.0F;
    /** 冲刺所需的朝向贴合阈值（度） */
    private static final float SPRINT_HEADING_THRESHOLD_DEG = 20.0F;

    private final Goal goal;
    private final PathWorld world;
    private final double arriveRadius;
    private final ExecutorService executor;

    // ---- 跨线程状态 ----
    private final AtomicReference<PathFinder.Result> pendingResult = new AtomicReference<>();
    private final AtomicBoolean computing = new AtomicBoolean(false);
    private volatile long generation = 0L;
    private volatile boolean cancelled = false;

    // ---- 引擎线程私有状态 ----
    private Path path;
    private int index;
    private boolean segmentMode;
    private int consecutiveFailures;
    private int totalReplans;
    private int waitingTicks;
    private boolean finished;

    // 卡住检测
    private int stuckTicks;
    private double lastCheckX = Double.NaN;
    private double lastCheckZ = Double.NaN;

    // 破块执行（引擎线程私有）
    /** 当前正在驱动的挖块子目标（复用 {@link BlockActionGoal}）；null 表示未在挖 */
    private BlockActionGoal activeMine;
    /** {@link #activeMine} 对应的目标格编码（防止目标变更时不重建） */
    private long activeMineKey;
    /** 当前正在挖的路径步索引（随步前进重置计时） */
    private int mineStepIndex = -1;
    /** 本步累计挖块 tick（超预算则判不可破放弃） */
    private int mineTicks;
    /** 上次发送的手持槽（避免重复发包） */
    private int lastCarriedSlot = -1;

    public PathingGoal(Goal goal, PathWorld world, double arriveRadius, ExecutorService executor) {
        this.goal = goal;
        this.world = world;
        this.arriveRadius = arriveRadius > 0 ? arriveRadius : 0.5;
        this.executor = executor;
        this.path = null;
        this.index = 0;
    }

    // ------------------------------------------------------------------
    // tick 主循环（引擎线程）
    // ------------------------------------------------------------------

    @Override
    public void tick(MovementEngine engine) {
        if (finished) {
            return;
        }
        PlayerPhysics physics = engine.physics();
        if (!physics.isPositioned()) {
            return;
        }

        double px = physics.x();
        double py = physics.y();
        double pz = physics.z();

        // (1) 消费执行器结果
        drainPendingResult();

        // (2) 已在目标
        if (goal.isInGoal(floor(px), floor(py), floor(pz))) {
            finished = true;
            engine.setInput(PlayerInput.EMPTY);
            log.info("[Running] 已到达目标 {}。", goal);
            return;
        }

        // (3) 有活动路径 → 平滑跟随
        if (path != null && index < path.length()) {
            followStep(engine, physics, px, py, pz);
            return;
        }

        // (4) 无活动路径：分段走完但未达目标 → 清空触发续算
        if (path != null && index >= path.length()) {
            path = null;
            resetStuck();
        }

        // 冷却等待（区块加载 / 结果未达）
        if (waitingTicks > 0) {
            waitingTicks--;
            engine.setInput(PlayerInput.EMPTY);
            return;
        }

        // 发起异步计算（若未在算）
        if (!computing.get()) {
            if (totalReplans >= MAX_TOTAL_REPLANS) {
                finished = true;
                engine.setInput(PlayerInput.EMPTY);
                log.info("[Running] 寻路重算次数超过上限，停止。");
                return;
            }
            requestRecompute(px, py, pz, false);
        }
        engine.setInput(PlayerInput.EMPTY);
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public void onFinish(MovementEngine engine, boolean completed) {
        // 取消：自增代次使在途结果作废，标记取消并结束（computing 由任务 finally 复位）
        cancelled = true;
        generation++;
        pendingResult.set(null);
        path = null;
        finished = true;
    }

    // ------------------------------------------------------------------
    // 结果消费与采纳（引擎线程）
    // ------------------------------------------------------------------

    private void drainPendingResult() {
        PathFinder.Result result = pendingResult.getAndSet(null);
        if (result == null) {
            return;
        }
        switch (result.type) {
            case SUCCESS_TO_GOAL -> {
                adopt(result.path, false);
                log.info("[Running] 已找到通往目标的路径，共 {} 步，开始移动。", result.path.length());
            }
            case SUCCESS_SEGMENT -> {
                adopt(result.path, true);
                log.info("[Running] 目标超出已加载范围，先沿可行分段移动 {} 步（到达边界后自动续算）。",
                        result.path.length());
            }
            case FAILURE -> {
                consecutiveFailures++;
                if (consecutiveFailures > CHUNK_RETRY_MAX) {
                    finished = true;
                    log.info("[Running] 目标不可达或超出可加载范围（连续 {} 次无可行路径）。", consecutiveFailures);
                } else {
                    // 多为已加载边界外尚未加载：等待新区块加载后重试
                    waitingTicks = CHUNK_RETRY_DELAY_TICKS;
                }
            }
            case CANCELLATION -> {
                // 被替换 / 取消，忽略
            }
        }
    }

    private void adopt(Path newPath, boolean segment) {
        this.path = newPath;
        this.index = 0;
        this.segmentMode = segment;
        this.consecutiveFailures = 0;
        this.waitingTicks = 0;
        resetStuck();
    }

    // ------------------------------------------------------------------
    // 异步计算提交（引擎线程提交，执行器线程执行）
    // ------------------------------------------------------------------

    private void requestRecompute(double px, double py, double pz, boolean stuck) {
        if (!computing.compareAndSet(false, true)) {
            return;
        }
        final long gen = generation;
        final int sx = floor(px);
        final int sy = floor(py);
        final int sz = floor(pz);
        final long primary = stuck ? REPLAN_PRIMARY_TIMEOUT_MS : PRIMARY_TIMEOUT_MS;
        final long failure = stuck ? REPLAN_FAILURE_TIMEOUT_MS : FAILURE_TIMEOUT_MS;
        totalReplans++;
        try {
            executor.submit(() -> {
                PathFinder.Result result;
                try {
                    PathFinder finder = new PathFinder(world, goal, sx, sy, sz);
                    result = finder.findPath(primary, failure);
                } catch (Throwable t) {
                    result = null;
                }
                try {
                    if (result != null && !cancelled && gen == generation) {
                        pendingResult.set(result);
                    }
                } finally {
                    computing.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            // 执行器已关闭（插件停用 / 卸载）
            computing.set(false);
            finished = true;
        }
    }

    // ------------------------------------------------------------------
    // 平滑跟随（引擎线程）
    // ------------------------------------------------------------------

    private void followStep(MovementEngine engine, PlayerPhysics physics, double px, double py, double pz) {
        // 到达 / 越过推进
        advancePastNodes(px, py, pz);
        if (index >= path.length()) {
            // 路径走完（到边界 / 结束）：清空触发续算（下一 tick 处理）
            path = null;
            resetStuck();
            engine.setInput(PlayerInput.EMPTY);
            return;
        }

        Path.Step step = path.steps().get(index);
        NodePos dest = step.dest;

        // 破块步：先挖通目标列，未打通前不前进（BlockActionGoal 自行转向并按 duration 计时）
        if (step.move.isBreak() && handleMining(engine, step, dest, px, py, pz)) {
            return;
        }

        double targetX = dest.x + 0.5;
        double targetZ = dest.z + 0.5;
        double dx = targetX - px;
        double dz = targetZ - pz;
        double horizontalDist = Math.hypot(dx, dz);

        // 朝向限速：由当前 yaw 每 tick 至多转 MAX_YAW_STEP_DEG 逼近目标
        float desiredYaw = yawToward(dx, dz);
        float newYaw = stepToward(physics.yaw(), desiredYaw, MAX_YAW_STEP_DEG);
        engine.look(newYaw, physics.pitch());

        // 输入：以前进为主
        PlayerInput input = new PlayerInput(true, false, false, false, false, false, false);

        // 冲刺：连续平坦同层直走/斜走 + 朝向已贴合 + 距航点足够
        float headingError = Math.abs(wrapDegrees(desiredYaw - newYaw));
        boolean sprint = world.allowSprint()
                && horizontalDist > 0.9
                && headingError < SPRINT_HEADING_THRESHOLD_DEG
                && safeToSprint(index);
        input = input.withSprint(sprint);

        // 跳跃：上台阶步或贴墙且可起跳；DESCEND/DOWNWARD 自然下落不起跳
        boolean wantJump = step.move.jumpUp() || physics.horizontalCollision();
        if (wantJump && physics.canJump()) {
            input = input.withJump(true);
        }
        engine.setInput(input);

        // 卡住检测 → 异步重算（不阻塞）
        updateStuck(px, pz, physics.onGround());
        if (stuckTicks >= STUCK_TICKS) {
            path = null;
            resetStuck();
            if (!computing.get() && totalReplans < MAX_TOTAL_REPLANS) {
                requestRecompute(px, py, pz, true);
            }
        }
    }

    /**
     * 推进当前航点：进入到达半径即前进；若到下一航点更近（越过当前中心）则跳过，减少绕点/画圈。
     */
    private void advancePastNodes(double px, double py, double pz) {
        List<Path.Step> steps = path.steps();
        while (index < steps.size()) {
            NodePos cur = steps.get(index).dest;
            double dxCur = cur.x + 0.5 - px;
            double dzCur = cur.z + 0.5 - pz;
            double dCur = Math.hypot(dxCur, dzCur);
            boolean arrivedY = Math.abs(py - cur.y) < ARRIVE_Y_TOLERANCE;

            if (dCur < arriveRadius && arrivedY) {
                index++;
                resetStuck();
                continue;
            }
            // 越过：已在本层且离下一航点更近 → 跳过当前（快速移动/斜走时避免绕点）
            if (arrivedY && index + 1 < steps.size()) {
                NodePos nxt = steps.get(index + 1).dest;
                double dNext = Math.hypot(nxt.x + 0.5 - px, nxt.z + 0.5 - pz);
                if (dNext < dCur) {
                    index++;
                    resetStuck();
                    continue;
                }
            }
            break;
        }
    }

    /** 该航点及其后继是否都是同层平地直走/斜走（可安全冲刺）。 */
    private boolean safeToSprint(int idx) {
        List<Path.Step> steps = path.steps();
        Path.Step cur = steps.get(idx);
        if (!isFlatForward(cur.move) || cur.dest.y != cur.src.y) {
            return false;
        }
        if (idx + 1 < steps.size()) {
            Path.Step nxt = steps.get(idx + 1);
            if (!isFlatForward(nxt.move) || nxt.dest.y != nxt.src.y) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    /** 当前步是否为平地直走 / 斜走。 */
    private static boolean isFlatForward(Moves move) {
        return move.name().startsWith("TRAVERSE") || move.name().startsWith("DIAGONAL");
    }

    // ------------------------------------------------------------------
    // 破块执行（引擎线程；Stage 4）
    // ------------------------------------------------------------------

    /**
     * 处理破块步：若目标列脚/头格仍被可挖方块阻挡，则驱动一个 {@link BlockActionGoal} 子目标
     * 逐 tick 挖掘（必要时先切到最优工具槽），本 tick 消费（不前进）。目标已畅通则返回
     * {@code false}，交回正常的行走逻辑。
     *
     * <p>安全性：{@code requiredTicks} 取规划期 {@link PathWorld#breakCost} 的最优（最快工具）
     * 估计并留正裕量；服务器按“实际手持 + 硬度”自主计时，按住时长只可能偏慢不可能偏快，
     * 故绝不触发 fastbreak。切槽若未生效，也只是“没断开→到预算后放弃重算”。</p>
     */
    private boolean handleMining(MovementEngine engine, Path.Step step, NodePos dest, double px, double py, double pz) {
        // 进入新步：重置计时与挖块子目标
        if (mineStepIndex != index) {
            mineStepIndex = index;
            mineTicks = 0;
            activeMine = null;
        }

        // 找第一个仍被阻挡、需要挖的格：脚格优先，其次头格
        int bx;
        int by;
        int bz;
        boolean needMine;
        if (!world.isPassable(dest.x, dest.y, dest.z)) {
            bx = dest.x;
            by = dest.y;
            bz = dest.z;
            needMine = true;
        } else if (!world.isPassable(dest.x, dest.y + 1, dest.z)) {
            bx = dest.x;
            by = dest.y + 1;
            bz = dest.z;
            needMine = true;
        } else {
            needMine = false;
            bx = by = bz = 0;
        }

        // 目标列已畅通：撤掉挖块子目标，交回行走逻辑
        if (!needMine) {
            activeMine = null;
            return false;
        }

        // 目标格不可挖（基岩 / 流体 / 未加载）：放弃本步并重算
        if (!world.isBreakable(bx, by, bz)) {
            giveUpMining(engine, px, py, pz);
            return true;
        }

        long key = blockKey(bx, by, bz);
        if (activeMine == null || activeMineKey != key) {
            startDig(bx, by, bz, key, px, py, pz);
        }
        if (activeMine == null) {
            giveUpMining(engine, px, py, pz);
            return true;
        }

        activeMine.tick(engine);
        mineTicks++;
        if (activeMine.isFinished()) {
            // 本轮挖完（或超时 / 出距放弃）：撤下，下一 tick 重新评估（可能轮到头格或已畅通）
            activeMine = null;
        }
        if (mineTicks > MAX_MINE_TICKS_PER_STEP) {
            giveUpMining(engine, px, py, pz);
            return true;
        }
        return true;
    }

    /** 新建针对 (bx,by,bz) 的挖块子目标：先按需切到最优工具槽，再按规划时长建 {@link BlockActionGoal}。 */
    private void startDig(int bx, int by, int bz, long key, double px, double py, double pz) {
        double cost = world.breakCost(bx, by, bz);
        int required = (int) Math.ceil(cost) + 2; // 正裕量：只可能偏慢，绝不 fastbreak
        int slot = world.bestToolSlotAt(bx, by, bz);
        if (slot >= 0 && slot != lastCarriedSlot) {
            sendCarried(slot);
            lastCarriedSlot = slot;
        }
        Direction face = faceTowardPlayer(bx, by, bz, px, py, pz);
        activeMine = BlockActionGoal.dig(Vector3i.from(bx, by, bz), face, required);
        activeMineKey = key;
    }

    /** 放弃破块：撤销在途挖掘，清空路径并请求（卡住）重算。 */
    private void giveUpMining(MovementEngine engine, double px, double py, double pz) {
        if (activeMine != null) {
            activeMine.onFinish(engine, false);
            activeMine = null;
        }
        path = null;
        resetStuck();
        if (!computing.get() && totalReplans < MAX_TOTAL_REPLANS) {
            requestRecompute(px, py, pz, true);
        }
    }

    /** 目标方块朝向玩家的面（用于 START/FINISH 命中面）。横穿为主，平局偏向水平面。 */
    private static Direction faceTowardPlayer(int bx, int by, int bz, double px, double py, double pz) {
        double dx = px - (bx + 0.5);
        double dz = pz - (bz + 0.5);
        double dy = py - by; // 玩家脚部相对方块所在层
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ax >= ay && ax >= az) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (az >= ay) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return dy > 0 ? Direction.UP : Direction.DOWN;
    }

    /** 方块坐标 → 唯一 long 键（对齐原版 BlockPos.asLong 的分位打包）。 */
    private static long blockKey(int x, int y, int z) {
        return (((long) x) << 42) ^ (((long) z) << 21) ^ ((long) y);
    }

    private static void sendCarried(int slot) {
        send(new ServerboundSetCarriedItemPacket(Math.max(0, Math.min(8, slot))));
    }

    private static void send(Packet packet) {
        ClientSession session = Bot.INSTANCE.getSession();
        if (session == null || !session.isConnected()) {
            return;
        }
        session.send(packet);
    }

    /**
     * 指向 (dx,dz) 的水平 yaw（度）。MC 朝向向量水平分量为 {@code (-sin(yaw), cos(yaw))}，
     * 故 {@code yaw = atan2(-dx, dz)}。dx/dz 全为 0 时返回 0（由到达判定兜底）。
     */
    private static float yawToward(double dx, double dz) {
        if (dx == 0 && dz == 0) {
            return 0F;
        }
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** 从 current 起步向 target，单步不超过 maxStep 度（沿最短角方向）。 */
    private static float stepToward(float current, float target, float maxStep) {
        float diff = wrapDegrees(target - current);
        if (diff > maxStep) {
            diff = maxStep;
        } else if (diff < -maxStep) {
            diff = -maxStep;
        }
        return current + diff;
    }

    /** 归一化角度到 (-180, 180]。 */
    private static float wrapDegrees(float deg) {
        float d = deg % 360.0F;
        if (d > 180.0F) {
            d -= 360.0F;
        }
        if (d <= -180.0F) {
            d += 360.0F;
        }
        return d;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    private void resetStuck() {
        stuckTicks = 0;
        lastCheckX = Double.NaN;
        lastCheckZ = Double.NaN;
    }

    /** 每 tick 更新无进展计数：着地且水平位移极小才累积，移动了则清零。 */
    private void updateStuck(double px, double pz, boolean onGround) {
        if (!onGround) {
            // 腾空（跳跃 / 下落）期间不计卡住，并刷新基准
            lastCheckX = px;
            lastCheckZ = pz;
            stuckTicks = 0;
            return;
        }
        if (!Double.isNaN(lastCheckX)) {
            double moved = Math.hypot(px - lastCheckX, pz - lastCheckZ);
            if (moved < STUCK_EPSILON) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
            }
        }
        lastCheckX = px;
        lastCheckZ = pz;
    }

    /** 当前正在执行的步骤索引（便于命令 / 日志观察进度）。 */
    public int currentIndex() {
        return index;
    }

    /** 路径总步数（尚无路径时为 0）。 */
    public int totalSteps() {
        return path != null ? path.length() : 0;
    }
}
