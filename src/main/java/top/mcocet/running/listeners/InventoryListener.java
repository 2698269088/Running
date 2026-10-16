package top.mcocet.running.listeners;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHeldSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetPlayerInventoryPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.world.InventoryState;

/**
 * 把服务器下发的背包/手持信息同步到 {@link InventoryState}（完整 36 格 + 盔甲 + 副手）。
 *
 * <p>处理三类背包包，并区分两套不同的槽位编号：</p>
 * <ul>
 *   <li>{@code SetPlayerInventory}：单格更新，槽位采用<b>玩家物品栏布局</b>
 *       （0..8 快捷栏，9..35 主背包，36..39 盔甲，40 副手），与 {@link InventoryState}
 *       的内部布局<b>完全一致</b>，故直接按原下标写入；</li>
 *   <li>{@code ContainerSetSlot} / {@code ContainerSetContent}（玩家背包窗口）：槽位采用
 *       原版 {@code InventoryMenu} 的<b>“打开容器”布局</b>（共 46 格），与内部布局不同，
 *       必须经 {@link #menuSlotToInventory(int)} 换算后再写入；同时刷新 stateId
 *       （点击包需携带最新值）；</li>
 *   <li>{@code SetHeldSlot}：服务器设定的当前快捷栏手持槽。</li>
 * </ul>
 *
 * <p>“玩家背包窗口”的 containerId 在不同协议版本并不可靠：传统为 {@code 0}（原版
 * {@code InventoryMenu} 的窗口 id 恒为 0），部分 1.21.9+ 快照改以 {@code -1} 专指玩家物品栏。
 * 二者携带的都是 {@code InventoryMenu} 46 格布局。其余 id（箱子等已开窗口）不触碰背包镜像。</p>
 *
 * <p>诊断：每类背包包首次到达时打印一条信息日志（含 id / 槽位 / 物品与镜像占用格数），
 * 用于在“镜像疑似为空/错位”时直接判定是“服务器没发”还是“发了但布局没对上”。</p>
 */
public final class InventoryListener extends SessionAdapter {
    private static final Logger log = LoggerFactory.getLogger(InventoryListener.class.getSimpleName());

    /** 传统玩家背包窗口 id */
    private static final int PLAYER_CONTAINER_ID = 0;
    /** 部分新版本以 -1 专指玩家物品栏 */
    private static final int PLAYER_CONTAINER_ID_NEG = -1;

    private final InventoryState inventory;

    /** 各类包“首次到达”诊断标记（只打印一次，避免刷屏） */
    private boolean loggedSetSlot;
    private boolean loggedSetContent;
    private boolean loggedSetPlayerInv;

    public InventoryListener(InventoryState inventory) {
        this.inventory = inventory;
    }

    @Override
    public void packetReceived(Session session, Packet packet) {
        if (packet instanceof ClientboundSetPlayerInventoryPacket inv) {
            // 玩家物品栏布局（0..40），与内部布局一致，直接写入
            inventory.setSlot(inv.getSlot(), inv.getContents());
            if (!loggedSetPlayerInv) {
                loggedSetPlayerInv = true;
                log.info("[Running] 背包诊断：收到 SetPlayerInventory 槽={} 物品={}（镜像已占 {} 格）。",
                        inv.getSlot(), describe(inv.getContents()), filledSlots());
            }
        } else if (packet instanceof ClientboundContainerSetSlotPacket slot) {
            if (isPlayerContainer(slot.getContainerId())) {
                applyMenuSlot(slot.getSlot(), slot.getItem());
                inventory.updateStateId(slot.getStateId());
                if (!loggedSetSlot) {
                    loggedSetSlot = true;
                    log.info("[Running] 背包诊断：收到 ContainerSetSlot 容器={} 菜单槽={}→内部槽={} 物品={}（镜像已占 {} 格）。",
                            slot.getContainerId(), slot.getSlot(), menuSlotToInventory(slot.getSlot()),
                            describe(slot.getItem()), filledSlots());
                }
            } else if (!loggedSetSlot) {
                loggedSetSlot = true;
                log.info("[Running] 背包诊断：收到 ContainerSetSlot 但容器 id={} 非玩家背包，未回填镜像。",
                        slot.getContainerId());
            }
        } else if (packet instanceof ClientboundContainerSetContentPacket content) {
            if (isPlayerContainer(content.getContainerId())) {
                applyMenuContent(content.getItems());
                inventory.updateStateId(content.getStateId());
                if (!loggedSetContent) {
                    loggedSetContent = true;
                    log.info("[Running] 背包诊断：收到 ContainerSetContent 容器={} 长度={}（镜像已占 {} 格）。",
                            content.getContainerId(),
                            content.getItems() == null ? -1 : content.getItems().length, filledSlots());
                }
            } else if (!loggedSetContent) {
                loggedSetContent = true;
                log.info("[Running] 背包诊断：收到 ContainerSetContent 但容器 id={} 非玩家背包，未回填镜像。",
                        content.getContainerId());
            }
        } else if (packet instanceof ClientboundSetHeldSlotPacket held) {
            inventory.setHeldSlot(held.getSlot());
        }
    }

    /**
     * 按 InventoryMenu“打开容器”布局回填整窗内容：数组下标即菜单槽位，逐个换算到内部布局。
     */
    private void applyMenuContent(ItemStack[] stacks) {
        if (stacks == null || stacks.length == 0) {
            return;
        }
        for (int menu = 0; menu < stacks.length; menu++) {
            int inv = menuSlotToInventory(menu);
            if (inv >= 0) {
                inventory.setSlot(inv, stacks[menu]);
            }
        }
    }

    /** 单格：把 InventoryMenu 菜单槽换算到内部槽后写入（合成格/结果格忽略）。 */
    private void applyMenuSlot(int menuSlot, ItemStack item) {
        int inv = menuSlotToInventory(menuSlot);
        if (inv >= 0) {
            inventory.setSlot(inv, item);
        }
    }

    /**
     * InventoryMenu（窗口 id=0，共 46 格）布局下标 → {@link InventoryState} 内部下标；
     * 返回 {@code -1} 表示该格不属于持久物品栏（合成结果 0 / 合成格 1..4），应忽略。
     *
     * <pre>
     *   菜单 0        合成结果        → 忽略
     *   菜单 1..4     2x2 合成格      → 忽略
     *   菜单 5..8     盔甲 头/胸/腿/脚 → 内部 39/38/37/36
     *   菜单 9..35    主背包          → 内部 9..35（一致）
     *   菜单 36..44   快捷栏 0..8     → 内部 0..8
     *   菜单 45       副手            → 内部 40
     * </pre>
     */
    private static int menuSlotToInventory(int menu) {
        if (menu >= 5 && menu <= 8) {
            // 头盔(5)→内部39、胸(6)→38、腿(7)→37、脚(8)→36
            return 39 - (menu - 5);
        }
        if (menu >= 9 && menu <= 35) {
            return menu; // 主背包区间一致
        }
        if (menu >= 36 && menu <= 44) {
            return menu - 36; // 快捷栏
        }
        if (menu == 45) {
            return InventoryState.OFFHAND_SLOT; // 副手
        }
        return -1; // 合成结果 / 合成格（0..4）及任何越界
    }

    private static boolean isPlayerContainer(int containerId) {
        return containerId == PLAYER_CONTAINER_ID || containerId == PLAYER_CONTAINER_ID_NEG;
    }

    private int filledSlots() {
        int count = 0;
        for (int i = 0; i < InventoryState.TOTAL; i++) {
            if (inventory.hasItem(i)) {
                count++;
            }
        }
        return count;
    }

    private static String describe(ItemStack stack) {
        return stack == null || stack.getId() <= 0 ? "空" : ("id=" + stack.getId() + " x" + stack.getAmount());
    }
}
