package top.mcocet.running.bot;

import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.Fireworks;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.world.InventoryState;
import top.mcocet.running.world.item.ItemRegistry;
import xin.bbtt.mcbot.Bot;

/**
 * 烟花加速执行器：由滑翔目标（{@link ElytraFlightGoal} 与 {@code PathingGoal} 的滑翔阶段）
 * 在引擎 tick 内驱动，完成"从背包选弹 → 必要时搬迁到快捷栏 → 手持 → 发送
 * {@code USE_ITEM} → 同刻预测应用加速窗口 → 消耗观测（仅诊断）"的完整链路。
 *
 * <ul>
 *   <li>{@code FireworkRocketItem.use}：仅当玩家 {@code isFallFlying()} 时受理；
 *       本地在发出 {@code USE_ITEM} 的同刻就应用加速窗口（官方客户端同为预测语义，
 *       从不"等扣物品才加速"）——旧版等消耗回包再施加会在无头客户端下因
 *       回包槽位与监视槽偏差而永远误判未确认、导致永不加速；服务器拒绝时
 *       位置由服务器回包纠偏；</li>
 *   <li>{@code FireworkRocketEntity} 寿命：{@code 10 × (1 + flight_duration)}，
 *       加速窗口按此施加（随机尾差不计，保守）；{@code flight_duration} 从物品的
 *       {@code minecraft:fireworks} 数据组件解析，缺失/无法解析时回退配置的默认时长；</li>
 *   <li>{@code ServerboundUseItemPacket} 携带当前视角 yaw/pitch（与官方一致），
 *       发送前留 2 tick 预热，确保服务器已收到指向当前视线的位置包。</li>
 * </ul>
 *
 * <p>火箭选择：扫描主物品区（0..35）与副手（40）中名称为 {@code firework_rocket}
 * 的堆，数量多者优先；位于快捷栏（0..8）则直接手持（或副手直接 {@code OFF_HAND}
 * 使用，无需搬迁），位于主背包则经 {@link InventoryMoveGoal} 与当前手持槽交换。
 * 搬迁超时/未生效则本次判失败（下 tick 由调用方按节流重试）。</p>
 *
 * <p>线程约定：{@link #begin()} 与 {@link #tick} 仅在移动引擎线程调用；
 * 背包镜像由网络线程写入，此处只读。</p>
 */
public final class RocketBooster {
    private static final Logger log = LoggerFactory.getLogger(RocketBooster.class.getSimpleName());

    /** 烟花火箭的 minecraft-data 物品名 */
    public static final String ROCKET_ITEM_NAME = "firework_rocket";

    /** 选弹后、发 USE_ITEM 前的转向预热 tick（保证视线位置包先行抵达服务器） */
    private static final int AIM_WARMUP_TICKS = 2;
    /** 背包搬迁等待上限 tick（含点击链发送 + 服务器回包刷新镜像）；超时判本次失败 */
    private static final int RELOCATE_TIMEOUT_TICKS = 40;
    /** 发出 USE_ITEM 后等待服务器消耗确认的最大 tick；超时判失败（服务器多半未受理） */
    private static final int CONFIRM_TIMEOUT_TICKS = 20;

    /** 执行状态 */
    public enum Status {
        /** 空闲（可 begin） */
        IDLE,
        /** 进行中（选弹/搬迁/预热/消耗观测） */
        BUSY,
        /** 本 tick 完成消耗观测：服务器确实扣了物品（加速窗口已在发射时应用） */
        BOOSTED,
        /** 本次失败（无烟花/搬迁超时/未观察到消耗回包），已回落 IDLE；
         *  注意：发射后窗口已应用，FAILED 不撤销加速，仅影响诊断 */
        FAILED
    }

    // 阶段
    private static final int PHASE_IDLE = 0;
    private static final int PHASE_RELOCATE = 1;
    private static final int PHASE_WARMUP = 2;
    private static final int PHASE_AIM = 3;
    private static final int PHASE_CONFIRM = 4;
    /** 预置阶段：只做选弹/搬迁不推进到发射（起飞窗口用，见 {@link #prepare()}） */
    private static final int PHASE_PREPARE = 5;
    /** 预置完成：烟花已就位、等待 {@link #beginPrepared()} 发射 */
    private static final int PHASE_PREPARED = 6;

    private final InventoryState inventory;
    private final ItemRegistry registry;
    /** 无法解析组件时的火箭飞行时长（配置 fly.rocket-flight-duration，1~3） */
    private final int fallbackFlightDuration;

    // 引擎线程私有
    private int phase = PHASE_IDLE;
    private int tickCount;
    private int rocketSlot = -1;      // 实际取用火箭的槽（快捷栏槽或副手 40）
    private int sourceSlot = -1;       // 选弹命中的原始槽
    private int amountBefore;          // 发射前 rocketSlot 的数量（消耗确认比对）
    private int totalBefore;           // 发射前全背包烟花总数（抗槽位错配的消耗确认主判据）
    private int boostWindowTicks;      // 确认后施加的加速窗口
    private InventoryMoveGoal relocate;
    private boolean pendingFailedLogged;

    public RocketBooster(InventoryState inventory, ItemRegistry registry, int fallbackFlightDuration) {
        this.inventory = inventory;
        this.registry = registry;
        this.fallbackFlightDuration = Math.min(3, Math.max(1, fallbackFlightDuration));
    }

    /** 是否正在进行一次发射流程（引擎 tick 内读取）。 */
    public boolean isBusy() {
        return phase != PHASE_IDLE;
    }

    /**
     * 开始一次发射流程（引擎线程、滑翔中调用）。
     *
     * @return 启动成功（进入 BUSY）；已在进行中或找不到烟花返回 {@code false}
     */
    public boolean begin() {
        return start(false);
    }

    /**
     * 预置一发烟花（起跳前的准备阶段调用，移植 MS {@code prepareElytra} 思想）：
     * 完成选弹与背包搬迁但停在 {@link #PHASE_PREPARED}，不推进到 USE_ITEM——
     * 服务器对非滑翔状态的 {@code use} 会直接 PASS 拒绝，提前发送会白白烧掉
     * 一次确认窗口；预置后腾空检测到起滑的 tick 即可 {@link #beginPrepared()}
     * 瞬时发射，抓住平地起滑仅有的 ≈20 tick 窗口。
     *
     * @return 启动成功（进行中或已就绪）；已在进行中或无烟花返回 {@code false}
     */
    public boolean prepare() {
        return start(true);
    }

    private boolean start(boolean pauseAfterReady) {
        if (phase != PHASE_IDLE) {
            return false;
        }
        // 粘用当前已装填堆：上一发装载的槽里还有烟花就继续消耗它，打空才重新
        // 选弹——修复多堆弹药下每发都重新选"最大堆"并与快捷栏做 MOVE_TO_HOTBAR
        // 交换的缺陷（每发一条点击链，既浪费服务器往返又刷日志）
        int slot = isRocketAt(rocketSlot) ? rocketSlot
                : inventory.findSlotHoldingBest(registry, ROCKET_ITEM_NAME);
        if (slot < 0) {
            if (!pendingFailedLogged) {
                log.info("[Running] 背包中没有烟花火箭，无法加速。");
                pendingFailedLogged = true;
            }
            return false;
        }
        sourceSlot = slot;
        // 加速窗口：按火箭组件的 flight_duration 估算（发射时刻的栈快照）
        boostWindowTicks = 10 * (1 + resolveFlightDuration(slot));
        if (slot >= InventoryState.HOTBAR_SIZE && slot < InventoryState.MAIN_SIZE) {
            // 主背包：与当前手持槽交换（手持物被挤进主背包，对齐取工具搬迁链）
            rocketSlot = inventory.heldSlot();
            relocate = InventoryMoveGoal.toHotbar(inventory, slot, rocketSlot);
            phase = pauseAfterReady ? PHASE_PREPARE : PHASE_RELOCATE;
        } else if (slot == InventoryState.OFFHAND_SLOT) {
            // 副手火箭：直接 OFF_HAND 使用，无需搬迁与换持
            rocketSlot = slot;
            phase = pauseAfterReady ? PHASE_PREPARED : PHASE_WARMUP;
        } else {
            rocketSlot = slot;
            phase = pauseAfterReady ? PHASE_PREPARED : PHASE_WARMUP;
        }
        tickCount = 0;
        pendingFailedLogged = false;
        return true;
    }

    /** 预置是否已就绪（烟花已在上手槽，{@link #beginPrepared()} 可瞬时发射）。 */
    public boolean prepared() {
        return phase == PHASE_PREPARED;
    }

    /** 预置阶段推进（仅搬迁轮询；滑翔检测前在 LAUNCH 阶段逐 tick 调用）。 */
    public void tickPrepare(MovementEngine engine) {
        if (phase == PHASE_PREPARE) {
            relocateTick(engine, true);
        }
    }

    /**
     * 从预置就绪直接进入发射阶段（跳过预热；下一个 {@link #tick} 即发
     * USE_ITEM）。仅在 {@link #prepared()} 时有效。
     */
    public boolean beginPrepared() {
        if (phase != PHASE_PREPARED) {
            return false;
        }
        phase = PHASE_AIM;
        tickCount = 0;
        return true;
    }

    /**
     * 推进一个引擎 tick：搬迁 → 预热 → 发射（换持 + USE_ITEM + SWING，同刻应用
     * 加速窗口）→ 消耗观测（仅日志）。
     *
     * @return 本 tick 后的状态；{@link Status#BOOSTED}/{@link Status#FAILED} 表示流程
     *         结束（自动回落 IDLE），FAILED 不撤销已应用的加速
     */
    public Status tick(MovementEngine engine) {
        switch (phase) {
            case PHASE_RELOCATE: {
                return relocateTick(engine, false);
            }
            case PHASE_PREPARED:
                // 预置完成但未下发射令：保持等待（调用方下次 tick 前会 beginPrepared）
                return Status.BUSY;
            case PHASE_WARMUP: {
                // 预热：让引擎先同步若干帧当前视线（位置包里的 yaw/pitch 随包发出）
                tickCount++;
                if (tickCount >= AIM_WARMUP_TICKS) {
                    phase = PHASE_AIM;
                }
                return Status.BUSY;
            }
            case PHASE_AIM: {
                // 发出使用包：手持切换（快捷栏火箭）+ USE_ITEM（带当前视角）+ SWING
                ClientSession session = Bot.INSTANCE.getSession();
                if (session == null || !session.isConnected()) {
                    phase = PHASE_IDLE;
                    return Status.FAILED;
                }
                Hand hand;
                if (rocketSlot == InventoryState.OFFHAND_SLOT) {
                    hand = Hand.OFF_HAND;
                } else {
                    hand = Hand.MAIN_HAND;
                    if (inventory.heldSlot() != rocketSlot) {
                        session.send(new ServerboundSetCarriedItemPacket(rocketSlot));
                    }
                }
                PlayerPhysics physics = engine.physics();
                session.send(new ServerboundUseItemPacket(hand, nextSequence(),
                        physics.yaw(), physics.pitch()));
                session.send(new ServerboundSwingPacket(hand));
                amountBefore = inventory.amountAt(rocketSlot);
                totalBefore = inventory.countItem(registry, ROCKET_ITEM_NAME);
                // 官方客户端语义：发出 use 的同刻就预测应用火箭加速窗口（原版从不
                // "等服务器扣物品才加速"；服务器仅在滑翔状态受理 use，拒绝时位置由
                // 服务器回包纠偏）——旧版等消耗回包再施加，在无头客户端下会因
                // 回包槽位与监视槽偏差而永远误判"未确认"，导致永不加速。
                // 常规发射不记日志（巡航补弹频繁）：仅错误（无弹药/搬迁失败）输出。
                physics.applyRocketBoost(boostWindowTicks);
                if (engine.isCreative()) {
                    // 创造模式：原版 ItemStack.consume 为 no-op，服务器受理后不扣
                    // 手持物品也不回推槽位——消耗观测必然落空（服务器侧火箭照常
                    // 生成并加速），故发射即完成，不进入观测阶段
                    phase = PHASE_IDLE;
                    return Status.BOOSTED;
                }
                phase = PHASE_CONFIRM;
                tickCount = 0;
                return Status.BUSY;
            }
            case PHASE_CONFIRM: {
                // 消耗观测：主判据是"全背包烟花总数减少"——服务器实际扣除的槽
                // 可能不是我们监视的 rocketSlot（服务器端 held 槽与镜像错位、
                // 回包落在其它槽等），总数比对对这些错配免疫；目标槽减少作快速
                // 路径保留。两判据都未动 = 回包确实没进入镜像，输出精确定位日志
                tickCount++;
                int totalNow = inventory.countItem(registry, ROCKET_ITEM_NAME);
                if (totalNow < totalBefore || inventory.amountAt(rocketSlot) < amountBefore) {
                    phase = PHASE_IDLE;
                    return Status.BOOSTED;
                }
                if (tickCount >= CONFIRM_TIMEOUT_TICKS) {
                    log.info("[Running] 未观察到烟花消耗回包：发射槽 {} 镜像仍为 {}，全背包烟花总数仍为 {}，"
                                    + "手持镜像槽 {}；最近一次槽位回包触达：{}。"
                                    + "（触达包晚于发射且槽/数对得上→镜像更新链路问题；"
                                    + "无任何回包→服务器消耗未回推，请查服务器端同步）",
                            rocketSlot, inventory.amountAt(rocketSlot), totalNow, inventory.heldSlot(),
                            inventory.lastSlotTouch());
                    phase = PHASE_IDLE;
                    return Status.FAILED;
                }
                return Status.BUSY;
            }
            default:
                return Status.IDLE;
        }
    }

    /** 撤销进行中的流程（目标结束时调用；不动服务器侧状态）。 */
    public void cancel() {
        if (relocate != null) {
            relocate.onFinish(null, false);
            relocate = null;
        }
        phase = PHASE_IDLE;
    }

    /** 镜像中目标快捷槽是否已是烟花（服务器搬迁回包生效的标志）。 */
    private boolean rocketInHotbar() {
        return registry != null && registry.isReady()
                && ROCKET_ITEM_NAME.equals(registry.itemName(inventory.itemIdAt(rocketSlot)));
    }

    /** 指定槽当前是否装着烟花火箭（选弹粘性复用判据；槽越界/空/非火箭返回 false）。 */
    private boolean isRocketAt(int slot) {
        if (registry == null || !registry.isReady()
                || slot < 0 || slot >= InventoryState.TOTAL) {
            return false;
        }
        int id = inventory.itemIdAt(slot);
        return id > 0 && ROCKET_ITEM_NAME.equals(registry.itemName(id));
    }

    /**
     * 搬迁轮询（发送与确认合并：服务器回包刷新镜像是异步的，点击链"发出完成"
     * 的那一 tick 目标槽可能仍是旧物品，只以“火箭已落在快捷槽”为成功判据）。
     *
     * @param prepareMode true=预置模式（完成后停在 PREPARED 不推进预热）
     */
    private Status relocateTick(MovementEngine engine, boolean prepareMode) {
        if (relocate != null) {
            relocate.tick(engine);
        }
        tickCount++;
        if (rocketInHotbar()) {
            relocate = null;
            phase = prepareMode ? PHASE_PREPARED : PHASE_WARMUP;
            tickCount = 0;
            return Status.BUSY;
        }
        if (tickCount >= RELOCATE_TIMEOUT_TICKS) {
            if (relocate != null) {
                relocate.onFinish(null, false);
                relocate = null;
            }
            log.info("[Running] 烟花搬迁到快捷栏未生效，本次加速放弃。");
            phase = PHASE_IDLE;
            return Status.FAILED;
        }
        return Status.BUSY;
    }

    /**
     * 从槽内物品栈解析火箭飞行时长（{@code minecraft:fireworks} 组件的
     * flight_duration，对齐官方 1~3）；组件缺失/解析失败回退构造传入的默认值。
     */
    private int resolveFlightDuration(int slot) {
        try {
            ItemStack stack = inventory.stackAt(slot);
            if (stack == null) {
                return fallbackFlightDuration;
            }
            DataComponents patch = stack.getDataComponentsPatch();
            if (patch == null) {
                return fallbackFlightDuration;
            }
            Fireworks fireworks = patch.get(DataComponentTypes.FIREWORKS);
            if (fireworks == null) {
                return fallbackFlightDuration;
            }
            return Math.min(3, Math.max(1, fireworks.getFlightDuration()));
        } catch (RuntimeException e) {
            return fallbackFlightDuration;
        }
    }

    private static int nextSequence() {
        return Bot.INSTANCE.getAndIncreaseSequence();
    }
}
