package top.mcocet.running.pathfinding;

import java.util.HashMap;
import java.util.Map;

/**
 * 回溯惩罚与避让区域系统，移植自 Baritone 的 {@code Favoring}。
 *
 * <p>在 A* 搜索时对"上一次路径经过的节点"施加代价乘数惩罚
 * （{@code backtrackCostFavoringCoefficient}），避免新路径与旧路径重叠导致来回抖动。
 * 还支持球形区域避让（如避开怪物）。</p>
 *
 * <p>实现方式：用 {@link Map} 存储每个位置哈希的代价乘数，默认值为 1.0（无惩罚）。
 * 搜索时若节点在惩罚表中，则将其移动代价乘以对应系数。</p>
 */
public final class Favoring {

    /** 默认值：无惩罚 */
    private static final double DEFAULT_FAVOR = 1.0;

    /** 位置哈希 → 代价乘数 */
    private final Map<Long, Double> favorings;

    public Favoring() {
        this.favorings = new HashMap<>();
    }

    /**
     * 从旧路径构建回溯惩罚：旧路径上的每个节点都施加惩罚系数。
     *
     * @param previous 上一次的路径（可为 null）
     * @param backtrackCostFavoringCoefficient 回溯惩罚系数（通常 &gt;1，如 1.5）
     */
    public Favoring(Path previous, double backtrackCostFavoringCoefficient) {
        this();
        if (previous != null && backtrackCostFavoringCoefficient != 1.0) {
            for (Path.Step step : previous.steps()) {
                long hash = NodePos.longHash(step.dest.x, step.dest.y, step.dest.z);
                favorings.put(hash, backtrackCostFavoringCoefficient);
            }
        }
    }

    /**
     * 添加球形避让区域：以 (cx, cy, cz) 为中心、radius 为半径的球体内所有位置
     * 施加惩罚系数。
     *
     * @param cx 中心 X
     * @param cy 中心 Y
     * @param cz 中心 Z
     * @param radius 半径（格）
     * @param coefficient 惩罚系数（&gt;1 表示避让）
     */
    public void addAvoidanceSphere(int cx, int cy, int cz, double radius, double coefficient) {
        int r = (int) Math.ceil(radius);
        double radiusSq = radius * radius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq <= radiusSq) {
                        long hash = NodePos.longHash(cx + dx, cy + dy, cz + dz);
                        favorings.put(hash, coefficient);
                    }
                }
            }
        }
    }

    /**
     * 查询指定位置的代价乘数。
     *
     * @param x 方块 X
     * @param y 方块 Y
     * @param z 方块 Z
     * @return 代价乘数（默认 1.0）
     */
    public double calculate(int x, int y, int z) {
        long hash = NodePos.longHash(x, y, z);
        return favorings.getOrDefault(hash, DEFAULT_FAVOR);
    }

    /**
     * 查询指定位置哈希的代价乘数。
     *
     * @param hash 位置哈希（{@link NodePos#longHash}）
     * @return 代价乘数（默认 1.0）
     */
    public double calculate(long hash) {
        return favorings.getOrDefault(hash, DEFAULT_FAVOR);
    }

    /**
     * 是否为空（无任何惩罚）。
     */
    public boolean isEmpty() {
        return favorings.isEmpty();
    }
}
