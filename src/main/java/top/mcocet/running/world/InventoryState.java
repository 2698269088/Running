package top.mcocet.running.world;

import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import top.mcocet.running.world.item.ItemRegistry;
import top.mcocet.running.world.item.ToolModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 机器人完整背包（玩家容器 36 格主物品 + 盔甲 4 + 副手 1）与当前手持快捷栏槽的本地镜像。
 *
 * <p>数据由网络线程写入（{@code SetPlayerInventory} / {@code ContainerSetSlot}(玩家容器) /
 * {@code SetHeldSlot}），由引擎线程与寻路计算线程读取。采用写时复制的 {@code volatile int[]}
 * 保证读侧无锁且看到一致快照。物品以 numeric item ID 存储（0 = 空），名称/工具经
 * {@link ItemRegistry} 解析。</p>
 *
 * <p>槽位约定（对齐原版玩家容器索引）：0..8 快捷栏（从左到右，手持槽即选此区间），
 * 9..35 主背包其余格，36..39 盔甲（脚/腿/胸/头），40 副手。</p>
 */
public final class InventoryState {

    public static final int HOTBAR_SIZE = 9;
    public static final int MAIN_SIZE = 36;      // 0..35
    public static final int TOTAL = 41;          // 0..35 主, 36..39 盔甲, 40 副手

    /** 每格的 item numeric ID（0 = 空/未设置） */
    private volatile int[] itemIds = new int[TOTAL];
    /** 当前手持快捷栏槽（0..8） */
    private volatile int heldSlot;

    /** 设置某槽位的物品（itemStack 为 null 或 id<=0 视为清空）。 */
    public void setSlot(int slot, ItemStack itemStack) {
        if (slot < 0 || slot >= TOTAL) {
            return;
        }
        int id = itemStack == null ? 0 : itemStack.getId();
        int[] current = itemIds;
        if (current[slot] == id) {
            return;
        }
        int[] copy = current.clone();
        copy[slot] = id;
        itemIds = copy;
    }

    public void setSlot(int slot, int itemId) {
        setSlot(slot, itemId <= 0 ? null : new ItemStack(itemId));
    }

    /** 整包替换（收到全量背包同步时）。 */
    public void setAll(int[] slots) {
        int[] copy = new int[TOTAL];
        int n = Math.min(TOTAL, slots.length);
        System.arraycopy(slots, 0, copy, 0, n);
        itemIds = copy;
    }

    public void setHeldSlot(int slot) {
        this.heldSlot = Math.max(0, Math.min(HOTBAR_SIZE - 1, slot));
    }

    public int heldSlot() {
        return heldSlot;
    }

    /** 指定槽位的 item numeric ID（0 = 空）。 */
    public int itemIdAt(int slot) {
        int[] current = itemIds;
        return (slot >= 0 && slot < current.length) ? current[slot] : 0;
    }

    /** 当前手持（快捷栏选中槽）的 item numeric ID。 */
    public int heldItemId() {
        return itemIdAt(heldSlot);
    }

    /** 当前手持物品名（经注册表解析）；空/未知返回 null。 */
    public String heldItemName(ItemRegistry registry) {
        int id = heldItemId();
        return id <= 0 ? null : registry.itemName(id);
    }

    /** 当前手持物品的工具模型；徒手/非工具/注册表未就绪返回 null。 */
    public ToolModel heldTool(ItemRegistry registry) {
        int id = heldItemId();
        return id <= 0 ? null : registry.toolOfItem(id);
    }

    /**
     * 快捷栏（槽 0..8）中可用工具的去重 {@link ToolModel} 列表，供寻路破块代价逐块取最优。
     *
     * <p>同一物品的多个堆叠会解析为同一模型实例（{@link ItemRegistry} 按名缓存），因此自然去重；
     * 不同工具（如钻石镐与铁镐）保留为不同候选。注册表未就绪时返回空列表（退化为仅徒手）。</p>
     */
    public List<ToolModel> hotbarToolModels(ItemRegistry registry) {
        List<ToolModel> out = new ArrayList<>();
        if (registry == null || !registry.isReady()) {
            return out;
        }
        Set<ToolModel> seen = new HashSet<>();
        int[] current = itemIds;
        for (int slot = 0; slot < HOTBAR_SIZE && slot < current.length; slot++) {
            int id = current[slot];
            if (id <= 0) {
                continue;
            }
            ToolModel tool = registry.toolOfItem(id);
            if (tool != null && seen.add(tool)) {
                out.add(tool);
            }
        }
        return out;
    }

    /** 主背包中是否存在某工具名的物品（供后续自动选镐，返回快捷栏槽或 -1）。 */
    public int findHotbarSlotHolding(ItemRegistry registry, String itemSimpleName) {
        int[] current = itemIds;
        for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
            int id = current[slot];
            if (id > 0 && itemSimpleName.equals(registry.itemName(id))) {
                return slot;
            }
        }
        return -1;
    }

    public void clear() {
        itemIds = new int[TOTAL];
        heldSlot = 0;
    }

    @Override
    public String toString() {
        return "InventoryState{held=" + heldSlot + ", items=" + Arrays.toString(itemIds) + "}";
    }
}
