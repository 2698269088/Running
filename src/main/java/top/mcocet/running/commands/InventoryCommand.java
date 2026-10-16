package top.mcocet.running.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.Main;
import top.mcocet.running.bot.InventoryMoveGoal;
import top.mcocet.running.bot.MovementEngine;
import top.mcocet.running.world.InventoryState;
import top.mcocet.running.world.item.ItemRegistry;
import xin.bbtt.mcbot.command.Command;
import xin.bbtt.mcbot.command.CommandExecutor;

import java.util.List;
import java.util.stream.Stream;

/**
 * inventory（别名 inv）命令：查看机器人背包与物品栏，并把物品移动到指定位置。
 *
 * <p>用法：</p>
 * <ul>
 *   <li>{@code inventory} —— 打印手持槽、快捷栏(0-8)、主背包(9-35)、盔甲(36-39) 与副手(40)
 *       的物品名称与堆叠数量；</li>
 *   <li>{@code inventory move <源槽> <目标槽> [one]} —— 把 {@code 源槽} 的物品移动到 {@code 目标槽}
 *       （任意槽 0-40）。默认整组搬运（目标槽非空时为交换）；加 {@code one} 仅移动 1 个。
 *       由引擎以 20 TPS 逐 tick 发出容器点击并在每步之间等待服务器 stateId 确认；</li>
 *   <li>{@code inventory hotbar <源槽> <0-8>} —— 单包把 {@code 源槽} 整组换到指定快捷栏槽；</li>
 *   <li>{@code inventory quick <槽>} —— 整组 shift 快捷移动（在主背包/快捷栏间自动收纳）；</li>
 *   <li>{@code inventory stop} —— 取消当前正在进行的背包操作。</li>
 * </ul>
 *
 * <p>移动类操作需要已进入游戏；查看不受限。移动会替换引擎当前的移动目标（手动接管）。</p>
 */
public final class InventoryCommand extends CommandExecutor {
    private static final Logger log = LoggerFactory.getLogger(InventoryCommand.class.getSimpleName());

    private static final String[] ARMOR_LABELS = {"脚", "腿", "胸", "头"};

    @Override
    public void onCommand(Command command, String label, String[] args) {
        Main plugin = Main.INSTANCE;
        if (plugin == null) {
            log.warn("[Running] 插件实例不存在。");
            return;
        }
        InventoryState inventory = plugin.inventory();
        ItemRegistry registry = plugin.itemRegistry();

        if (args.length == 0) {
            printInventory(inventory, registry);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "move" -> doMove(plugin, inventory, args);
            case "hotbar" -> doHotbar(plugin, inventory, args);
            case "quick" -> doQuick(plugin, inventory, args);
            case "stop" -> doStop(plugin);
            default -> usage(command);
        }
    }

    // ------------------------------------------------------------------
    // 查看
    // ------------------------------------------------------------------

    private void printInventory(InventoryState inventory, ItemRegistry registry) {
        log.info("[Running] ===== 背包（手持槽 {}，stateId {}）=====",
                inventory.heldSlot(), inventory.stateId());

        StringBuilder hotbar = new StringBuilder("[Running] 快捷栏:");
        for (int slot = 0; slot < InventoryState.HOTBAR_SIZE; slot++) {
            hotbar.append(String.format(" [%d]%s%s", slot, slot == inventory.heldSlot() ? "*" : "",
                    describe(inventory, registry, slot)));
        }
        log.info(hotbar.toString());

        StringBuilder main = new StringBuilder("[Running] 主背包:");
        boolean any = false;
        for (int slot = InventoryState.HOTBAR_SIZE; slot < InventoryState.ARMOR_FIRST; slot++) {
            if (inventory.hasItem(slot)) {
                main.append(String.format(" [%d]%s", slot, describe(inventory, registry, slot)));
                any = true;
            }
        }
        log.info(any ? main.toString() : "[Running] 主背包: （空）");

        StringBuilder armor = new StringBuilder("[Running] 盔甲:");
        for (int i = 0; i < ARMOR_LABELS.length; i++) {
            armor.append(String.format(" %s%s", ARMOR_LABELS[i],
                    describe(inventory, registry, InventoryState.ARMOR_FIRST + i)));
        }
        armor.append(String.format(" | 副手%s", describe(inventory, registry, InventoryState.OFFHAND_SLOT)));
        log.info(armor.toString());
    }

    /** 单格描述：" 名称 x数量"；空格返回 " 空"。 */
    private static String describe(InventoryState inventory, ItemRegistry registry, int slot) {
        int id = inventory.itemIdAt(slot);
        if (id <= 0) {
            return " 空";
        }
        String name = registry.isReady() ? registry.itemName(id) : null;
        if (name == null) {
            name = "ID" + id;
        }
        int amount = inventory.amountAt(slot);
        return " " + name + (amount > 1 ? " x" + amount : "");
    }

    // ------------------------------------------------------------------
    // 移动
    // ------------------------------------------------------------------

    private void doMove(Main plugin, InventoryState inventory, String[] args) {
        if (args.length < 3) {
            log.info("[Running] 用法: inventory move <源槽> <目标槽> [one]");
            return;
        }
        Integer from = parseInt(args[1]);
        Integer to = parseInt(args[2]);
        boolean single = args.length >= 4 && args[3].equalsIgnoreCase("one");
        if (from == null || to == null) {
            log.info("[Running] 槽号格式错误（需为 0-40 的整数）。");
            return;
        }
        if (!inRange(from) || !inRange(to)) {
            log.info("[Running] 槽号超界（有效范围 0-40）。");
            return;
        }
        if (from.equals(to)) {
            log.info("[Running] 源槽与目标槽相同。");
            return;
        }
        if (!inventory.hasItem(from)) {
            log.info("[Running] 源槽 {} 为空，无物可移动。", from);
            return;
        }
        MovementEngine engine = requireEngine(plugin);
        if (engine == null) return;

        boolean toHasItem = inventory.hasItem(to);
        engine.setGoal(InventoryMoveGoal.move(inventory, from, to, single, toHasItem));
    }

    private void doHotbar(Main plugin, InventoryState inventory, String[] args) {
        if (args.length < 3) {
            log.info("[Running] 用法: inventory hotbar <源槽> <0-8>");
            return;
        }
        Integer from = parseInt(args[1]);
        Integer hotbar = parseInt(args[2]);
        if (from == null || hotbar == null) {
            log.info("[Running] 槽号格式错误。");
            return;
        }
        if (!inRange(from)) {
            log.info("[Running] 源槽超界（有效范围 0-40）。");
            return;
        }
        if (hotbar < 0 || hotbar >= InventoryState.HOTBAR_SIZE) {
            log.info("[Running] 快捷栏槽需在 0-8 之间。");
            return;
        }
        if (!inventory.hasItem(from)) {
            log.info("[Running] 源槽 {} 为空，无物可移动。", from);
            return;
        }
        MovementEngine engine = requireEngine(plugin);
        if (engine == null) return;

        engine.setGoal(InventoryMoveGoal.toHotbar(inventory, from, hotbar));
    }

    private void doQuick(Main plugin, InventoryState inventory, String[] args) {
        if (args.length < 2) {
            log.info("[Running] 用法: inventory quick <槽>");
            return;
        }
        Integer slot = parseInt(args[1]);
        if (slot == null) {
            log.info("[Running] 槽号格式错误。");
            return;
        }
        if (!inRange(slot)) {
            log.info("[Running] 槽号超界（有效范围 0-40）。");
            return;
        }
        if (!inventory.hasItem(slot)) {
            log.info("[Running] 槽 {} 为空，无物可移动。", slot);
            return;
        }
        MovementEngine engine = requireEngine(plugin);
        if (engine == null) return;

        engine.setGoal(InventoryMoveGoal.quickMove(inventory, slot));
    }

    private void doStop(Main plugin) {
        MovementEngine engine = plugin.movementEngine();
        if (engine != null) {
            engine.setGoal(null);
        }
        log.info("[Running] 已取消当前背包操作。");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 校验插件与引擎就绪且已进入游戏；未就绪输出提示并返回 null。 */
    private MovementEngine requireEngine(Main plugin) {
        MovementEngine engine = plugin.movementEngine();
        if (engine == null) {
            log.warn("[Running] 移动引擎未就绪（插件尚未启用）。");
            return null;
        }
        if (!engine.isInGame()) {
            log.warn("[Running] 尚未进入游戏，无法操作背包。");
            return null;
        }
        return engine;
    }

    private static boolean inRange(int slot) {
        return slot >= 0 && slot < InventoryState.TOTAL;
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void usage(Command command) {
        log.info("[Running] 用法: {}", command.getUsage());
    }

    @Override
    public List<String> onTabComplete(Command cmd, String label, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return Stream.of("move", "hotbar", "quick", "stop")
                    .filter(sub -> sub.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
