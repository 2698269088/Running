package top.mcocet.running.math;

/**
 * 不可变三维向量，对齐 Minecraft {@code net.minecraft.world.phys.Vec3} 的常用操作。
 *
 * <p>仅保留移动物理（速度、位移、碰撞解算）所需的最小集合；
 * 所有运算返回新实例，实例本身天然线程安全。</p>
 */
public final class Vec3 {
    public static final Vec3 ZERO = new Vec3(0.0, 0.0, 0.0);

    public final double x;
    public final double y;
    public final double z;

    public Vec3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vec3 add(Vec3 other) {
        return new Vec3(x + other.x, y + other.y, z + other.z);
    }

    public Vec3 add(double dx, double dy, double dz) {
        return new Vec3(x + dx, y + dy, z + dz);
    }

    public Vec3 subtract(Vec3 other) {
        return new Vec3(x - other.x, y - other.y, z - other.z);
    }

    public Vec3 subtract(double dx, double dy, double dz) {
        return new Vec3(x - dx, y - dy, z - dz);
    }

    /** 逐分量乘法（对齐 MC {@code Vec3.multiply}） */
    public Vec3 multiply(double mx, double my, double mz) {
        return new Vec3(x * mx, y * my, z * mz);
    }

    public Vec3 scale(double factor) {
        return new Vec3(x * factor, y * factor, z * factor);
    }

    /** 水平分量（x/z）长度，对齐 MC {@code horizontalDistance} */
    public double horizontalDistance() {
        return Math.sqrt(horizontalDistanceSqr());
    }

    public double horizontalDistanceSqr() {
        return x * x + z * z;
    }

    public double length() {
        return Math.sqrt(lengthSqr());
    }

    public double lengthSqr() {
        return x * x + y * y + z * z;
    }

    public double distanceToSqr(Vec3 other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distanceTo(Vec3 other) {
        return Math.sqrt(distanceToSqr(other));
    }

    /** 单位化；长度小于 1.0E-4 时返回 {@link #ZERO}（对齐 MC Vec3.normalize） */
    public Vec3 normalize() {
        double len = length();
        return len < 1.0E-4 ? ZERO : scale(1.0 / len);
    }

    public boolean isZero() {
        return x == 0.0 && y == 0.0 && z == 0.0;
    }

    /**
     * 绕 X 轴旋转（弧度制），严格对齐 MC {@code Vec3.xRot}：
     * {@code y' = y·cos(a) + z·sin(a)}，{@code z' = z·cos(a) - y·sin(a)}。
     * 三角函数与官方一致按 float 精度计算后再参与 double 运算。
     */
    public Vec3 xRot(float radians) {
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        return new Vec3(x, y * cos + z * sin, z * cos - y * sin);
    }

    /**
     * 绕 Y 轴旋转（弧度制），严格对齐 MC {@code Vec3.yRot}：
     * {@code x' = x·cos(a) + z·sin(a)}，{@code z' = z·cos(a) - x·sin(a)}。
     */
    public Vec3 yRot(float radians) {
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        return new Vec3(x * cos + z * sin, y, z * cos - x * sin);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Vec3 other)) return false;
        return Double.compare(x, other.x) == 0
                && Double.compare(y, other.y) == 0
                && Double.compare(z, other.z) == 0;
    }

    @Override
    public int hashCode() {
        int result = Double.hashCode(x);
        result = 31 * result + Double.hashCode(y);
        result = 31 * result + Double.hashCode(z);
        return result;
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
