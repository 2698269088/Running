/*
 * 本接口移植自 Baritone（LGPL-3.0）的 baritone.api.pathing.goals.Goal。
 */
package top.mcocet.running.pathfinding;

/**
 * 寻路目标：抽象为一个纯坐标谓词 + 启发式估计，无 Minecraft 依赖。
 *
 * @author 移植自 Baritone（leijurv）
 */
public interface Goal {

    /**
     * 指定方块坐标是否已满足本目标。
     */
    boolean isInGoal(int x, int y, int z);

    /**
     * 估计从 (x,y,z) 走到目标所需的 tick 数（A* 启发式）。
     */
    double heuristic(int x, int y, int z);

    /**
     * 目标已满足时的启发式下界（某些目标在 {@link #isInGoal} 为真时启发式并非 0）。
     */
    default double heuristic() {
        return 0;
    }

    default boolean isInGoal(NodePos pos) {
        return isInGoal(pos.x, pos.y, pos.z);
    }

    default double heuristic(NodePos pos) {
        return heuristic(pos.x, pos.y, pos.z);
    }
}
