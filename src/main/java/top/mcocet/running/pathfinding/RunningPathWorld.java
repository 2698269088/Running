package top.mcocet.running.pathfinding;

import top.mcocet.running.world.BlockNameTable;
import top.mcocet.running.world.RunningWorld;
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
    private final int maxFallHeight;
    /** 计算开始时从背包解析出的候选工具（已去重，不含徒手）；用于破块代价取最优 */
    private final List<ToolModel> candidateTools;

    public RunningPathWorld(RunningWorld world, BlockNameTable blocks, boolean allowSprint, int maxFallHeight) {
        this(world, blocks, List.of(), allowSprint, maxFallHeight);
    }

    public RunningPathWorld(RunningWorld world, BlockNameTable blocks, List<ToolModel> candidateTools,
                            boolean allowSprint, int maxFallHeight) {
        this.world = world;
        this.blocks = blocks;
        this.candidateTools = candidateTools != null ? List.copyOf(candidateTools) : List.of();
        this.allowSprint = allowSprint;
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

    /**
     * 危险方块启发式（首版仅按名称 / 流体粗判）：火、岩浆相关、岩浆怪块、仙人掌、
     * 甜浆果、细雪等。命中则既不可通行也不可站立。更完整的危险规避留作后续增量。
     */
    private boolean isDangerous(int state) {
        if (blocks.fluidType(state) == BlockNameTable.FLUID_LAVA) {
            return true;
        }
        String name = blocks.nameOf(state);
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
