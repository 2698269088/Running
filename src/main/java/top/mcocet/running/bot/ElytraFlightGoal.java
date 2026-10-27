package top.mcocet.running.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.RunningConfig;
import top.mcocet.running.flight.ElytraDurability;
import top.mcocet.running.flight.FlightContext;
import top.mcocet.running.flight.FlightSelector;
import top.mcocet.running.flight.FlightWorldQuery;
import top.mcocet.running.world.InventoryState;
import top.mcocet.running.world.item.ItemRegistry;

/**
 * 鞘翅飞行目标（决策架构完整移植 MovementSync 1.11 的飞行子系统思路）：
 * 由 {@link MovementEngine} 以 20 TPS 驱动，每 tick 用 {@link FlightSelector}
 * 对候选航向/俯仰网格做纯物理弹道推演，按"伤害分级 + 目标推进代价"选出唯一
 * 提案后执行——取代旧版的"固定巡航角 + 速度阈值补弹"开环控制。
 *
 * <ol>
 *   <li>CHECK：胸槽鞘翅检查，必要时经 {@link InventoryMoveGoal} 自动装备
 *       （只以镜像生效为成功判据，等待窗口内轮询，修复过"发出≠生效"竞态）；
 *       耐久预算 {@link ElytraDurability#canStart} 门控起飞；</li>
 *   <li>LAUNCH：地面起跳，离地后调 {@link PlayerPhysics#requestAirborneGlide()}
 *       （MS 式起飞：物理层在 vy≤0 窗口即时置位起滑并发 START_ELYTRA_FLYING，
 *       无需第二次跳跃键边沿）；超时撤销请求并判失败；</li>
 *   <li>GLIDE：每 tick 组装 {@link FlightContext}（只读快照）→
 *       {@code decide} 选安全候选；无安全候选（全 UNKNOWN/LETHAL）→
 *       {@code forceLand} 损伤最小迫降；耐久/高度触发的 {@code shouldLand}
 *       或收到停止请求 → emergency 上下文（伤害优先排序）；动作请求点火时经
 *       {@link RocketBooster} 走"选弹→搬迁→USE_ITEM→服务器消耗确认→加速窗口"
 *       链路（确认后才对物理层施加，服务器拒绝不失真）；</li>
 *   <li>LAND：转入 emergency 决策直到滑翔标志自然熄灭（触地/入水，
 *       canGlide 本地同构裁定，无停飞包）。</li>
 * </ol>
 *
 * <p>血量说明：本插件没有自身血量镜像（服务器不下发自己的 health 元数据），
 * 上下文的致命阈值按满血 20 计——岩浆等 outcome 已单独判 LETHAL，
 * 该默认值仅影响"重伤是否算致命"的次要分支。</p>
 *
 * <p>线程：{@link #tick} 仅在引擎线程运行；{@link #requestStop()}/
 * {@link #requestBoost(int)}/{@link #setTarget} 供命令线程调用（volatile）。</p>
 */
public final class ElytraFlightGoal implements MovementGoal {
    private static final Logger log = LoggerFactory.getLogger(ElytraFlightGoal.class.getSimpleName());

    /** 鞘翅的 minecraft-data 物品名 */
    public static final String ELYTRA_ITEM_NAME = "elytra";

    /** LAUNCH 阶段总超时（tick）：含地面等待、腾空上升/下落窗口与烟花预置搬迁 */
    private static final int LAUNCH_TIMEOUT_TICKS = 80;
    /** 起飞阶段的抬头俯仰（度）：原版火箭推力严格沿视线，平地起滑仅有的 ≈20 tick
     *  窗口内必须先拉负俯仰，起滑瞬间选上仰弹道 + 预置烟花瞬时点火才能拉出地面 */
    private static final float LAUNCH_PITCH = -55.0F;
    /** GLIDE 阶段巡航总超时（tick，约 16 分钟），超时转入 LAND */
    private static final int GLIDE_MAX_TICKS = 20000;
    /** LAND 阶段等待自然着陆的超时（tick） */
    private static final int LAND_MAX_TICKS = 1200;
    /** 两次烟花发射的最小间隔（tick；对齐 MS 火箭寿命上界 40，同刻只挂一枚） */
    private static final int BOOST_COOLDOWN_TICKS = 40;
    /** 每 tick 最大转向角（度），yaw/pitch 同限速（防候选抖动与反作弊视角突变敏感） */
    private static final float MAX_LOOK_STEP_DEG = 30.0F;
    /** 装备搬迁预算（tick）：多次点击串行链 + 末步后等待服务器回包刷新镜像。 */
    private static final int RELOCATE_BUDGET = 120;
    /** 换翅确认预算（tick）：拾/放两次点击 + 新翅 damage 组件回包生效 */
    private static final int REPLACE_BUDGET = 120;
    /** 换翅失败后的重试间隔（tick，防无备件时每 tick 重建点击链） */
    private static final int REPLACE_RETRY_COOLDOWN = 200;
    /** 地面高度缓存的重扫间隔（tick，供耐久 shouldLand 判定） */
    private static final int GROUND_SCAN_INTERVAL_TICKS = 20;
    /** 自由巡航（无真实目标）时的前方虚拟锚点距离（格） */
    private static final double CRUISE_LOOKAHEAD = 64.0;

    // 阶段
    private static final int PHASE_CHECK = 0;
    private static final int PHASE_LAUNCH_GROUND = 1;
    private static final int PHASE_LAUNCH_AIR = 2;
    private static final int PHASE_GLIDE = 3;
    private static final int PHASE_LAND = 4;

    private final InventoryState inventory;
    private final ItemRegistry registry;
    private final RunningConfig config;
    /** 弹道推演用的世界查询（BlockWorldQuery 适配，引擎线程只读） */
    private final FlightWorldQuery query;
    /** 机器人状态镜像（读创造模式标志：创造下服务器不扣耐久，预算门控自动旁路） */
    private final BotState state;

    private final FlightSelector selector = new FlightSelector();
    private RocketBooster booster;
    private InventoryMoveGoal equipGoal;
    /** 耐久将尽时的自动换翅子目标（null=无在途换翅） */
    private InventoryMoveGoal replaceGoal;
    private int replaceTicks;
    private int replaceRetryCooldown;

    // 命令线程写入
    private volatile boolean stopRequested;
    private volatile int pendingBoosts;
    /** 飞行目标点（NaN=自由巡航，按当前朝向维持高度） */
    private volatile double targetX = Double.NaN;
    private volatile double targetY = Double.NaN;
    private volatile double targetZ = Double.NaN;
    /** 巡航锁定航向（度；NaN=追目标方位或当前朝向） */
    private volatile float headingYaw = Float.NaN;

    // 引擎线程私有
    private int phase = PHASE_CHECK;
    private int phaseTicks;
    private int totalGlideTicks;
    private int ticksSinceBoost;
    /** 巡航期望高度锚点（起滑时刻记录；fly to 显式给 y 时改用目标高度）：
     *  决策器高度价值项的基准——若每 tick 重算为当前高度，高度永远"达标"，
     *  上仰爬坡候选永不胜出（表现为"烟花激活但不升高度"） */
    private double cruiseRefY = Double.NaN;
    private double cachedGroundY = Double.NaN;
    private boolean finished;

    public ElytraFlightGoal(InventoryState inventory, ItemRegistry registry,
                            RunningConfig config, FlightWorldQuery query, BotState state) {
        this.inventory = inventory;
        this.registry = registry;
        this.config = config;
        this.query = query;
        this.state = state;
        this.booster = new RocketBooster(inventory, registry, config.flyRocketDuration());
    }

    // ------------------------------------------------------------------
    // 命令层 API
    // ------------------------------------------------------------------

    /** 指定飞行目标（世界坐标；y 传 NaN 表示自动取目标列台面高度附近）。 */
    public void setTarget(double x, double y, double z) {
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
    }

    /** 锁定巡航航向（度）；仅在未给出目标点时参与决策。 */
    public void setHeading(float yaw) {
        this.headingYaw = yaw;
    }

    /** 请求结束飞行：转入损伤优先的着陆决策，等待自然触地/入水。 */
    public void requestStop() {
        stopRequested = true;
    }

    /** 请求立即发射 n 发烟花加速（滑翔中生效；与自动补弹共享冷却）。 */
    public void requestBoost(int n) {
        if (n > 0) {
            pendingBoosts = Math.min(16, pendingBoosts + n);
        }
    }

    // ------------------------------------------------------------------
    // 引擎 tick
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
        phaseTicks++;
        ticksSinceBoost++;

        switch (phase) {
            case PHASE_CHECK -> tickCheck(engine, physics);
            case PHASE_LAUNCH_GROUND -> tickLaunchGround(engine, physics);
            case PHASE_LAUNCH_AIR -> tickLaunchAir(engine, physics);
            case PHASE_GLIDE -> tickFly(engine, physics, false);
            case PHASE_LAND -> tickFly(engine, physics, true);
            default -> finished = true;
        }
    }

    // ---- 阶段 0：鞘翅检查 / 自动装备 / 耐久门控 ----

    private void tickCheck(MovementEngine engine, PlayerPhysics physics) {
        if (isElytraEquipped()) {
            if (beginLaunch(engine, physics)) {
                return;
            }
            return;
        }
        if (!config.flyAutoEquip()) {
            log.info("[Running] 胸甲槽没有鞘翅（配置 fly.auto-equip=false，请手动穿戴后重试）。");
            finished = true;
            return;
        }
        if (equipGoal == null) {
            int elytraSlot = inventory.findSlotHoldingBest(registry, ELYTRA_ITEM_NAME);
            if (elytraSlot < 0 || elytraSlot >= InventoryState.ARMOR_FIRST) {
                log.info("[Running] 背包里没有鞘翅（盔甲槽不可作为来源），无法飞行。");
                finished = true;
                return;
            }
            boolean chestHasItem = inventory.hasItem(InventoryState.ARMOR_CHEST);
            log.info("[Running] 胸甲槽没有鞘翅，自动把槽 {} 的鞘翅装上去。", elytraSlot);
            equipGoal = InventoryMoveGoal.move(inventory, elytraSlot,
                    InventoryState.ARMOR_CHEST, false, chestHasItem);
            equipGoal.onStart(engine);
        }
        equipGoal.tick(engine);
        engine.setInput(PlayerInput.EMPTY);
        // 服务器回包异步生效：只以镜像中的鞘翅就位为成功判据，窗口内任意 tick 生效即成功
        if (isElytraEquipped()) {
            equipGoal.onFinish(engine, false);
            equipGoal = null;
            log.info("[Running] 鞘翅已自动装备到胸槽，继续起滑。");
            beginLaunch(engine, physics);
        } else if (phaseTicks >= RELOCATE_BUDGET) {
            equipGoal.onFinish(engine, false);
            equipGoal = null;
            log.info("[Running] 自动装备鞘翅未生效（点击超时/被拒），请手动穿戴后重试。");
            finished = true;
        }
    }

    /** 耐久预算门控是否旁路：创造模式服务器本就不扣耐久；强制飞行（fly force）生存下也无视预算。 */
    private boolean bypassBudget(PlayerPhysics physics) {
        return state.isCreative() || physics.isForceFlight();
    }

    /** 耐久预算门控后进入起跳阶段；预算不足判失败。返回是否继续。 */
    private boolean beginLaunch(MovementEngine engine, PlayerPhysics physics) {
        ElytraDurability durability = currentDurability();
        double distance = Double.isNaN(targetX) ? 0.0
                : Math.hypot(targetX - physics.x(), targetZ - physics.z());
        if (!bypassBudget(physics) && !durability.canStart(distance)) {
            log.info("[Running] 鞘翅耐久预算不足以飞行 {} 格（剩余 {} 点），放弃起飞。",
                    String.format("%.0f", distance), durability.remainingDurability());
            finished = true;
            return false;
        }
        phase = PHASE_LAUNCH_GROUND;
        phaseTicks = 0;
        return true;
    }

    // ---- 阶段 1~2：地面起跳 → 空中程序化起滑 ----

    private void tickLaunchGround(MovementEngine engine, PlayerPhysics physics) {
        // 起飞即抬头（原版玩家平地起飞：视角朝上→跳→开翅瞬间点烟花），同时预置
        // 一发烟花：选弹/背包搬迁在起跳前的地面等待与腾空窗口内完成，不推进到发射
        engine.look(physics.yaw(), LAUNCH_PITCH);
        booster.prepare();
        booster.tickPrepare(engine);
        if (physics.isFallFlying()) {
            enterGlide(physics);
            firePreparedRocket(engine);
            return;
        }
        if (launchTimeout()) {
            return;
        }
        if (physics.canJump()) {
            engine.setInput(new PlayerInput(false, false, false, false, true, false, false));
            // MS 式起飞：请求一次程序化起滑，物理层在"离地且 vy≤0"窗口自动置位
            physics.requestAirborneGlide();
            phase = PHASE_LAUNCH_AIR;
            phaseTicks = 0;
            return;
        }
        engine.setInput(PlayerInput.EMPTY);
    }

    private void tickLaunchAir(MovementEngine engine, PlayerPhysics physics) {
        engine.look(physics.yaw(), LAUNCH_PITCH);
        booster.tickPrepare(engine);
        if (physics.isFallFlying()) {
            enterGlide(physics);
            // 检测到起滑的本 tick 立即发令：START 包已于上一 tick 发出，本 tick 的
            // driveGoal 阶段是其后最早的合法 USE_ITEM 时刻——即玩家手感的
            // "打开鞘翅瞬间激活烟花"，平地窗口内靠上仰视线的火箭冲量把 vy 拉正
            firePreparedRocket(engine);
            return;
        }
        if (launchTimeout()) {
            physics.cancelGlideRequest();
            return;
        }
        // 离地等待窗口：保持空输入，让请求闭锁在 vy≤0 的 tick 被物理层消费
        engine.setInput(PlayerInput.EMPTY);
    }

    /** 若预置已就绪，转入发射并立即推进一 tick（本 tick 发出 USE_ITEM）。 */
    private void firePreparedRocket(MovementEngine engine) {
        if (booster.prepared() && booster.beginPrepared()) {
            booster.tick(engine);
            ticksSinceBoost = 0;
        }
    }

    private boolean launchTimeout() {
        if (phaseTicks > LAUNCH_TIMEOUT_TICKS) {
            log.info("[Running] 起滑超时（可能已在空中/水中，或胸槽实际无鞘翅被服务器纠正）。");
            finished = true;
            return true;
        }
        return false;
    }

    private void enterGlide(PlayerPhysics physics) {
        phase = PHASE_GLIDE;
        phaseTicks = 0;
        totalGlideTicks = 0;
        ticksSinceBoost = BOOST_COOLDOWN_TICKS; // 允许进入后立刻按需要点火
        // 锚定巡航期望高度：显式 y 目标 > 起滑时刻高度；不锁定则高度参考永远
        // 跟随当前值漂移，决策器无爬升动机
        cruiseRefY = !Double.isNaN(targetY) ? targetY : physics.y();
        cachedGroundY = Double.NaN;
        if (Double.isNaN(targetX)) {
            log.info("[Running] 已起滑：候选网格决策巡航（fly to <x> <z> [y] 指定目标，fly stop 着陆）。");
        } else {
            log.info("[Running] 已起滑：飞向 (x={}, z={})，y={}。", targetX, targetZ,
                    Double.isNaN(targetY) ? "自动（保持当前高度）" : String.format("%.0f（锁定）", targetY));
        }
    }

    // ---- 阶段 3~4：selector 驱动的巡航 / 着陆 ----

    private void tickFly(MovementEngine engine, PlayerPhysics physics, boolean landing) {
        engine.setInput(PlayerInput.EMPTY);
        totalGlideTicks++;

        // 自然着陆（触地/入水/服务器纠正，canGlide 同构裁定）：滑翔标志熄灭即结束
        if (!physics.isFallFlying()) {
            log.info(landing ? "[Running] 已着陆，飞行结束。"
                    : "[Running] 滑翔已结束（着地/入水/服务器纠正）。");
            finished = true;
            return;
        }
        int limit = landing ? LAND_MAX_TICKS : GLIDE_MAX_TICKS;
        if (totalGlideTicks > limit) {
            log.info("[Running] {} 超时，退出飞行控制（滑翔状态由物理层自行延续）。",
                    landing ? "着陆等待" : "巡航");
            finished = true;
            return;
        }
        if (!landing && stopRequested) {
            phase = PHASE_LAND;
            phaseTicks = 0;
            totalGlideTicks = 0;
            log.info("[Running] 收到停止指令，转入损伤优先着陆决策。");
        }

        // 耐久将尽自动换翅（滑翔不中断）：本 tick 若在换翅点击链中，仅推进它并跳过决策
        if (tickElytraReplace(engine, physics)) {
            return;
        }

        // 推进在途补弹点击链（服务器异步确认）；预置就绪但起滑 tick 未及发射的
        // 补漏 → 补发令立即发射。手动 boost 请求直接点火，不等决策器判势
        if (booster.isBusy()) {
            if (booster.prepared() && booster.beginPrepared()) {
                ticksSinceBoost = 0;
            }
            booster.tick(engine);
        }
        while (pendingBoosts > 0 && !booster.isBusy()
                && physics.rocketBoostRemaining() <= 0
                && ticksSinceBoost >= BOOST_COOLDOWN_TICKS) {
            pendingBoosts--;
            if (booster.begin()) {
                booster.tick(engine);
                ticksSinceBoost = 0;
            } else {
                break; // 无烟花可用：丢弃剩余请求
            }
        }

        FlightContext ctx = buildContext(engine, physics, landing);
        FlightSelector.Decision decision = selector.decide(ctx);
        if (decision == null) {
            // 无安全候选：迫降（伤害最小），仍全未知则抬头保高
            decision = selector.forceLand(ctx);
        }
        applyDecision(engine, physics, ctx, decision);
    }

    /** 组装本 tick 的只读决策上下文（目标缺省为"当前朝向前方 + 锁定巡航高度"）。 */
    private FlightContext buildContext(MovementEngine engine, PlayerPhysics physics, boolean emergency) {
        // 期望高度：fly to 显式 y 优先，否则起滑时锚定的巡航参考高度（不随当前高度漂移）
        double desiredY = !Double.isNaN(targetY) ? targetY
                : !Double.isNaN(cruiseRefY) ? cruiseRefY : physics.y();
        double tx;
        double ty;
        double tz;
        if (!Double.isNaN(targetX)) {
            tx = targetX;
            ty = desiredY;
            tz = targetZ;
        } else {
            // 自由巡航：锁定航向（或当前 yaw）前方 64 格，维持起滑时记录的参考高度
            float yaw = Float.isNaN(headingYaw) ? physics.yaw() : headingYaw;
            tx = physics.x() - Math.sin(Math.toRadians(yaw)) * CRUISE_LOOKAHEAD;
            tz = physics.z() + Math.cos(Math.toRadians(yaw)) * CRUISE_LOOKAHEAD;
            ty = desiredY;
        }
        refreshGroundCache(physics);
        // 耐久/高度触发的强制着陆意图并入 emergency（伤害优先排序）；
        // 创造/强制飞行旁路（bypassBudget）
        ElytraDurability durability = currentDurability();
        double heightAboveGround = Double.isNaN(cachedGroundY) ? 0.0 : physics.y() - cachedGroundY;
        boolean durabilityPressure = !bypassBudget(physics)
                && durability.shouldLand(heightAboveGround);
        int rocketsHeld = inventory.countItem(registry, RocketBooster.ROCKET_ITEM_NAME);
        boolean rocketReady = !booster.isBusy()
                && physics.rocketBoostRemaining() <= 0
                && ticksSinceBoost >= BOOST_COOLDOWN_TICKS
                && rocketsHeld > 0;
        return new FlightContext(query,
                physics.x(), physics.y(), physics.z(),
                physics.deltaX(), physics.deltaY(), physics.deltaZ(),
                physics.fallDistance(),
                physics.yaw(), physics.pitch(),
                tx, ty, tz,
                config.arriveRadius(),
                rocketReady ? rocketsHeld : 0,
                physics.rocketBoostRemaining() > 0,
                20.0, // 无自身血量镜像：按满血阈值（岩浆等 outcome 已单独判致命）
                emergency || durabilityPressure,
                query.minY());
    }

    /** 执行选出的提案：限速转向 + 按需经 RocketBooster 点火。 */
    private void applyDecision(MovementEngine engine, PlayerPhysics physics,
                               FlightContext ctx, FlightSelector.Decision decision) {
        float yaw = stepToward(physics.yaw(), decision.action().yaw());
        float pitch = stepToward(physics.pitch(), decision.action().pitch());
        engine.look(yaw, pitch);
        if (decision.action().firework() && !booster.isBusy()
                && physics.rocketBoostRemaining() <= 0
                && ticksSinceBoost >= BOOST_COOLDOWN_TICKS) {
            if (booster.begin()) {
                booster.tick(engine);
                ticksSinceBoost = 0;
            }
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 定期重扫正下方地面高度（供耐久 shouldLand；64 格内未找到则保守取当前高度）。 */
    private void refreshGroundCache(PlayerPhysics physics) {
        if (phaseTicks % GROUND_SCAN_INTERVAL_TICKS != 0 && !Double.isNaN(cachedGroundY)) {
            return;
        }
        int bx = (int) Math.floor(physics.x());
        int bz = (int) Math.floor(physics.z());
        double ground = Double.NaN;
        for (int y = (int) Math.floor(physics.y()); y > query.minY(); y--) {
            if (query.isStandable(bx, y, bz)) {
                ground = y + 1.0;
                break;
            }
            if ((int) Math.floor(physics.y()) - y > 64) {
                break;
            }
        }
        cachedGroundY = Double.isNaN(ground) ? physics.y() : ground;
    }

    private ElytraDurability currentDurability() {
        return ElytraDurability.fromElytraStack(inventory.stackAt(InventoryState.ARMOR_CHEST));
    }

    /**
     * 耐久将尽自动换翅（{@code fly.auto-replace}，对齐 MS {@code prepareElytra} 的
     * 装备维护思想）：服务器每 20 个滑翔 tick 扣 1 点耐久并回推 damage 组件，
     * 镜像里剩余 ≤1 点（usable=false）时用背包里另一副鞘翅与胸槽交换——
     * 滑翔不中断（服务器 canGlide 逐 tick 重评，新翅接上即连续）。
     *
     * <p>成功判据与点击链竞态同装备链：只以"新翅 damage 回包后 usable"为准；
     * 超预算未生效则记一次失败并进入重试冷却（无备件直接判不可换）。创造模式
     * 与强制飞行（{@link #bypassBudget}）不触发：前者服务器永不扣耐久，后者语义是
     * 无视耐久飞到停为止。若换翅失败且未强制飞行，结束目标交由滑翔自然衰减落地的
     * canGlide 裁定。</p>
     *
     * @return true = 本 tick 被换翅流程占用（跳过常规决策，输入保持空、弹道自由推进）
     */
    private boolean tickElytraReplace(MovementEngine engine, PlayerPhysics physics) {
        if (bypassBudget(physics) || !physics.isAutoReplaceElytra()) {
            return false;
        }
        if (currentDurability().usable()) {
            replaceRetryCooldown = 0;
            return false; // 耐久充足，无需换翅
        }
        if (replaceGoal == null) {
            if (replaceRetryCooldown > 0) {
                replaceRetryCooldown--;
                return false;
            }
            int spare = inventory.findSlotHoldingBest(registry, ELYTRA_ITEM_NAME);
            if (spare < 0 || spare >= InventoryState.ARMOR_FIRST) {
                if (replaceRetryCooldown == 0) {
                    log.info("[Running] 鞘翅耐久已尽，背包里没有备件可换（{}）。",
                            physics.isForceFlight() ? "强制飞行：继续滑翔直到服务器停滑" : "fly stop 或手动更换");
                }
                replaceRetryCooldown = REPLACE_RETRY_COOLDOWN;
                if (!physics.isForceFlight()) {
                    finished = true; // 退出目标；滑翔姿态由物理层维持至自然落地
                }
                return false;
            }
            log.info("[Running] 鞘翅耐久将尽，自动把槽 {} 的备件换上（滑翔不中断）。", spare);
            replaceGoal = InventoryMoveGoal.move(inventory, spare,
                    InventoryState.ARMOR_CHEST, false, true);
            replaceGoal.onStart(engine);
            replaceTicks = 0;
            return true;
        }
        replaceGoal.tick(engine);
        if (currentDurability().usable()) {
            replaceGoal.onFinish(engine, false);
            replaceGoal = null;
            replaceRetryCooldown = 0;
            log.info("[Running] 鞘翅已自动更换（剩余耐久 {} 点），继续飞行。",
                    currentDurability().remainingDurability());
            return false;
        }
        if (++replaceTicks >= REPLACE_BUDGET) {
            replaceGoal.onFinish(engine, false);
            replaceGoal = null;
            replaceRetryCooldown = REPLACE_RETRY_COOLDOWN;
            log.info("[Running] 自动换翅点击未在预算内生效，稍后重试（{}）。",
                    physics.isForceFlight() ? "强制飞行继续" : "耐久耗尽将自然落地");
            if (!physics.isForceFlight()) {
                finished = true;
                return false;
            }
            return false;
        }
        return true;
    }

    private boolean isElytraEquipped() {
        return inventory.isEquipped(registry, ELYTRA_ITEM_NAME);
    }

    /** 角度逼近（跨 ±180° 取最短弧），每 tick 至多转 {@link #MAX_LOOK_STEP_DEG}°。 */
    private static float stepToward(float current, float target) {
        float delta = wrapDegrees(target - current);
        if (Math.abs(delta) <= MAX_LOOK_STEP_DEG) {
            return target;
        }
        return current + Math.copySign(MAX_LOOK_STEP_DEG, delta);
    }

    private static float wrapDegrees(float degrees) {
        float value = degrees % 360.0F;
        if (value >= 180.0F) {
            value -= 360.0F;
        }
        if (value < -180.0F) {
            value += 360.0F;
        }
        return value;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public void onFinish(MovementEngine engine, boolean completed) {
        finished = true;
        if (equipGoal != null && engine != null) {
            equipGoal.onFinish(engine, false);
            equipGoal = null;
        }
        if (replaceGoal != null && engine != null) {
            replaceGoal.onFinish(engine, false);
            replaceGoal = null;
        }
        booster.cancel();
        if (engine != null) {
            engine.physics().cancelGlideRequest();
        }
    }
}
