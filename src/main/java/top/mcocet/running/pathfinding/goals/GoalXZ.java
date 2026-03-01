package top.mcocet.running.pathfinding.goals;

import org.joml.Vector3d;

public class GoalXZ implements Goal {
    private static final double SQRT_2 = Math.sqrt(2);
    private final int x, z;
    
    public GoalXZ(int x, int z) {
        this.x = x;
        this.z = z;
    }
    
    public GoalXZ(Vector3d pos) {
        this((int) Math.floor(pos.x), (int) Math.floor(pos.z));
    }
    
    @Override
    public boolean isInGoal(int x, int y, int z) {
        return x == this.x && z == this.z;
    }
    
    @Override
    public boolean isInGoal(Vector3d pos) {
        return isInGoal((int) Math.floor(pos.x), 0, (int) Math.floor(pos.z));
    }
    
    @Override
    public double heuristic(int x, int y, int z) {
        int xDiff = Math.abs(x - this.x);
        int zDiff = Math.abs(z - this.z);
        return calculate(xDiff, zDiff);
    }
    
    public static double calculate(double xDiff, double zDiff) {
        double x = Math.abs(xDiff);
        double z = Math.abs(zDiff);
        double straight, diagonal;
        
        if (x < z) {
            straight = z - x;
            diagonal = x;
        } else {
            straight = x - z;
            diagonal = z;
        }
        
        diagonal *= SQRT_2;
        return diagonal + straight; // Cost multiplier can be added here
    }
    
    public int getX() { return x; }
    public int getZ() { return z; }
    
    @Override
    public String toString() {
        return String.format("GoalXZ{x=%d, z=%d}", x, z);
    }
}