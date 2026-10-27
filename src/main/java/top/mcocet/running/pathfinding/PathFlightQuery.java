package top.mcocet.running.pathfinding;

import top.mcocet.running.flight.FlightWorldQuery;

/**
 * {@link FlightWorldQuery} 到 {@link PathWorld} 的适配器：让 {@code PathingGoal}
 * 的滑翔巡航段复用与 A* 计算完全同一套方块语义（通行/可站/水/岩浆判定），
 * 保证"规划认为安全的弹道"与"执行期每 tick 推演的弹道"判断一致。
 *
 * <p>{@link PathWorld#isPassable} 对未加载区块返回 {@code false}，与水相同；
 * 因此 {@link #isLoaded} 单独用 {@link PathWorld#stateAt} 的 {@code -1} 哨兵判定，
 * 让弹道推演在越出已加载区时正确暴露 UNKNOWN 而不是误判"可飞"。</p>
 */
public final class PathFlightQuery implements FlightWorldQuery {

    private final PathWorld world;

    public PathFlightQuery(PathWorld world) {
        this.world = world;
    }

    @Override
    public boolean isLoaded(int x, int y, int z) {
        return world.stateAt(x, y, z) != PathWorld.UNLOADED;
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        if (!isLoaded(x, y, z)) {
            return false; // 未加载由 isLoaded 暴露为 UNKNOWN，不当作实体
        }
        if (world.isWater(x, y, z) || world.isLava(x, y, z)) {
            return false; // 流体另行分类（水=停、岩浆=致命），不算撞墙
        }
        return !world.isPassable(x, y, z);
    }

    @Override
    public boolean isStandable(int x, int y, int z) {
        return isLoaded(x, y, z) && world.isStandable(x, y, z);
    }

    @Override
    public boolean isWater(int x, int y, int z) {
        return world.isWater(x, y, z);
    }

    @Override
    public boolean isLava(int x, int y, int z) {
        return world.isLava(x, y, z);
    }

    @Override
    public int minY() {
        return world.minY();
    }

    @Override
    public int maxY() {
        return world.maxY();
    }
}
