/*
 * 本文件移植自 Baritone（LGPL-3.0）的 baritone.utils.pathing.MutableMoveResult。
 */
package top.mcocet.running.pathfinding;

/**
 * 一次移动计算的可变得结果：落点坐标与代价。移植自 Baritone（leijurv）。
 *
 * <p>复用同一实例以避免在每个搜索节点上分配新对象；{@link #reset()} 将代价预置为
 * {@link MoveCosts#COST_INF}，各 {@link Moves} 在可行时才覆盖落点与代价。</p>
 */
public final class MutableMoveResult {

    public int x;
    public int y;
    public int z;
    public double cost;

    public MutableMoveResult() {
        reset();
    }

    public final void reset() {
        x = 0;
        y = 0;
        z = 0;
        cost = MoveCosts.COST_INF;
    }
}
