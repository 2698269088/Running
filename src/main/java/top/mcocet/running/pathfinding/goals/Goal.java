package top.mcocet.running.pathfinding.goals;

import org.joml.Vector3d;
import top.mcocet.running.pathfinding.core.PathNode;

public interface Goal {
    boolean isInGoal(int x, int y, int z);
    boolean isInGoal(Vector3d pos);
    double heuristic(int x, int y, int z);
    
    default Vector3d getTargetPosition() {
        return null; // 默认实现返回null，具体目标类可以覆盖此方法
    }
    
    default boolean isInGoal(PathNode node) {
        return isInGoal(node.getX(), node.getY(), node.getZ());
    }
    
    default double heuristic(PathNode node) {
        return heuristic(node.getX(), node.getY(), node.getZ());
    }
    
    default double heuristic(Vector3d pos) {
        return heuristic((int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));
    }
}