package top.mcocet.running.pathfinding;

import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.bot.BlockActionGoal;
import top.mcocet.running.bot.InventoryMoveGoal;
import top.mcocet.running.bot.MovementEngine;
import top.mcocet.running.bot.MovementGoal;
import top.mcocet.running.bot.PlayerInput;
import top.mcocet.running.bot.PlayerPhysics;
import top.mcocet.running.world.InventoryState;
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
 *   <li>有活动路径 → 平滑跟随（朝向限速、越过跳过、条件冲刺、上台阶/贴墙起跳）；
 *       对齐 Baritone“每个 movement 自带 positionsToBreak”：跟随任一步前都复校 dest 站立列，
 *       被可挖方块堵住则先从全背包取最优工具挖开再走；并做卡住检测（应急挖通 / 重算）；</li>
 *   <li>路径走完但未达目标（跨加载边界分段）→ 清空并异步续算；未加载导致失败 →
 *       等待新区块加载后重试；连续失败超限 → 转入<b>直挖隧道</b>：优先以 A* 前瞻子目标
 *       在已加载区内规划安全路线（跳落 / 坑洞由引擎建模）照常跟随，算不出时才朝目标
 *       方向逐格直挖兜底，得到真实路径即切回常规行走。</li>
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

    /** 空/未设置的挖掘目标格编码 */
    private static final long UNSET_KEY = Long.MIN_VALUE;
    /** 取工具（背包搬迁）等待上限 tick；超时按“未搬到也继续挖”尽力而为 */
    private static final int RELOCATE_TIMEOUT_TICKS = 30;
    /** 一次应急挖通的总 tick 上限（超时未打通→放弃并交回重算） */
    private static final int EMERGENCY_MAX_TICKS = 300;
    /** 连续“无进展→升级应急挖通”的最大次数；前进一格即归零，超过则只重算不再挖 */
    private static final int MAX_STUCK_ESCALATIONS = 12;

    /** 直挖隧道模式总 tick 上限（约 5 分钟）：仍未到达且未算出路径 → 判不可达 */
    private static final int TUNNEL_MAX_TICKS = 6000;
    /** 直挖期间周期性重试常规寻路的间隔（tick）：区块随推进扩展，有真实路径立刻切回行走 */
    private static final int TUNNEL_RECOMPUTE_INTERVAL_TICKS = 40;
    /** 单个不可挖障碍的侧移绕行上限（格）：超过仍未绕过则判不可达 */
    private static final int TUNNEL_BYPASS_MAX_SIDE = 48;
    /** 直挖期间 A* 前瞻子目标距离（格）：优先在已加载区内算安全路线，盲挖仅作兜底 */
    private static final int TUNNEL_LOOKAHEAD = 24;
    /** 直挖盲走允许的最大主动下坠（格）：更深的坑交给 A* 规划绕行/阶梯下降，防止掉坑 */
    private static final int TUNNEL_MAX_DROP = 1;
    /** 搭桥放置：发出放置后等待服务器方块更新的 tick 数（超时未出现则重试） */
    private static final int PLACE_CONFIRM_TICKS = 8;
    /** 搭桥放置：同一缺口最多发送放置（USE_ITEM_ON）次数，超过判为放不上 */
    private static final int MAX_PLACE_TRIES = 3;
    /** 单个缺口搭桥的总 tick 预算（含取物/瞄准/确认），超时放弃回落等待/绕行 */
    private static final int BRIDGE_MAX_TICKS = 120;

    /** 每 tick 最大转向角（度）——朝向限速，避免急转抖动 */
    private static final float MAX_YAW_STEP_DEG = 30.0F;
    /** 冲刺所需的朝向贴合阈值（度） */
    private static final float SPRINT_HEADING_THRESHOLD_DEG = 20.0F;

    /** Parkour：沿方向自 src 中心起跳的最小位移（接近远边缘） */
    private static final double PARKOUR_TAKEOFF_PROG = 0.3;
    /** Parkour：再不跳就快要踩空坠落的强制起跳位移（AABB 支撑结束约 0.8） */
    private static final double PARKOUR_FORCE_PROG = 0.6;
    /** Parkour：宽跳跨（需冲刺）起跳前要求达到的沿向水平速度 */
    private static final double PARKOUR_MIN_SPEED = 0.06;
    /** Parkour：腾空最长 tick 数，超过判为失败放弃重算 */
    private static final int PARKOUR_MAX_AIR_TICKS = 60;

    private final Goal goal;
    private final PathWorld world;
    private final double arriveRadius;
    private final ExecutorService executor;
    /** 持续跟随模式：到达不结束，目标移出半径自动重算；豁免总重算上限 */
    private final boolean continuous;
    /** 背包镜像（把主背包的最优工具换到快捷栏后再挖）；可为 {@code null}（退化为不搬迁） */
    private final InventoryState inventory;
    /** 卡住应急挖通开关（对齐配置 pathfinding.stuck-dig-recovery） */
    private final boolean stuckDigEnabled;

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

    // 破块执行（引擎线程私有；计划破块步与卡住应急挖通共用）
    /** 正在驱动的挖块子目标；null 表示未在挖 */
    private BlockActionGoal activeMine;
    /** 取工具（背包→快捷栏搬迁）子目标；null 表示无需/已完成 */
    private InventoryMoveGoal activeRelocate;
    /** 当前挖通目标格编码（防止目标变更时不重建） */
    private long digTargetKey = UNSET_KEY;
    /** 当前挖通目标格坐标与挖掘上下文 */
    private int digTargetX;
    private int digTargetY;
    private int digTargetZ;
    private Direction digFace;
    private int digRequiredTicks;
    /** 挖掘时应手持的快捷栏槽（-1 无需改变） */
    private int digEquipSlot = -1;
    /** 本格累计挖/取 tick（超预算则判不可破放弃） */
    private int mineTicks;
    /** 上次发送的手持槽（避免重复发包） */
    private int lastCarriedSlot = -1;

    // 卡住应急挖通（引擎线程私有）
    /** 是否处于“卡住→朝前进方向挖通”的应急模式 */
    private boolean emergencyDig;
    /** 应急挖通目标列（X/Z 为水平列，FootY 为脚部层） */
    private int emergencyColX;
    private int emergencyFootY;
    private int emergencyColZ;
    /** 本次应急挖通累计 tick */
    private int emergencyTicks;
    /** 连续“无进展→重算”升级次数（前进一格即归零） */
    private int stuckEscalations;

    // 直挖隧道（引擎线程私有）：目标反复无路径时，朝目标方向逐格直挖前进
    /** 是否处于直挖隧道模式 */
    private boolean tunnelDig;
    /** 本次直挖累计 tick（超 {@link #TUNNEL_MAX_TICKS} 判不可达） */
    private int tunnelTicks;
    /** 区块重试耗尽，已置备“下一空闲时转入直挖隧道” */
    private boolean needTunnel;
    /** 直挖最近遇到的不可挖障碍列（{@code Integer.MIN_VALUE} 表示无）；主轴命中它则改走侧移绕行 */
    private int tunnelBlockX = Integer.MIN_VALUE;
    private int tunnelBlockZ = Integer.MIN_VALUE;
    /** 当前侧移绕行方向（±1，垂直于主轴） */
    private int tunnelSideDir = 1;
    /** 本障碍的绕行已反向过一次（再受阻即被困） */
    private boolean tunnelSideFlipped;
    /** 本障碍累计绕行格数（超 {@link #TUNNEL_BYPASS_MAX_SIDE} 判不可达） */
    private int tunnelSideSteps;

    // 放置搭桥（引擎线程私有；直挖遇到“无路可踩”的缺口时用背包方块铺地板）
    /** 在途放置子目标；null 表示未在发送放置 */
    private BlockActionGoal activePlace;
    /** 把方块从主背包搬到快捷栏的取物子目标 */
    private InventoryMoveGoal placeRelocate;
    /** 搭桥目标地板格编码（{@code UNSET_KEY} 表示无当前搭桥目标） */
    private long bridgeTargetKey = UNSET_KEY;
    /** 本次搭桥的点击面（相邻实心块 + 方向 + 光标） */
    private Placement bridgePlacement;
    /** 放置时应手持的快捷栏槽（-1 无需改变） */
    private int bridgeEquipSlot = -1;
    /** 本缺口搭桥累计 tick */
    private int bridgeTicks;
    /** 本缺口已发送放置的次数 */
    private int bridgeTries;
    /** 放置发出后等待方块更新中（>0 表示等待，归零后重查站立面） */
    private int bridgeConfirmTicks;

    // Parkour 执行（引擎线程私有）
    /** 当前正在驱动的 parkour 步索引（随步前进重置阶段） */
    private int parkourStepIndex = -1;
    /** 0 = 助跑 / 起跳前；上 src 中心朝 dest 加速； 1 = 已起跳腾空 */
    private int parkourPhase;
    /** 腾空累计 tick（超时未落→判失败） */
    private int parkourAirTicks;

    public PathingGoal(Goal goal, PathWorld world, double arriveRadius, ExecutorService executor) {
        this(goal, world, null, arriveRadius, executor, false, false);
    }

    public PathingGoal(Goal goal, PathWorld world, double arriveRadius, ExecutorService executor,
                       boolean continuous) {
        this(goal, world, null, arriveRadius, executor, continuous, false);
    }

    public PathingGoal(Goal goal, PathWorld world, InventoryState inventory, double arriveRadius,
                       ExecutorService executor, boolean continuous, boolean stuckDigEnabled) {
        this.goal = goal;
        this.world = world;
        this.inventory = inventory;
        this.arriveRadius = arriveRadius > 0 ? arriveRadius : 0.5;
        this.executor = executor;
        this.continuous = continuous;
        this.stuckDigEnabled = stuckDigEnabled;
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
            if (!continuous) {
                finished = true;
                engine.setInput(PlayerInput.EMPTY);
                log.info("[Running] 已到达目标 {}。", goal);
                return;
            }
            // 持续跟随：到达保持半径即停下待命，清空在途路径；
            // 目标移出半径后 isInGoal 转假，下一 tick 自然触发重算
            if (path != null) {
                path = null;
                index = 0;
                resetStuck();
            }
            engine.setInput(PlayerInput.EMPTY);
            return;
        }

        // (3) 卡住应急挖通：优先于常规跟随，朝前进方向把阻挡方块挖通
        if (emergencyDig) {
            tickEmergencyDig(engine, px, py, pz);
            return;
        }

        // (4) 直挖隧道：有 A* 路径（真实目标或前瞻子目标）照常安全跟随；无路径才逐格直挖兜底
        if (tunnelDig) {
            if (path != null) {
                if (index < path.length()) {
                    followStep(engine, physics, px, py, pz);
                    return;
                }
                // 前瞻段走完：清空并立刻向下一段发起规划，直挖步进仅在此间隙兜底
                path = null;
                index = 0;
                resetStuck();
                if (!computing.get()) {
                    requestTunnelPath(px, py, pz);
                }
            }
            tickTunnelDig(engine, physics, px, py, pz);
            return;
        }

        // (5) 有活动路径 → 平滑跟随
        if (path != null && index < path.length()) {
            followStep(engine, physics, px, py, pz);
            return;
        }

        // (6) 无活动路径：分段走完但未达目标 → 清空触发续算
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
            if (needTunnel) {
                // 目标反复无路径：不再换地重算，直接朝目标方向挖隧道前进
                needTunnel = false;
                int[] anchor = tunnelAnchor();
                if (world.canBreak() && anchor != null) {
                    beginTunnel(anchor);
                    // 进入直挖先给 A* 一次机会：已加载区内若能算出安全路线则直接跟随，不走盲挖
                    requestTunnelPath(px, py, pz);
                } else {
                    finished = true;
                    engine.setInput(PlayerInput.EMPTY);
                    log.info("[Running] 目标不可达或超出可加载范围（无可行路径，且未启用/无法直挖）。");
                    return;
                }
            } else {
                requestRecompute(px, py, pz, false);
            }
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
                log.info("[Running] 已找到可通行路径（真实目标或前瞻子目标），共 {} 步，开始移动。", result.path.length());
            }
            case SUCCESS_SEGMENT -> {
                adopt(result.path, true);
                log.info("[Running] 目标超出已加载范围，先沿可行分段移动 {} 步（到达边界后自动续算）。",
                        result.path.length());
            }
            case FAILURE -> {
                consecutiveFailures++;
                if (consecutiveFailures > CHUNK_RETRY_MAX) {
                    // 等待区块加载已反复无果：不再直接判不可达，置备“直挖隧道”——
                    // 由空闲分支朝目标方向直接开挖（不可挖/超时才判不可达）
                    consecutiveFailures = 0;
                    needTunnel = true;
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
        if (continuous) {
            // 持续跟随会不断重算，成功采纳即将总重算计数归零（豁免上限）
            this.totalReplans = 0;
        }
        resetStuck();
    }

    // ------------------------------------------------------------------
    // 异步计算提交（引擎线程提交，执行器线程执行）
    // ------------------------------------------------------------------

    private void requestRecompute(double px, double py, double pz, boolean stuck) {
        requestRecomputeTo(goal, px, py, pz, stuck);
    }

    /** 以指定目标发起异步计算（直挖模式用前瞻子目标复用同一套异步状态机）。 */
    private void requestRecomputeTo(Goal target, double px, double py, double pz, boolean stuck) {
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
                    PathFinder finder = new PathFinder(world, target, sx, sy, sz);
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
    // 直挖隧道（目标反复无路径时，朝目标方向逐格直挖前进）
    // ------------------------------------------------------------------

    /**
     * 解析目标锚点列（XZ）：{@link GoalXZ}/{@link GoalBlock} 取字段坐标；
     * {@link GoalFollow} 取目标玩家当前/最近已知列。无法解析（如跟随目标从未出现过）
     * 返回 {@code null}，此时不进入/维持直挖。
     */
    private int[] tunnelAnchor() {
        if (goal instanceof GoalXZ gx) {
            return new int[]{gx.getX(), gx.getZ()};
        }
        if (goal instanceof GoalBlock gb) {
            return new int[]{gb.x, gb.z};
        }
        if (goal instanceof GoalFollow gf) {
            return gf.targetColumn();
        }
        return null;
    }

    /** 进入直挖模式：区块重试耗尽后不再换位重算，而是朝目标方向直接开挖。 */
    private void beginTunnel(int[] anchor) {
        tunnelDig = true;
        tunnelTicks = 0;
        consecutiveFailures = 0;
        tunnelBlockX = Integer.MIN_VALUE;
        tunnelBlockZ = Integer.MIN_VALUE;
        tunnelSideDir = 1;
        tunnelSideFlipped = false;
        tunnelSideSteps = 0;
        bridgeTargetKey = UNSET_KEY;
        bridgePlacement = null;
        activePlace = null;
        placeRelocate = null;
        digTargetKey = UNSET_KEY;
        activeMine = null;
        activeRelocate = null;
        log.info("[Running] 连续寻路无果，转入直挖模式：朝目标列 ({}, {}) 直接挖进。", anchor[0], anchor[1]);
    }

    /** 退出直挖模式（撤下在途挖掘 / 放置子目标）。 */
    private void endTunnel(MovementEngine engine) {
        tunnelDig = false;
        tunnelTicks = 0;
        clearDig(engine);
        clearBridge(engine);
    }

    /**
     * 直挖期间的寻路请求：优先让 A* 引擎在<b>已加载区域</b>内规划安全路线。距锚点已在
     * {@link #TUNNEL_LOOKAHEAD} 格内且目标列已加载时直接算到真实目标；否则沿锚点方向
     * 截取前瞻点构造临时 {@link GoalXZ} 子目标（不约束 Y）——A* 对子目标会完整建模
     * 跳跃 / 下落 / parkour 与 BREAK 代价，天然避开会坠坑的地面断裂；算出的路径照常由
     * followStep 安全跟随，逐格直挖仅在 A* 无果时作为兜底推进。
     */
    private void requestTunnelPath(double px, double py, double pz) {
        int[] anchor = tunnelAnchor();
        if (anchor == null) {
            requestRecompute(px, py, pz, false);
            return;
        }
        int fx = floor(px);
        int fz = floor(pz);
        int dx = anchor[0] - fx;
        int dz = anchor[1] - fz;
        double dist = Math.hypot(dx, dz);
        if (dist <= TUNNEL_LOOKAHEAD && world.isLoaded(anchor[0], anchor[1])) {
            // 已在视野内：直接算到真实目标（能算出即说明世界恢复正常，沿用常规跟随）
            requestRecompute(px, py, pz, false);
            return;
        }
        // 前瞻子目标：沿 bot→锚点连线截取至多 LOOKAHEAD 格（锚点更近时取其列）
        double t = Math.min(1.0, TUNNEL_LOOKAHEAD / dist);
        int subX = fx + (int) Math.round(dx * t);
        int subZ = fz + (int) Math.round(dz * t);
        requestRecomputeTo(new GoalXZ(subX, subZ), px, py, pz, false);
    }

    /**
     * 直挖兜底步进一个 tick（仅在无 A* 路径时到达此处；有路径时由外层分支直接 followStep）：
     * 周期性向 {@link #requestTunnelPath A* 前瞻子目标}求安全路线，同时取朝锚点主轴方向的
     * 下一列，用共用的 {@link #digColumn} 挖开脚+头两格后走一格（全背包最优工具、
     * fastbreak 安全裕量均由该例程保证）。主轴遇不可挖障碍时<b>不判死</b>：记住障碍列，
     * 改朝垂直方向侧移绕行（顺墙法，优先偏向锚点一侧；受阻再反向，两向皆阻才判被困）；
     * 前方未加载→等待加载；落点列无站立面且下坠超 {@link #TUNNEL_MAX_DROP} 格→不盲踩
     * （深坑交给 A* 规划）；绕行超过 {@link #TUNNEL_BYPASS_MAX_SIDE} 格或总 tick 超限才判不可达。
     */
    private void tickTunnelDig(MovementEngine engine, PlayerPhysics physics, double px, double py, double pz) {
        if (++tunnelTicks > TUNNEL_MAX_TICKS) {
            endTunnel(engine);
            finished = true;
            engine.setInput(PlayerInput.EMPTY);
            log.info("[Running] 直挖 {} tick 仍未到达目标，判定不可达。", TUNNEL_MAX_TICKS);
            return;
        }
        // 隧道推进使区块不断扩展：周期性请求 A* 前瞻路径，一有安全路线立刻切回“走路”
        if (!computing.get() && tunnelTicks % TUNNEL_RECOMPUTE_INTERVAL_TICKS == 0) {
            requestTunnelPath(px, py, pz);
        }
        int[] anchor = tunnelAnchor();
        if (anchor == null) {
            engine.setInput(PlayerInput.EMPTY);
            return;
        }
        int fx = floor(px);
        int fy = floor(py);
        int fz = floor(pz);
        int dx = anchor[0] - fx;
        int dz = anchor[1] - fz;
        // 轴对齐前进一步：取水平距离较大的分量方向（避免斜角贴边）
        int stepX = Math.abs(dx) >= Math.abs(dz) ? Integer.signum(dx) : 0;
        int stepZ = stepX == 0 ? Integer.signum(dz) : 0;
        if (stepX == 0 && stepZ == 0) {
            // XZ 已达目标列（Y 不符 / 在跟随半径内）：交给到达判定与周期重算收尾
            engine.setInput(PlayerInput.EMPTY);
            return;
        }
        int colX = fx + stepX;
        int colZ = fz + stepZ;
        // 主轴正对已记录的不可挖障碍 → 改用垂直侧移的绕行列（顺墙绕过，不判不可达）
        boolean sidestep = (colX == tunnelBlockX && colZ == tunnelBlockZ);
        if (sidestep) {
            if (stepX != 0) {
                colZ = fz + tunnelSideDir;
            } else {
                colX = fx + tunnelSideDir;
            }
        }
        // 未加载区块无法挖也无法判落点：原地等待加载（区块随推进会进来）
        if (!world.isLoaded(colX, colZ)) {
            engine.setInput(PlayerInput.EMPTY);
            return;
        }
        DigStatus st = digColumn(engine, colX, fy, colZ, px, py, pz);
        if (st == DigStatus.BUSY) {
            engine.setInput(PlayerInput.EMPTY);
            return;
        }
        if (st == DigStatus.FAILED) {
            if (!sidestep) {
                // 主轴不可挖：记下障碍列，开启垂直绕行（优先偏向锚点所在侧，无偏向先取正向）
                tunnelBlockX = colX;
                tunnelBlockZ = colZ;
                int sd = stepX != 0 ? Integer.signum(dz) : Integer.signum(dx);
                tunnelSideDir = sd == 0 ? 1 : sd;
                tunnelSideFlipped = false;
                tunnelSideSteps = 0;
                log.info("[Running] 前方 ({}, {}) 不可挖，绕行避开。", colX, colZ);
                engine.setInput(PlayerInput.EMPTY);
                return;
            }
            // 绕行方向也受阻：反向一次；两向皆阻 → 确实被困，判不可达
            if (!tunnelSideFlipped) {
                tunnelSideFlipped = true;
                tunnelSideDir = -tunnelSideDir;
                tunnelSideSteps = 0;
                engine.setInput(PlayerInput.EMPTY);
                return;
            }
            endTunnel(engine);
            finished = true;
            engine.setInput(PlayerInput.EMPTY);
            log.info("[Running] 直挖绕行失败：障碍列 ({}, {}) 两侧均受阻，判定不可达。",
                    tunnelBlockX, tunnelBlockZ);
            return;
        }
        if (!sidestep) {
            // 主轴可通行：障碍已让开 / 消失，清除绕行记录
            tunnelBlockX = Integer.MIN_VALUE;
            tunnelBlockZ = Integer.MIN_VALUE;
        } else if (++tunnelSideSteps > TUNNEL_BYPASS_MAX_SIDE) {
            // 单个障碍绕行超上限仍未让开：不再耗着，判不可达
            endTunnel(engine);
            finished = true;
            engine.setInput(PlayerInput.EMPTY);
            log.info("[Running] 直挖绕行超过 {} 格仍未能绕过障碍 ({}, {})，判定不可达。",
                    TUNNEL_BYPASS_MAX_SIDE, tunnelBlockX, tunnelBlockZ);
            return;
        }
        // 列已畅通：盲走只允许同层或 ≤1 格下坠；更深的坑交给 A* 前瞻规划绕行/阶梯下降，
        // 若允许放置搭桥则先“铺”一块地板再走上去（无路可踩 → 放方块补齐踩点）
        if (!world.isStandable(colX, fy - 1, colZ) && !fallSafeWithin(colX, fy, colZ, TUNNEL_MAX_DROP)) {
            if (!world.canPlace()
                    || bridgeColumn(engine, colX, fy, colZ, fx, fz) != DigStatus.CLEARED) {
                engine.setInput(PlayerInput.EMPTY);
                return;
            }
        }
        // 走一格：朝向限速逼近目标列中心，贴墙时起跳
        float desiredYaw = yawToward(colX + 0.5 - px, colZ + 0.5 - pz);
        float newYaw = stepToward(physics.yaw(), desiredYaw, MAX_YAW_STEP_DEG);
        engine.look(newYaw, physics.pitch());
        PlayerInput input = new PlayerInput(true, false, false, false, false, false, false);
        if (physics.horizontalCollision() && physics.canJump()) {
            input = input.withJump(true);
        }
        engine.setInput(input);
    }

    /** 该列下行是否安全：maxDrop 内找到站立面且经过列畅通；否则 false（不踩深坑）。 */
    private boolean fallSafeWithin(int colX, int footY, int colZ, int maxDrop) {
        for (int cand = footY - 1; cand >= footY - maxDrop && cand > world.minY(); cand--) {
            if (!world.isPassable(colX, cand, colZ) || !world.isPassable(colX, cand + 1, colZ)) {
                return false;
            }
            if (world.isStandable(colX, cand - 1, colZ)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 放置搭桥（引擎线程；直挖遇到“无路可踩”的缺口时铺地板）
    // ------------------------------------------------------------------

    /**
     * 把 {@code (colX, colZ)} 列脚下一格铺成可踩的地板：用 {@link PathWorld#planPlaceBlock}
     * 在<b>整个背包</b>选出可用方块物品（数量多的优先，桥长取决于存量）；若物品位于主背包，
     * 先经 {@link InventoryMoveGoal} 换到快捷栏，再手持并驱动 {@link BlockActionGoal#place}
     * 向相邻实心面点击放置，等待方块更新后复查站立面。
     *
     * <p>点击锚优先级：脚下相邻块（忍者桥，向侧面放置）→ 目标下方块（UP 面）→ 四横向相邻块；
     * 与 ActCommand 验证过的链路一致，光标取面中心略内偏。</p>
     *
     * @return {@link DigStatus#CLEARED} 该格已可踩（原本就有或已铺好）；{@link DigStatus#BUSY}
     *         取物 / 放置 / 等待更新中；{@link DigStatus#FAILED} 无锚点 / 无物品 / 超预算
     */
    private DigStatus bridgeColumn(MovementEngine engine, int colX, int footY, int colZ,
                                   int standX, int standZ) {
        int floorY = footY - 1;
        if (world.isStandable(colX, floorY, colZ)) {
            if (bridgeTargetKey != UNSET_KEY) {
                log.info("[Running] 已在 ({}, {}, {}) 铺方块搭路。", colX, floorY, colZ);
                clearBridge(engine);
            }
            return DigStatus.CLEARED;
        }

        long key = blockKey(colX, floorY, colZ);
        if (key != bridgeTargetKey) {
            // 新缺口：确认目标是空气，规划点击锚与放置物品
            clearBridge(engine);
            if (world.stateAt(colX, floorY, colZ) != PathWorld.AIR) {
                return DigStatus.FAILED; // 非空且不可踩（如含水/植物），放置无意义
            }
            Placement anchor = findPlaceAnchor(colX, floorY, colZ, standX, standZ);
            if (anchor == null) {
                return DigStatus.FAILED;
            }
            PlaceTool plan = world.planPlaceBlock();
            if (!plan.placeable()) {
                return DigStatus.FAILED;
            }
            bridgeTargetKey = key;
            bridgePlacement = anchor;
            bridgeEquipSlot = plan.equipHotbarSlot();
            bridgeTicks = 0;
            bridgeTries = 0;
            bridgeConfirmTicks = 0;
            activePlace = null;
            if (plan.needsRelocate() && inventory != null) {
                log.info("[Running] 搭桥需先从主背包槽 {} 取方块到快捷栏槽 {}。",
                        plan.relocateFromSlot(), bridgeEquipSlot);
                placeRelocate = InventoryMoveGoal.toHotbar(inventory, plan.relocateFromSlot(), bridgeEquipSlot);
            } else {
                equipForPlace();
            }
            log.info("[Running] ({}, {}, {}) 无路可踩，尝试放置方块搭桥。", colX, floorY, colZ);
        }

        // 预算：整体超时与重试次数双封顶，防止反复放置不生效地耗着
        if (++bridgeTicks > BRIDGE_MAX_TICKS || bridgeTries >= MAX_PLACE_TRIES) {
            clearBridge(engine);
            return DigStatus.FAILED;
        }

        // 阶段 A：取方块（主背包 → 快捷栏搬迁）
        if (placeRelocate != null) {
            placeRelocate.tick(engine);
            if (placeRelocate.isFinished() || bridgeTicks >= RELOCATE_TIMEOUT_TICKS) {
                placeRelocate = null;
                equipForPlace();
            }
            return DigStatus.BUSY;
        }

        // 阶段 B：放置发出后等待方块更新包回灌
        if (bridgeConfirmTicks > 0) {
            bridgeConfirmTicks--;
            return DigStatus.BUSY;
        }

        // 阶段 C：驱动放置子目标（BlockActionGoal 自带转向预热与触及距离门控）
        if (activePlace != null) {
            activePlace.tick(engine);
            if (activePlace.isFinished()) {
                activePlace = null;
                bridgeTries++;
                bridgeConfirmTicks = PLACE_CONFIRM_TICKS;
            }
            return DigStatus.BUSY;
        }
        activePlace = BlockActionGoal.place(
                Vector3i.from(bridgePlacement.x, bridgePlacement.y, bridgePlacement.z),
                bridgePlacement.face, bridgePlacement.cursorX, bridgePlacement.cursorY, bridgePlacement.cursorZ);
        return DigStatus.BUSY;
    }

    /**
     * 为目标地板格 {@code (tx, ty, tz)} 找一个相邻可依附块与点击面。
     * 优先脚下块（忍者桥侧放），其次下方块（UP 面），再四横向；都不实心可依附则 null。
     */
    private Placement findPlaceAnchor(int tx, int ty, int tz, int standX, int standZ) {
        if (world.isStandable(standX, ty, standZ)) {
            Direction f = faceBetween(standX, ty, standZ, tx, ty, tz);
            if (f != null) {
                return new Placement(standX, ty, standZ, f);
            }
        }
        if (world.isStandable(tx, ty - 1, tz)) {
            return new Placement(tx, ty - 1, tz, Direction.UP);
        }
        int[][] offs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : offs) {
            int ax = tx + o[0];
            int az = tz + o[1];
            if (ax == standX && az == standZ) {
                continue; // 已在上面优先尝试过
            }
            if (world.isStandable(ax, ty, az)) {
                return new Placement(ax, ty, az, faceBetween(ax, ty, az, tx, ty, tz));
            }
        }
        return null;
    }

    /** 相邻两格间“从源指向目标”的面；不相邻返回 null。 */
    private static Direction faceBetween(int sx, int sy, int sz, int dx, int dy, int dz) {
        int ex = dx - sx;
        int ey = dy - sy;
        int ez = dz - sz;
        if (Math.abs(ex) + Math.abs(ey) + Math.abs(ez) != 1) {
            return null;
        }
        if (ex == 1) return Direction.EAST;
        if (ex == -1) return Direction.WEST;
        if (ez == 1) return Direction.SOUTH;
        if (ez == -1) return Direction.NORTH;
        return ey == 1 ? Direction.UP : Direction.DOWN;
    }

    /** 面中心命中点（与 ActCommand 一致：法向轴取贴近面内侧的 0.001/0.999 避免边界抖动）。 */
    private static float[] cursorForFace(Direction face) {
        return switch (face) {
            case DOWN -> new float[]{0.5F, 0.001F, 0.5F};
            case UP -> new float[]{0.5F, 0.999F, 0.5F};
            case NORTH -> new float[]{0.5F, 0.5F, 0.001F};
            case SOUTH -> new float[]{0.5F, 0.5F, 0.999F};
            case WEST -> new float[]{0.001F, 0.5F, 0.5F};
            case EAST -> new float[]{0.999F, 0.5F, 0.5F};
        };
    }

    /** 一次搭桥放置的点击上下文：被点方块 + 面 + 光标。 */
    private static final class Placement {
        final int x;
        final int y;
        final int z;
        final Direction face;
        final float cursorX;
        final float cursorY;
        final float cursorZ;

        Placement(int x, int y, int z, Direction face) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.face = face;
            float[] c = cursorForFace(face);
            this.cursorX = c[0];
            this.cursorY = c[1];
            this.cursorZ = c[2];
        }
    }

    /** 若需要且尚未手持，则切换到规划好的放置用快捷栏槽。 */
    private void equipForPlace() {
        if (bridgeEquipSlot >= 0 && bridgeEquipSlot != lastCarriedSlot) {
            sendCarried(bridgeEquipSlot);
            lastCarriedSlot = bridgeEquipSlot;
        }
    }

    /** 撤下在途放置 / 取物子目标并复位搭桥状态（不触碰寻路状态）。 */
    private void clearBridge(MovementEngine engine) {
        if (activePlace != null) {
            activePlace.onFinish(engine, false);
            activePlace = null;
        }
        placeRelocate = null;
        bridgeTargetKey = UNSET_KEY;
        bridgePlacement = null;
        bridgeTicks = 0;
        bridgeTries = 0;
        bridgeConfirmTicks = 0;
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

        // Parkour 步：交由专用多 tick 跟随状态机（助跑→边缘起跳→腾空落地），本 tick 消费
        if (step.move.isParkour()) {
            followParkour(engine, physics, step, px, py, pz);
            return;
        }

        // 对齐 Baritone “每个 movement 自带 positionsToBreak、运行时自动挖开脚下前方”的挖掘寻路机制：
        // 无论本步是显式 BREAK 还是普通 TRAVERSE/ASCEND/DESCEND/DIAGONAL，跟随前都复校 dest 站立列
        // （脚格 + 头格）是否畅通；若被“可挖的实体”堵住（寻路后才出现的方块 / 世界变化 / 区块刷新 /
        // 几何边缘情形），先从整个背包取最优工具把它挖开再前进——这正是“BOT 以为能走过去却被方块卡住”的根因。
        // 仅在显式 BREAK，或允许破块（canBreak）时才挖；否则落入卡住应急挖通 / 重算。
        if (step.move.isBreak() || world.canBreak()) {
            DigStatus st = digColumn(engine, dest.x, dest.y, dest.z, px, py, pz);
            if (st == DigStatus.BUSY) {
                engine.setInput(PlayerInput.EMPTY);
                return;
            }
            if (st == DigStatus.FAILED) {
                giveUpMining(engine, px, py, pz);
                engine.setInput(PlayerInput.EMPTY);
                return;
            }
            // CLEARED：dest 站立列已畅通，落入下方行走逻辑
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

        // 跳跃：凡“落点高于起点”的动作（含对角上升）统一起跳；另外 jumpUp 显式上跳
        // 与贴墙受阻也起跳；DESCEND/DOWNWARD 等 dest.y&lt;src.y 自然下落不起跳
        boolean ascending = dest.y > step.src.y;
        boolean wantJump = ascending || step.move.jumpUp() || physics.horizontalCollision();
        if (wantJump && physics.canJump()) {
            input = input.withJump(true);
        }
        engine.setInput(input);

        // 卡住检测 → 先尝试朝 dest 列应急挖通（需开启且允许破块）；否则清空路径异步重算
        updateStuck(px, pz, physics.onGround());
        if (stuckTicks >= STUCK_TICKS) {
            resetStuck();
            stuckEscalations++;
            boolean escalated = stuckDigEnabled && world.canBreak()
                    && stuckEscalations <= MAX_STUCK_ESCALATIONS
                    && beginEmergencyDig(dest, px, py, pz);
            if (!escalated) {
                path = null;
                if (!computing.get() && totalReplans < MAX_TOTAL_REPLANS) {
                    requestRecompute(px, py, pz, true);
                }
            }
        }
    }

    /**
     * Parkour 跳跨跟随（引擎线程）：对齐 Baritone {@code MovementParkour.updateState} 的“先助跑
     * 建 momentum、近边缘起跳、腾空保持前进/冲刺”思路。分两阶段：
     * <ul>
     *   <li>阶段 0（助跑）：朝 dest 直行，需要宽跳时保持冲刺；当沿向位移达边缘且已具足够水平
     *       速度（或再不跳就踩空）时起跳，进入阶段 1；</li>
     *   <li>阶段 1（腾空）：持续前进 + 冲刺 + 按跳；落至 dest 邻域且回到可站高度→本步完成 index++；
     *       若坠到 src 下方或腾空超时→判失败，清空路径异步重算。</li>
     * </ul>
     * 本方法总在当前步为 parkour 时消费一个 tick（返回），不走普通行走逻辑。
     */
    private void followParkour(MovementEngine engine, PlayerPhysics physics, Path.Step step,
                               double px, double py, double pz) {
        // 进入新步：重置阶段
        if (parkourStepIndex != index) {
            parkourStepIndex = index;
            parkourPhase = 0;
            parkourAirTicks = 0;
        }

        NodePos src = step.src;
        NodePos dest = step.dest;
        int dirX = Integer.signum(dest.x - src.x);
        int dirZ = Integer.signum(dest.z - src.z);
        int gap = Math.abs(dest.x - src.x) + Math.abs(dest.z - src.z);
        boolean ascend = dest.y > src.y;
        boolean needSprint = world.allowSprint() && (gap >= 3 || ascend);

        double targetX = dest.x + 0.5;
        double targetZ = dest.z + 0.5;
        // 朝向沿方向直行（瞄准落点中心）
        float desiredYaw = yawToward(targetX - px, targetZ - pz);
        float newYaw = stepToward(physics.yaw(), desiredYaw, MAX_YAW_STEP_DEG);
        engine.look(newYaw, physics.pitch());

        // 沿方向自 src 中心的位移与水平速度
        double prog = (px - (src.x + 0.5)) * dirX + (pz - (src.z + 0.5)) * dirZ;
        double alongSpeed = physics.deltaX() * dirX + physics.deltaZ() * dirZ;

        PlayerInput input = new PlayerInput(true, false, false, false, false, false, needSprint);

        if (parkourPhase == 0) {
            boolean speedReady = !needSprint || alongSpeed >= PARKOUR_MIN_SPEED;
            boolean atEdge = prog >= PARKOUR_TAKEOFF_PROG && speedReady;
            boolean mustJump = prog >= PARKOUR_FORCE_PROG; // 即将踩空，不管速度是否建够
            if (physics.canJump() && (atEdge || mustJump)) {
                parkourPhase = 1;
                input = input.withJump(true);
            }
            engine.setInput(input);
            return;
        }

        // 阶段 1：腾空，持续前进/冲刺/按跳
        input = input.withJump(true);
        parkourAirTicks++;
        double dDest = Math.hypot(targetX - px, targetZ - pz);
        boolean arrivedY = Math.abs(py - dest.y) < ARRIVE_Y_TOLERANCE;
        if (physics.onGround() && dDest < arriveRadius + 0.5 && arrivedY) {
            // 落在 dest：本步完成，前进
            parkourPhase = 0;
            index++;
            resetStuck();
            markProgress();
            engine.setInput(input);
            return;
        }
        if (parkourAirTicks > PARKOUR_MAX_AIR_TICKS || py < src.y - ARRIVE_Y_TOLERANCE) {
            // 跳跨失败（坠落/超长）：放弃本路径，异步重算（可能避开 parkour 或等待新块）
            parkourPhase = 0;
            path = null;
            resetStuck();
            if (!computing.get() && totalReplans < MAX_TOTAL_REPLANS) {
                requestRecompute(px, py, pz, true);
            }
            engine.setInput(PlayerInput.EMPTY);
            return;
        }
        engine.setInput(input);
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
                markProgress();
                continue;
            }
            // 越过：已在本层且离下一航点更近 → 跳过当前（快速移动/斜走时避免绕点）；
            // 但 parkour 航点必须实际起跳越过，不得被提前跳过
            if (arrivedY && !steps.get(index).move.isParkour() && index + 1 < steps.size()) {
                NodePos nxt = steps.get(index + 1).dest;
                double dNext = Math.hypot(nxt.x + 0.5 - px, nxt.z + 0.5 - pz);
                if (dNext < dCur) {
                    index++;
                    resetStuck();
                    markProgress();
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
    // 挖通例程（引擎线程；计划破块步、卡住应急挖通与直挖隧道共用）
    // ------------------------------------------------------------------

    /** {@link #digColumn} / {@link #bridgeColumn} 的单 tick 结果。 */
    private enum DigStatus { CLEARED, BUSY, FAILED }

    /**
     * 挖通 {@code (colX, colZ)} 列（脚格 + 头格）：用 {@link PathWorld#planDigTool} 在<b>整个背包</b>
     * 选出破坏耗时最短的工具；若该工具当前位于主背包，先经 {@link InventoryMoveGoal} 换到某个
     * 快捷栏槽，再手持并逐 tick 驱动 {@link BlockActionGoal} 挖掘，直到本格破坏。
     *
     * <p>安全性：{@code requiredTicks} 取规划最优工具的耗时估计并留正裕量——服务器按“实际手持
     * + 硬度”自主计时，按住时长只可能偏慢不可能偏快，故绝不触发 fastbreak。取工具若未及时生效，
     * 也只是“慢一点 → 到预算后放弃”，不会造成越界行为。</p>
     *
     * @return 本 tick 状态：{@link DigStatus#CLEARED} 该列已畅通可前进；{@link DigStatus#BUSY}
     *         取工具 / 挖掘进行中（调用方本 tick 不应前进）；{@link DigStatus#FAILED} 不可挖或超时
     */
    private DigStatus digColumn(MovementEngine engine, int colX, int footY, int colZ,
                                double px, double py, double pz) {
        // 找第一个仍被阻挡的格：脚格优先，其次头格
        int bx;
        int by;
        int bz;
        if (!world.isPassable(colX, footY, colZ)) {
            bx = colX;
            by = footY;
            bz = colZ;
        } else if (!world.isPassable(colX, footY + 1, colZ)) {
            bx = colX;
            by = footY + 1;
            bz = colZ;
        } else {
            // 列已畅通：撤下在途挖 / 取子目标，交回前进逻辑
            clearDig(engine);
            return DigStatus.CLEARED;
        }

        long key = blockKey(bx, by, bz);
        if (key != digTargetKey) {
            // 新目标格：规划全背包最优工具
            DigTool plan = world.planDigTool(bx, by, bz);
            if (plan == null || !plan.breakable()) {
                clearDig(engine);
                return DigStatus.FAILED;
            }
            digTargetKey = key;
            digTargetX = bx;
            digTargetY = by;
            digTargetZ = bz;
            digFace = faceTowardPlayer(bx, by, bz, px, py, pz);
            digRequiredTicks = (int) Math.ceil(plan.ticks()) + 2; // 正裕量：只可能偏慢，绝不 fastbreak
            digEquipSlot = plan.equipHotbarSlot();
            mineTicks = 0;
            activeMine = null;
            activeRelocate = null;
            if (plan.needsRelocate() && inventory != null) {
                log.info("[Running] 需用背包中的工具开路（主背包槽 {} → 快捷栏槽 {}），先取工具。",
                        plan.relocateFromSlot(), digEquipSlot);
                activeRelocate = InventoryMoveGoal.toHotbar(inventory, plan.relocateFromSlot(), digEquipSlot);
            } else {
                equipForDig();
                startDigGoal();
            }
        }

        // 阶段 A：取工具（主背包 → 快捷栏搬迁）
        if (activeRelocate != null) {
            activeRelocate.tick(engine);
            mineTicks++;
            if (activeRelocate.isFinished() || mineTicks >= RELOCATE_TIMEOUT_TICKS) {
                // 工具已就位（或搬迁超时：尽力而为，未搬到也继续，最坏是慢一点）
                activeRelocate = null;
                equipForDig();
                startDigGoal();
            }
            return DigStatus.BUSY;
        }

        // 阶段 B：逐 tick 挖掘
        if (activeMine == null) {
            equipForDig();
            startDigGoal();
        }
        activeMine.tick(engine);
        mineTicks++;
        if (activeMine.isFinished()) {
            // 本格挖完 / 放弃：撤下并令下一 tick 重新评估该列（可能轮到头格或已畅通）
            activeMine = null;
            digTargetKey = UNSET_KEY;
        }
        if (mineTicks > MAX_MINE_TICKS_PER_STEP) {
            clearDig(engine);
            return DigStatus.FAILED;
        }
        return DigStatus.BUSY;
    }

    /** 若需要且尚未手持，则切换到规划好的快捷栏槽。 */
    private void equipForDig() {
        if (digEquipSlot >= 0 && digEquipSlot != lastCarriedSlot) {
            sendCarried(digEquipSlot);
            lastCarriedSlot = digEquipSlot;
        }
    }

    /** 依据当前挖掘上下文创建一个 {@link BlockActionGoal} 挖块子目标。 */
    private void startDigGoal() {
        activeMine = BlockActionGoal.dig(
                Vector3i.from(digTargetX, digTargetY, digTargetZ), digFace, digRequiredTicks);
    }

    /** 撤下在途挖 / 取子目标并复位目标编码（不触碰寻路状态）。 */
    private void clearDig(MovementEngine engine) {
        if (activeMine != null) {
            activeMine.onFinish(engine, false);
            activeMine = null;
        }
        activeRelocate = null;
        digTargetKey = UNSET_KEY;
    }

    /** 放弃破块：撤销在途挖掘 / 取工具，清空路径并请求（卡住）重算。 */
    private void giveUpMining(MovementEngine engine, double px, double py, double pz) {
        clearDig(engine);
        path = null;
        resetStuck();
        if (!computing.get() && totalReplans < MAX_TOTAL_REPLANS) {
            requestRecompute(px, py, pz, true);
        }
    }

    // ------------------------------------------------------------------
    // 卡住应急挖通（引擎线程）
    // ------------------------------------------------------------------

    /**
     * 尝试进入应急挖通：以路径下一步 {@code dest} 所在列为目标，仅当该列确有“被可挖方块
     * 阻挡的格”时才进入，否则返回 {@code false}（交回常规重算 / 绕行）。
     */
    private boolean beginEmergencyDig(NodePos dest, double px, double py, double pz) {
        int colX = dest.x;
        int colZ = dest.z;
        int footY = floor(py);
        if (colX == floor(px) && colZ == floor(pz)) {
            return false; // 目标列即当前所在列，异常情形交给重算
        }
        boolean footBlocked = !world.isPassable(colX, footY, colZ);
        boolean headBlocked = !world.isPassable(colX, footY + 1, colZ);
        if (!footBlocked && !headBlocked) {
            return false; // 前方无阻挡：不是“被方块卡住”，交给重算
        }
        boolean canDigFoot = footBlocked && world.isBreakable(colX, footY, colZ);
        boolean canDigHead = headBlocked && world.isBreakable(colX, footY + 1, colZ);
        if (!canDigFoot && !canDigHead) {
            return false; // 阻挡不可挖（基岩 / 流体 / 未加载），挖不通
        }
        emergencyDig = true;
        emergencyColX = colX;
        emergencyFootY = footY;
        emergencyColZ = colZ;
        emergencyTicks = 0;
        digTargetKey = UNSET_KEY; // 强制按新列重新规划工具
        activeMine = null;
        activeRelocate = null;
        log.info("[Running] 卡住无法前进，尝试朝 ({}, {}, {}) 方向挖通开路。", colX, footY, colZ);
        return true;
    }

    /** 驱动一次应急挖通的 tick；打通或超时后结束并清空路径交回重算。 */
    private void tickEmergencyDig(MovementEngine engine, double px, double py, double pz) {
        DigStatus st = digColumn(engine, emergencyColX, emergencyFootY, emergencyColZ, px, py, pz);
        emergencyTicks++;
        if (st == DigStatus.BUSY && emergencyTicks <= EMERGENCY_MAX_TICKS) {
            engine.setInput(PlayerInput.EMPTY);
            return;
        }
        boolean cleared = st == DigStatus.CLEARED;
        endEmergency(engine);
        if (cleared) {
            log.info("[Running] 已挖开通路，继续寻路。");
        } else {
            log.info("[Running] 应急挖通未成功（不可挖或超时），交回寻路重算。");
        }
        path = null; // 世界已变，需重新规划
        resetStuck();
        if (!computing.get() && totalReplans < MAX_TOTAL_REPLANS) {
            requestRecompute(px, py, pz, true);
        }
        engine.setInput(PlayerInput.EMPTY);
    }

    /** 退出应急挖通模式（撤下在途子目标）。 */
    private void endEmergency(MovementEngine engine) {
        emergencyDig = false;
        emergencyTicks = 0;
        clearDig(engine);
    }

    /** 前进一格即视为有进展，清零卡住升级计数。 */
    private void markProgress() {
        stuckEscalations = 0;
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
