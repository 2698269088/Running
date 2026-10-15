package top.mcocet.running.bot;

import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.world.InventoryState;
import xin.bbtt.mcbot.Bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 背包移动目标：由 {@link MovementEngine} 以 20 TPS 驱动，把一次“背包整理”拆成
 * 若干次玩家容器（id=0）点击并<b>串行</b>发出，用于把物品移动到指定位置。
 *
 * <p>发包全部落在引擎 tick 内（与 {@link BlockActionGoal} 同构），从而与每 tick 的
 * {@code ClientTickEndPacket} 正确配对。容器点击不是飞行包，不参与 Grim TickTimer 的
 * 飞行包计数，但仍统一走引擎线程以保持与位置包的发送节律一致。</p>
 *
 * <p><b>1.20.5+ 的 stateId 顺序约束</b>：服务器只接受携带<b>最新</b> stateId 的点击，
 * 若版本戳落后则整包丢弃并回推一次内容重同步。因此对需要“连续多次点击”的交换/搬运，
 * 本目标在发出一步后<b>暂停等待</b>，直到网络线程把服务器回包中的新 stateId 写回
 * {@link InventoryState#stateId()}（{@link InventoryState#updateStateId(int)}），
 * 才以最新戳发出下一步；超过 {@link #ACK_TIMEOUT_TICKS} 未收到推进则视为已被重同步并继续，
 * 保证最坏情况也不会永久卡住。</p>
 *
 * <p>预测性 {@code changedSlots}/{@code carriedItem}：本实现刻意发送<b>空预测</b>
 * （不带组件、不猜结果）。服务器据此走权威回推路径——这与“慢客户端”行为一致，只会多出
 * 若干回包，绝不会让服务器执行非法操作，也不会触发任何反作弊。</p>
 *
 * <p><b>槽位布局换算</b>：{@link InventoryState} 内部按“玩家物品栏布局”编号
 * （0..8 快捷栏，9..35 主背包，36..39 盔甲，40 副手），而服务器对窗口 id=0 的点击
 * 期望的是原版 {@code InventoryMenu} 的“打开容器”布局（快捷栏 36..44、副手 45、盔甲 5..8 等）。
 * 因此发出前用 {@link #inventorySlotToMenu(int)} 把内部槽换算成菜单槽，与
 * {@code InventoryListener} 的读取侧换算严格互逆。</p>
 */
public final class InventoryMoveGoal implements MovementGoal {
    private static final Logger log = LoggerFactory.getLogger(InventoryMoveGoal.class.getSimpleName());

    /** 玩家背包容器 id */
    private static final int PLAYER_CONTAINER_ID = 0;
    /** 发出一步后等待服务器推进 stateId 的最大 tick 数；超时视为已重同步并继续 */
    private static final int ACK_TIMEOUT_TICKS = 20;

    /** 单步点击描述：动作类型 + 参数 + 目标槽。预测字段一律留空交由服务器权威回推。 */
    private record Click(ContainerActionType action, ContainerAction param, int slot) {
    }

    private final InventoryState inventory;
    private final List<Click> steps;
    private final String description;

    // 引擎线程私有
    private int stepIndex;
    private int waitingTicks;
    private int stateIdBeforeStep;
    private boolean awaitingAck;
    private boolean finished;

    private InventoryMoveGoal(InventoryState inventory, List<Click> steps, String description) {
        this.inventory = inventory;
        this.steps = steps;
        this.description = description;
    }

    // ------------------------------------------------------------------
    // 工厂：三种移动语义
    // ------------------------------------------------------------------

    /**
     * 把 {@code sourceSlot} 的整组物品与目标快捷栏槽 {@code hotbarIndex}(0..8) 交换。
     * 单包原子（原版“按住数字键点击格子”），无需等待确认。
     */
    public static InventoryMoveGoal toHotbar(InventoryState inventory, int sourceSlot, int hotbarIndex) {
        MoveToHotbarAction action = MoveToHotbarAction.from(hotbarIndex);
        if (action == null) {
            throw new IllegalArgumentException("非法快捷栏槽: " + hotbarIndex);
        }
        List<Click> steps = List.of(new Click(ContainerActionType.MOVE_TO_HOTBAR_SLOT, action, sourceSlot));
        return new InventoryMoveGoal(inventory, steps,
                "槽 " + sourceSlot + " → 快捷栏 " + hotbarIndex);
    }

    /**
     * 快捷移动（shift 点击）：把 {@code slot} 的整组在主背包与快捷栏之间自动收纳。
     * 单包原子，落点由服务器决定。
     */
    public static InventoryMoveGoal quickMove(InventoryState inventory, int slot) {
        List<Click> steps = List.of(new Click(ContainerActionType.SHIFT_CLICK_ITEM,
                ShiftClickItemAction.LEFT_CLICK, slot));
        return new InventoryMoveGoal(inventory, steps, "槽 " + slot + " 快捷移动（整组）");
    }

    /**
     * 把 {@code from} 的物品移动到 {@code to}（任意槽，0..40）。
     *
     * <ul>
     *   <li>{@code single=false}：整组搬运；{@code to} 非空时为 from↔to 交换（额外一步把
     *       被挤出的物品放回 from），为空时两步（拾起→放下）即可；</li>
     *   <li>{@code single=true}：仅移动 1 个——左键拾起整组、右键在 {@code to} 放置 1 个、
     *       再左键把余下放回 {@code from}。</li>
     * </ul>
     *
     * @param toHasItem   {@code to} 当前是否有物品（决定整组搬运是否需要交换第三步）
     */
    public static InventoryMoveGoal move(InventoryState inventory, int from, int to,
                                         boolean single, boolean toHasItem) {
        List<Click> steps = new ArrayList<>(3);
        // 1) 从左键拾起 from 整组到光标
        steps.add(new Click(ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK, from));
        if (single) {
            // 2) 右键向 to 放下 1 个；3) 左键把余量放回 from
            steps.add(new Click(ContainerActionType.CLICK_ITEM, ClickItemAction.RIGHT_CLICK, to));
            steps.add(new Click(ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK, from));
        } else {
            // 2) 左键把整组放到 to（若 to 有物则把 to 原物挤到光标）
            steps.add(new Click(ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK, to));
            if (toHasItem) {
                // 3) 左键把被挤出的原 to 物品放回（此时）已空的 from —— 完成交换
                steps.add(new Click(ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK, from));
            }
        }
        String desc = single
                ? "槽 " + from + " → 槽 " + to + "（单个）"
                : "槽 " + from + " → 槽 " + to + "（整组" + (toHasItem ? "/交换" : "") + "）";
        return new InventoryMoveGoal(inventory, steps, desc);
    }

    // ------------------------------------------------------------------
    // tick 主循环（引擎线程）
    // ------------------------------------------------------------------

    @Override
    public void tick(MovementEngine engine) {
        if (finished) {
            return;
        }
        if (stepIndex >= steps.size()) {
            finished = true;
            log.info("[Running] 背包操作完成：{}。", description);
            return;
        }

        // 等待上一步的服务器确认（stateId 推进）
        if (awaitingAck) {
            boolean advanced = inventory.stateId() != stateIdBeforeStep;
            waitingTicks++;
            if (!advanced && waitingTicks < ACK_TIMEOUT_TICKS) {
                return; // 继续等待
            }
            // 已推进或超时（视为已被重同步）：解除等待，本 tick 发出下一步
            awaitingAck = false;
            if (advanced) {
                waitingTicks = 0;
            }
        }

        // 发出当前步
        Click click = steps.get(stepIndex);
        stateIdBeforeStep = inventory.stateId();
        sendClick(click);
        stepIndex++;

        // 仍有后续步骤才需要等待确认；最后一步发出后即可收尾（不再自旋等待回包）
        if (stepIndex < steps.size()) {
            awaitingAck = true;
            waitingTicks = 0;
        } else {
            finished = true;
            log.info("[Running] 背包操作已发出：{}。", description);
        }
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public void onStart(MovementEngine engine) {
        log.info("[Running] 开始背包操作：{}（共 {} 步点击）。", description, steps.size());
    }

    @Override
    public void onFinish(MovementEngine engine, boolean completed) {
        finished = true;
    }

    // ------------------------------------------------------------------
    // 发包
    // ------------------------------------------------------------------

    private void sendClick(Click click) {
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                PLAYER_CONTAINER_ID,
                inventory.stateId(),
                inventorySlotToMenu(click.slot()),
                click.action(),
                click.param(),
                null,               // carriedItem 预测：空
                Map.of());          // changedSlots 预测：空，交服务器权威回推
        send(packet);
    }

    /**
     * {@link InventoryState} 内部槽（0..40）→ InventoryMenu（窗口 id=0）菜单槽（0..45）。
     * 与 {@code InventoryListener.menuSlotToInventory} 互逆：
     * <pre>
     *   内部 0..8（快捷栏）  → 菜单 36..44
     *   内部 9..35（主背包）  → 菜单 9..35（一致）
     *   内部 36..39（盔甲）   → 菜单 8..5（脚/腿/胸/头 对应 8/7/6/5）
     *   内部 40（副手）       → 菜单 45
     * </pre>
     */
    private static int inventorySlotToMenu(int inv) {
        if (inv >= 0 && inv <= 8) {
            return inv + 36; // 快捷栏
        }
        if (inv >= 9 && inv <= 35) {
            return inv; // 主背包区间一致
        }
        if (inv >= 36 && inv <= 39) {
            return 44 - inv; // 盔甲：内部 36(脚)→菜单 8，39(头)→菜单 5
        }
        if (inv == InventoryState.OFFHAND_SLOT) {
            return 45; // 副手
        }
        return inv; // 越界原样（不应发生）
    }

    private static void send(Packet packet) {
        ClientSession session = Bot.INSTANCE.getSession();
        if (session == null || !session.isConnected()) {
            return;
        }
        session.send(packet);
    }
}
