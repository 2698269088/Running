/*
 * 本文件移植自 Baritone（LGPL-3.0）的 baritone.api.pathing.goals.GoalComposite。
 */
package top.mcocet.running.pathfinding;

import java.util.Arrays;

/**
 * 多个目标的"或"组合：满足其中任一即视为达成，启发式取最近者（最小值）。
 * 移植自 Baritone（avecowa）。
 */
public class GoalComposite implements Goal {

    private final Goal[] goals;

    public GoalComposite(Goal... goals) {
        this.goals = goals;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        for (Goal goal : goals) {
            if (goal.isInGoal(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double min = Double.MAX_VALUE;
        for (Goal g : goals) {
            min = Math.min(min, g.heuristic(x, y, z));
        }
        return min;
    }

    @Override
    public double heuristic() {
        double min = Double.MAX_VALUE;
        for (Goal g : goals) {
            min = Math.min(min, g.heuristic());
        }
        return min;
    }

    public Goal[] goals() {
        return goals;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GoalComposite)) {
            return false;
        }
        return Arrays.equals(goals, ((GoalComposite) o).goals);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(goals);
    }

    @Override
    public String toString() {
        return "GoalComposite" + Arrays.toString(goals);
    }
}
