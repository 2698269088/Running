package top.mcocet.running.flight;

import top.mcocet.running.world.BlockNameTable;
import top.mcocet.running.world.RunningWorld;

/**
 * {@link FlightWorldQuery} 到 Running 世界模型的适配：与 {@code RunningPathWorld}
 * 同一套方块语义（{@code blocksMotion} 判阻挡、{@code hasCollision} 判可站、
 * 流体与火/岩浆按危险），保证规划、执行、预测三方对几何的判断一致。
 *
 * <p>{@code RunningWorld.getBlockStateId} 约定：区块未加载返回 {@code -1}，
 * 高度越界返回 {@code 0}（空气）。未加载在推演中必须显式暴露为 UNKNOWN。</p>
 */
public final class BlockWorldQuery implements FlightWorldQuery {

    /** 未加载区块的状态哨兵（与 RunningWorld.getBlockStateId 的返回约定一致） */
    private static final int UNLOADED = -1;

    private final RunningWorld world;
    private final BlockNameTable blocks;

    public BlockWorldQuery(RunningWorld world, BlockNameTable blocks) {
        this.world = world;
        this.blocks = blocks;
    }

    @Override
    public boolean isLoaded(int x, int y, int z) {
        // 高度越界会返回 AIR(0) 而非 -1：世界外也是"已知的空"；未加载区块才不可信
        return !isUnloaded(x, y, z);
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        int state = world.getBlockStateId(x, y, z);
        if (state == UNLOADED || state == 0) {
            return false;
        }
        if (blocks.fluidType(state) != BlockNameTable.FLUID_NONE) {
            return false; // 流体不阻挡滑翔碰撞盒（触水另按 isWater 判停）
        }
        if (isDangerous(state)) {
            return false; // 火/岩浆无实体碰撞，撞上按危险单独判，不算"墙"
        }
        return blocks.blocksMotion(state);
    }

    @Override
    public boolean isStandable(int x, int y, int z) {
        int state = world.getBlockStateId(x, y, z);
        if (state == UNLOADED || state == 0) {
            return false;
        }
        if (blocks.fluidType(state) != BlockNameTable.FLUID_NONE) {
            return false;
        }
        if (isDangerous(state)) {
            return false;
        }
        return blocks.hasCollision(state);
    }

    @Override
    public boolean isWater(int x, int y, int z) {
        int state = world.getBlockStateId(x, y, z);
        return state != UNLOADED && blocks.fluidType(state) == BlockNameTable.FLUID_WATER;
    }

    @Override
    public boolean isLava(int x, int y, int z) {
        int state = world.getBlockStateId(x, y, z);
        return state != UNLOADED && (blocks.fluidType(state) == BlockNameTable.FLUID_LAVA
                || isDangerousName(blocks.nameOf(state)));
    }

    /** 查询该格是否未加载（推演遇到即整条弹道判 UNKNOWN，杜绝盲飞越界）。 */
    public boolean isUnloaded(int x, int y, int z) {
        return world.getBlockStateId(x, y, z) == UNLOADED;
    }

    @Override
    public int minY() {
        return world.getMinY();
    }

    @Override
    public int maxY() {
        return world.getMinY() + world.getHeight() - 1;
    }

    private boolean isDangerous(int state) {
        if (blocks.fluidType(state) == BlockNameTable.FLUID_LAVA) {
            return true;
        }
        return isDangerousName(blocks.nameOf(state));
    }

    /** 危险名称启发式（与 {@code RunningPathWorld.isDangerousName} 同一集合）。 */
    private static boolean isDangerousName(String name) {
        if (name == null) {
            return false;
        }
        return name.contains("fire") || name.contains("lava") || name.contains("magma");
    }
}
