package top.mcocet.running.utils;

import org.joml.Vector3d;

/**
 * 优化的方块位置类，借鉴Baritone的设计
 * 提供更高效的哈希计算和位置操作
 */
public class BetterBlockPos {
    private static final int NUM_X_BITS = 26;
    private static final int NUM_Z_BITS = NUM_X_BITS;
    private static final int NUM_Y_BITS = 64 - NUM_X_BITS - NUM_Z_BITS;
    
    public final int x;
    public final int y;
    public final int z;
    
    public static final BetterBlockPos ORIGIN = new BetterBlockPos(0, 0, 0);
    
    public BetterBlockPos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }
    
    public BetterBlockPos(Vector3d vec) {
        this((int) Math.floor(vec.x), (int) Math.floor(vec.y), (int) Math.floor(vec.z));
    }
    
    public BetterBlockPos(double x, double y, double z) {
        this((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }
    
    /**
     * 高效的哈希计算，避免标准BlockPos的冲突问题
     */
    public static long longHash(int x, int y, int z) {
        long hash = 3241;
        hash = 3457689L * hash + x;
        hash = 8734625L * hash + y;
        hash = 2873465L * hash + z;
        return hash;
    }
    
    public long longHash() {
        return longHash(x, y, z);
    }
    
    @Override
    public int hashCode() {
        return (int) longHash(x, y, z);
    }
    
    @Override
    public boolean equals(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof BetterBlockPos) {
            BetterBlockPos other = (BetterBlockPos) o;
            return other.x == x && other.y == y && other.z == z;
        }
        return false;
    }
    
    /**
     * 快速向上移动
     */
    public BetterBlockPos up() {
        return new BetterBlockPos(x, y + 1, z);
    }
    
    /**
     * 快速向下移动
     */
    public BetterBlockPos down() {
        return new BetterBlockPos(x, y - 1, z);
    }
    
    /**
     * 快速向北移动（负Z方向）
     */
    public BetterBlockPos north() {
        return new BetterBlockPos(x, y, z - 1);
    }
    
    /**
     * 快速向南移动（正Z方向）
     */
    public BetterBlockPos south() {
        return new BetterBlockPos(x, y, z + 1);
    }
    
    /**
     * 快速向西移动（负X方向）
     */
    public BetterBlockPos west() {
        return new BetterBlockPos(x - 1, y, z);
    }
    
    /**
     * 快速向东移动（正X方向）
     */
    public BetterBlockPos east() {
        return new BetterBlockPos(x + 1, y, z);
    }
    
    /**
     * 计算到另一个位置的距离平方
     */
    public double distanceSq(BetterBlockPos other) {
        int dx = this.x - other.x;
        int dy = this.y - other.y;
        int dz = this.z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
    
    /**
     * 计算到另一个位置的距离
     */
    public double distance(BetterBlockPos other) {
        return Math.sqrt(distanceSq(other));
    }
    
    /**
     * 转换为Vector3d
     */
    public Vector3d toVector3d() {
        return new Vector3d(x, y, z);
    }
    
    /**
     * 获取中心位置（方块中心点）
     */
    public Vector3d getCenter() {
        return new Vector3d(x + 0.5, y + 0.5, z + 0.5);
    }
    
    @Override
    public String toString() {
        return String.format("BetterBlockPos{x=%d, y=%d, z=%d}", x, y, z);
    }
    
    /**
     * 检查是否在指定范围内
     */
    public boolean isWithinDistance(BetterBlockPos center, double distance) {
        return distanceSq(center) <= distance * distance;
    }
    
    /**
     * 获取相邻位置
     */
    public BetterBlockPos[] getNeighbors() {
        return new BetterBlockPos[] {
            north(), south(), east(), west(), up(), down()
        };
    }
}