package top.mcocet.running.pathfinding.goals;

import org.joml.Vector3d;

/**
 * 坐标目标类 - 表示要到达的具体坐标位置
 */
public class CoordinateGoal implements Goal {
    private final int x, y, z;
    
    public CoordinateGoal(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }
    
    public CoordinateGoal(Vector3d pos) {
        this((int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));
    }
    
    @Override
    public boolean isInGoal(int x, int y, int z) {
        return x == this.x && y == this.y && z == this.z;
    }
    
    @Override
    public boolean isInGoal(Vector3d pos) {
        return isInGoal((int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));
    }
    
    @Override
    public double heuristic(int x, int y, int z) {
        // 使用欧几里得距离作为启发式函数，更加准确
        double dx = x - this.x;
        double dy = y - this.y;
        double dz = z - this.z;
        
        double euclideanDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        
        // 确保返回值在合理范围内
        if (Double.isNaN(euclideanDistance) || Double.isInfinite(euclideanDistance)) {
            return 1000000; // 返回一个很大的但有限的值
        }
        
        return Math.max(0, euclideanDistance);
    }
    
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    
    @Override
    public Vector3d getTargetPosition() {
        return new Vector3d(x, y, z);
    }
    
    @Override
    public String toString() {
        return String.format("CoordinateGoal{x=%d, y=%d, z=%d}", x, y, z);
    }
}