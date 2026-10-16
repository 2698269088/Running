package top.mcocet.running.math;

/**
 * 轴对齐包围盒（AABB），对齐 Minecraft {@code net.minecraft.world.phys.AABB} 的常用操作。
 *
 * <p>用于玩家碰撞盒建模与方块碰撞盒收集，只保留移动物理需要的最小子集。</p>
 */
public final class AABB {
    public final double minX;
    public final double minY;
    public final double minZ;
    public final double maxX;
    public final double maxY;
    public final double maxZ;

    public AABB(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this.minX = Math.min(minX, maxX);
        this.minY = Math.min(minY, maxY);
        this.minZ = Math.min(minZ, maxZ);
        this.maxX = Math.max(minX, maxX);
        this.maxY = Math.max(minY, maxY);
        this.maxZ = Math.max(minZ, maxZ);
    }

    /**
     * 按实体尺寸构建碰撞盒（对齐 MC {@code EntityDimensions.makeBoundingBox}）：
     * 以 (x, y, z) 为脚底中心，宽 width、高 height。
     */
    public static AABB ofEntity(double x, double y, double z, double width, double height) {
        double half = width / 2.0;
        return new AABB(x - half, y, z - half, x + half, y + height, z + half);
    }

    public AABB move(double dx, double dy, double dz) {
        return new AABB(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    public AABB move(Vec3 delta) {
        return move(delta.x, delta.y, delta.z);
    }

    /** 各方向扩张（负值收缩），对齐 MC {@code AABB.inflate} */
    public AABB inflate(double dx, double dy, double dz) {
        return new AABB(minX - dx, minY - dy, minZ - dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    /** 各方向收缩（对齐 MC {@code AABB.deflate}；负值扩张） */
    public AABB deflate(double dx, double dy, double dz) {
        return inflate(-dx, -dy, -dz);
    }

    /** 向移动方向扩张（对齐 MC {@code AABB.expandTowards}） */
    public AABB expandTowards(double dx, double dy, double dz) {
        double newMinX = dx < 0.0 ? minX + dx : minX;
        double newMinY = dy < 0.0 ? minY + dy : minY;
        double newMinZ = dz < 0.0 ? minZ + dz : minZ;
        double newMaxX = dx > 0.0 ? maxX + dx : maxX;
        double newMaxY = dy > 0.0 ? maxY + dy : maxY;
        double newMaxZ = dz > 0.0 ? maxZ + dz : maxZ;
        return new AABB(newMinX, newMinY, newMinZ, newMaxX, newMaxY, newMaxZ);
    }

    public AABB expandTowards(Vec3 delta) {
        return expandTowards(delta.x, delta.y, delta.z);
    }

    public double getXsize() {
        return maxX - minX;
    }

    public double getYsize() {
        return maxY - minY;
    }

    public double getZsize() {
        return maxZ - minZ;
    }

    /** 两包围盒是否相交（对齐 MC {@code AABB.intersects}，边贴边不算相交） */
    public boolean intersects(AABB other) {
        return maxX > other.minX && minX < other.maxX
                && maxY > other.minY && minY < other.maxY
                && maxZ > other.minZ && minZ < other.maxZ;
    }

    @Override
    public String toString() {
        return "AABB[" + minX + ", " + minY + ", " + minZ + "] -> [" + maxX + ", " + maxY + ", " + maxZ + "]";
    }
}
