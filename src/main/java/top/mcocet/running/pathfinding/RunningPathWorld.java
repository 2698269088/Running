package top.mcocet.running.pathfinding;

import top.mcocet.running.world.BlockNameTable;
import top.mcocet.running.world.InventoryState;
import top.mcocet.running.world.RunningWorld;
import top.mcocet.running.world.item.ItemRegistry;
import top.mcocet.running.world.item.ToolModel;

import java.util.List;

/**
 * {@link PathWorld} 到 Running 世界模型的映射实现：方块状态取自 {@link RunningWorld}
 * （仅已加载区块），通行 / 站立 / 危险判定基于 {@link BlockNameTable} 的近似几何。
 *
 * <p>与 {@code PlayerPhysics} 使用同一 {@link BlockNameTable}，保证"规划"与"执行"对
 * 方块几何的判断一致（full / slab / fence / fluid 的近似）。首版：</p>
 * <ul>
 *   <li>可通行 = 非 {@link BlockNameTable#blocksMotion}（空气、地毯、火把等无实质阻挡）；</li>
 *   <li>可站立 = {@link BlockNameTable#hasCollision} 且有可踩顶面，排除流体与危险方块；</li>
 *   <li>流体（水 / 岩浆）与火 / 岩浆岩等按危险处理，一律不可通行 / 不可站立。</li>
 * </ul>
 * 更精确的破块、攀爬、涉水与危险规避留作后续增量（接口已占位）。
 */
public final class RunningPathWorld implements PathWorld {

    private final RunningWorld world;
    private final BlockNameTable blocks;
    private final boolean allowSprint;
    private final boolean allowBreak;
    /** 挖掘优先模式：挖块代价打折且隐含允许破块（见 {@link #canBreak()}） */
    private final boolean digPriority;
    /** 允许“放置搭桥”：无路可踩的缺口用背包方块铺地板（见 {@link #canPlace()}） */
    private final boolean allowPlace;
    private final boolean allowParkour;
    private final int maxFallHeight;
    /** 计算开始时从背包解析出的候选工具（已去重，不含徒手）；用于破块代价取最优 */
    private final List<ToolModel> candidateTools;
    /** 背包与物品表（可为 null）：用于执行时反查“该切哪把工具”的快捷槽 */
    private final InventoryState inventory;
    private final ItemRegistry registry;

    public RunningPathWorld(RunningWorld world, BlockNameTable blocks, boolean allowSprint, int maxFallHeight) {
        this(world, blocks, List.of(), null, null, allowSprint, false, false, maxFallHeight);
    }

    public RunningPathWorld(RunningWorld world, BlockNameTable blocks, List<ToolModel> candidateTools,
                            boolean allowSprint, int maxFallHeight) {
        this(world, blocks, candidateTools, null, null, allowSprint, false, false, maxFallHeight);
    }

    public RunningPathWorld(RunningWorld world, BlockNameTable blocks, List<ToolModel> candidateTools,
                            InventoryState inventory, ItemRegistry registry,
                            boolean allowSprint, boolean allowBreak, int maxFallHeight) {
        this(world, blocks, candidateTools, inventory, registry, allowSprint, allowBreak, false, maxFallHeight);
    }

    public RunningPathWorld(RunningWorld world, BlockNameTable blocks, List<ToolModel> candidateTools,
                            InventoryState inventory, ItemRegistry registry,
                            boolean allowSprint, boolean allowBreak, boolean allowParkour, int maxFallHeight) {
        this(world, blocks, candidateTools, inventory, registry,
                allowSprint, allowBreak, false, allowParkour, maxFallHeight);
    }

    public RunningPathWorld(RunningWorld world, BlockNameTable blocks, List<ToolModel> candidateTools,
                            InventoryState inventory, ItemRegistry registry,
                            boolean allowSprint, boolean allowBreak, boolean digPriority,
                            boolean allowParkour, int maxFallHeight) {
        this(world, blocks, candidateTools, inventory, registry,
                allowSprint, allowBreak, digPriority, false, allowParkour, maxFallHeight);
    }

    public RunningPathWorld(RunningWorld world, BlockNameTable blocks, List<ToolModel> candidateTools,
                            InventoryState inventory, ItemRegistry registry,
                            boolean allowSprint, boolean allowBreak, boolean digPriority,
                            boolean allowPlace, boolean allowParkour, int maxFallHeight) {
        this.world = world;
        this.blocks = blocks;
        this.candidateTools = candidateTools != null ? List.copyOf(candidateTools) : List.of();
        this.inventory = inventory;
        this.registry = registry;
        this.allowSprint = allowSprint;
        this.allowBreak = allowBreak;
        this.digPriority = digPriority;
        this.allowPlace = allowPlace;
        this.allowParkour = allowParkour;
        this.maxFallHeight = Math.max(0, maxFallHeight);
    }

    @Override
    public int minY() {
        return world.getMinY();
    }

    @Override
    public int maxY() {
        return world.getMinY() + world.getHeight() - 1;
    }

    @Override
    public int stateAt(int x, int y, int z) {
        return world.getBlockStateId(x, y, z);
    }

    @Override
    public boolean isLoaded(int blockX, int blockZ) {
        return world.isChunkLoaded(blockX >> 4, blockZ >> 4);
    }

    @Override
    public boolean isPassable(int x, int y, int z) {
        int state = stateAt(x, y, z);
        if (state == PathWorld.UNLOADED) {
            return false;
        }
        if (state == PathWorld.AIR) {
            return true;
        }
        // 首版：流体不作为可通行空间（不涉水 / 不熔岩）
        if (blocks.fluidType(state) != BlockNameTable.FLUID_NONE) {
            return false;
        }
        if (isDangerous(state)) {
            return false;
        }
        return !blocks.blocksMotion(state);
    }

    @Override
    public boolean isStandable(int x, int y, int z) {
        int state = stateAt(x, y, z);
        if (state == PathWorld.UNLOADED || state == PathWorld.AIR) {
            return false;
        }
        // 流体表面不可稳定站立（无水桶 / 泳池逻辑）
        if (blocks.fluidType(state) != BlockNameTable.FLUID_NONE) {
            return false;
        }
        if (isDangerous(state)) {
            return false;
        }
        return blocks.hasCollision(state);
    }

    @Override
    public boolean allowSprint() {
        return allowSprint;
    }

    @Override
    public boolean allowParkour() {
        return allowParkour;
    }

    @Override
    public double surfaceCostMultiplier(int x, int y, int z) {
        int state = stateAt(x, y, z);
        if (state == PathWorld.UNLOADED || state == PathWorld.AIR) {
            return 1.0;
        }
        float speed = blocks.speedFactor(state);
        if (speed <= 0.0F || speed >= 1.0F) {
            return 1.0;
        }
        // 减速方块（如灵魂沙 speedFactor=0.4）→ 走一格耗时按 1/speed 放大
        return 1.0 / speed;
    }

    @Override
    public boolean isClimbable(int x, int y, int z) {
        int state = stateAt(x, y, z);
        if (state == PathWorld.UNLOADED || state == PathWorld.AIR) {
            return false;
        }
        String name = blocks.nameOf(state);
        return "ladder".equals(name) || "vine".equals(name) || "scaffolding".equals(name);
    }

    @Override
    public boolean isDangerousAt(int x, int y, int z) {
        int state = stateAt(x, y, z);
        if (state == PathWorld.UNLOADED || state == PathWorld.AIR) {
            return false;
        }
        return isDangerous(state);
    }

    @Override
    public boolean canBreak() {
        // 挖掘优先模式隐含允许破块（否则“直挖前进”无从谈起）
        return allowBreak || digPriority;
    }

    @Override
    public boolean digPriority() {
        return digPriority;
    }

    @Override
    public boolean canPlace() {
        return allowPlace;
    }

    @Override
    public PlaceTool planPlaceBlock() {
        if (inventory == null || registry == null || !registry.isReady()) {
            return PlaceTool.none();
        }
        int bestSlot = -1;
        int bestAmount = 0;
        for (int slot = 0; slot < InventoryState.MAIN_SIZE; slot++) {
            int id = inventory.itemIdAt(slot);
            if (id <= 0 || inventory.amountAt(slot) <= 0) {
                continue;
            }
            if (!isPlaceableFloorName(registry.itemName(id))) {
                continue;
            }
            int amount = inventory.amountAt(slot);
            if (amount > bestAmount) {
                bestAmount = amount;
                bestSlot = slot;
            }
        }
        if (bestSlot < 0) {
            return PlaceTool.none();
        }
        if (bestSlot < InventoryState.HOTBAR_SIZE) {
            return new PlaceTool(bestSlot, -1, true);
        }
        // 主背包方块：搬到某个快捷栏槽（优先空槽，否则占用当前手持槽）
        int target = firstFreeHotbarSlot();
        if (target < 0) {
            target = inventory.heldSlot();
        }
        return new PlaceTool(target, bestSlot, true);
    }

    /**
     * 物品名是否可用作搭桥地板：存在同名“有碰撞且非流体”的方块，且非危险（岩浆/火等）
     * 也非重力方块（沙/砾砂放下后会坠落，无法铺成桥面）。
     */
    private boolean isPlaceableFloorName(String name) {
        if (name == null || isDangerousName(name) || !blocks.isStandableBlockName(name)) {
            return false;
        }
        return !name.equals("sand") && !name.equals("red_sand")
                && !name.equals("gravel") && !name.equals("suspicious_sand")
                && !name.equals("suspicious_gravel");
    }

    @Override
    public int maxFallHeight() {
        return maxFallHeight;
    }

    @Override
    public boolean isBreakable(int x, int y, int z) {
        int state = stateAt(x, y, z);
        if (state == PathWorld.UNLOADED || state == PathWorld.AIR) {
            return false;
        }
        // 流体不是“挖”掉的目标（且不可作为破块通路）
        if (blocks.fluidType(state) != BlockNameTable.FLUID_NONE) {
            return false;
        }
        return blocks.isDiggable(state);
    }

    @Override
    public double breakCost(int x, int y, int z) {
        int state = stateAt(x, y, z);
        if (state == PathWorld.UNLOADED || state == PathWorld.AIR) {
            return MoveCosts.COST_INF;
        }
        if (blocks.fluidType(state) != BlockNameTable.FLUID_NONE || !blocks.isDiggable(state)) {
            return MoveCosts.COST_INF;
        }
        String name = blocks.nameOf(state);
        float hardness = blocks.hardness(state);
        byte category = blocks.toolCategory(state);
        boolean requiresTool = blocks.requiresCorrectTool(state);
        // 徒手（无工具组件）作为基线候选，再用背包中可用的工具逐一试算取最快
        // （对齐 Baritone：规划时假设会切到对目标方块最优的工具）
        double best = MiningCalculator.breakTicks(null, name, category, hardness, requiresTool, true);
        for (ToolModel tool : candidateTools) {
            double ticks = MiningCalculator.breakTicks(tool, name, category, hardness, requiresTool, true);
            if (ticks < best) {
                best = ticks;
            }
        }
        return best;
    }

    @Override
    public int bestToolSlotAt(int x, int y, int z) {
        if (inventory == null || registry == null || !registry.isReady()) {
            return -1;
        }
        int state = stateAt(x, y, z);
        if (state == PathWorld.UNLOADED || state == PathWorld.AIR) {
            return -1;
        }
        if (blocks.fluidType(state) != BlockNameTable.FLUID_NONE || !blocks.isDiggable(state)) {
            return -1;
        }
        String name = blocks.nameOf(state);
        float hardness = blocks.hardness(state);
        byte category = blocks.toolCategory(state);
        boolean requiresTool = blocks.requiresCorrectTool(state);
        // 以徒手为基线，逐个快捷栏槽试算，选耗时最短的槽（与 breakCost 的最优估计一致）
        double bestTicks = MiningCalculator.breakTicks(null, name, category, hardness, requiresTool, true);
        int bestSlot = -1;
        for (int slot = 0; slot < InventoryState.HOTBAR_SIZE; slot++) {
            int id = inventory.itemIdAt(slot);
            if (id <= 0) {
                continue;
            }
            ToolModel tool = registry.toolOfItem(id);
            if (tool == null) {
                continue;
            }
            double ticks = MiningCalculator.breakTicks(tool, name, category, hardness, requiresTool, true);
            if (ticks < bestTicks) {
                bestTicks = ticks;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    @Override
    public DigTool planDigTool(int x, int y, int z) {
        int state = stateAt(x, y, z);
        if (state == PathWorld.UNLOADED || state == PathWorld.AIR) {
            return DigTool.unbreakable();
        }
        if (blocks.fluidType(state) != BlockNameTable.FLUID_NONE || !blocks.isDiggable(state)) {
            return DigTool.unbreakable();
        }
        // 无背包 / 物品表信息：退回“按 breakCost 估计、不搬迁”，仅保证可挖性判断
        if (inventory == null || registry == null || !registry.isReady()) {
            return new DigTool(breakCost(x, y, z), -1, -1, true);
        }

        String name = blocks.nameOf(state);
        float hardness = blocks.hardness(state);
        byte category = blocks.toolCategory(state);
        boolean requiresTool = blocks.requiresCorrectTool(state);

        // 徒手为基线，扫描整个背包（0..35：快捷栏 + 主背包）逐件试算，取耗时最短者
        double bestTicks = MiningCalculator.breakTicks(null, name, category, hardness, requiresTool, true);
        int bestHotbar = -1;   // 最优工具已在快捷栏（0..8）
        int bestMain = -1;     // 最优工具在主背包（9..35），需先搬到快捷栏
        for (int slot = 0; slot < InventoryState.MAIN_SIZE; slot++) {
            int id = inventory.itemIdAt(slot);
            if (id <= 0) {
                continue;
            }
            ToolModel tool = registry.toolOfItem(id);
            if (tool == null) {
                continue;
            }
            double ticks = MiningCalculator.breakTicks(tool, name, category, hardness, requiresTool, true);
            if (ticks < bestTicks) {
                bestTicks = ticks;
                if (slot < InventoryState.HOTBAR_SIZE) {
                    bestHotbar = slot;
                    bestMain = -1;
                } else {
                    bestMain = slot;
                    bestHotbar = -1;
                }
            }
        }

        if (bestMain >= 0) {
            // 主背包工具需搬到某个快捷栏槽：优先空槽，否则占用当前手持槽
            int target = firstFreeHotbarSlot();
            if (target < 0) {
                target = inventory.heldSlot();
            }
            return new DigTool(bestTicks, target, bestMain, true);
        }
        if (bestHotbar >= 0) {
            int equip = (bestHotbar == inventory.heldSlot()) ? -1 : bestHotbar;
            return new DigTool(bestTicks, equip, -1, true);
        }
        // 徒手已是最快：无需改变手持、无需搬迁
        return new DigTool(bestTicks, -1, -1, true);
    }

    /** 快捷栏中第一个空槽（0..8），全占则返回 -1。 */
    private int firstFreeHotbarSlot() {
        for (int slot = 0; slot < InventoryState.HOTBAR_SIZE; slot++) {
            if (inventory.itemIdAt(slot) <= 0) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * 危险方块启发式（首版仅按名称 / 流体粗判）：火、岩浆相关、岩浆怪块、仙人掌、
     * 甜浆果、细雪等。命中则既不可通行也不可站立。更完整的危险规避留作后续增量。
     */
    private boolean isDangerous(int state) {
        if (blocks.fluidType(state) == BlockNameTable.FLUID_LAVA) {
            return true;
        }
        return isDangerousName(blocks.nameOf(state));
    }

    /** 按名称的危险启发式（也供搭桥选物品复用）。 */
    private static boolean isDangerousName(String name) {
        if (name == null) {
            return false;
        }
        return name.contains("fire")
                || name.contains("lava")
                || name.contains("magma")
                || name.contains("cactus")
                || name.contains("sweet_berry_bush")
                || name.contains("powder_snow");
    }
}
