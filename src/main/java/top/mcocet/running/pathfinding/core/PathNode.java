package top.mcocet.running.pathfinding.core;

import lombok.Data;
import org.joml.Vector3d;
import top.mcocet.running.utils.BetterBlockPos;

@Data
public class PathNode {
    private final Vector3d position;
    private final BetterBlockPos betterPos; // 添加BetterBlockPos缓存
    private final int x, y, z;
    private double cost; // g-cost: actual cost from start
    private double estimatedCostToGoal; // h-cost: heuristic estimate to goal
    private double combinedCost; // f-cost: g + h
    private PathNode previous;
    private MoveType moveType;
    
    public PathNode(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.position = new Vector3d(x, y, z);
        this.betterPos = new BetterBlockPos(x, y, z); // 初始化BetterBlockPos
        this.cost = Double.POSITIVE_INFINITY;
        this.estimatedCostToGoal = 0;
        this.combinedCost = Double.POSITIVE_INFINITY;
        this.previous = null;
        this.moveType = MoveType.WALK;
    }
    
    public PathNode(Vector3d pos) {
        this((int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));
    }
    
    public boolean isOpen() {
        return combinedCost < Double.POSITIVE_INFINITY;
    }
    
    public long getPositionHash() {
        // 使用BetterBlockPos的高效哈希算法
        return BetterBlockPos.longHash(x, y, z);
    }
    
    public BetterBlockPos getBetterPos() {
        return betterPos;
    }
    
    /**
     * 计算到另一个节点的距离平方
     */
    public double distanceSquared(PathNode other) {
        return betterPos.distanceSq(other.betterPos);
    }
    
    /**
     * 计算到另一个节点的距离
     */
    public double distance(PathNode other) {
        return betterPos.distance(other.betterPos);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        PathNode pathNode = (PathNode) obj;
        return x == pathNode.x && y == pathNode.y && z == pathNode.z;
    }
    
        @Override
    public int hashCode() {
        return (int) getPositionHash();
    }
    
    // 显式添加getter方法
    public Vector3d getPosition() {
        return position;
    }
    
    public int getX() {
        return x;
    }
    
    public int getY() {
        return y;
    }
    
    public int getZ() {
        return z;
    }
    
    // 添加缺失的getter方法
    public double getCost() {
        return cost;
    }
    
    public double getEstimatedCostToGoal() {
        return estimatedCostToGoal;
    }
    
    public double getCombinedCost() {
        return combinedCost;
    }
    
    public PathNode getPrevious() {
        return previous;
    }
    
    // 显式添加setter方法
    public void setCost(double cost) {
        this.cost = cost;
    }
    
    public void setEstimatedCostToGoal(double estimatedCostToGoal) {
        this.estimatedCostToGoal = estimatedCostToGoal;
    }
    
    public void setCombinedCost(double combinedCost) {
        this.combinedCost = combinedCost;
    }
    
    public void setPrevious(PathNode previous) {
        this.previous = previous;
    }
    
    public enum MoveType {
        WALK,
        JUMP,
        FALL,
        PARKOUR,
        BREAK_BLOCK,
        PLACE_BLOCK
    }
}