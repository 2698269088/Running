package top.mcocet.running.pathfinding.goals;

import org.joml.Vector3d;

public class GoalBlock implements Goal {
    private final int x, y, z;
    
    public GoalBlock(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }
    
    public GoalBlock(Vector3d pos) {
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
        return Math.abs(x - this.x) + Math.abs(y - this.y) + Math.abs(z - this.z);
    }
    
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    
    @Override
    public String toString() {
        return String.format("GoalBlock{x=%d, y=%d, z=%d}", x, y, z);
    }
}