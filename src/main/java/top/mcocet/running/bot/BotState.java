package top.mcocet.running.bot;

/**
 * 机器人运动状态：位置、朝向、速度、着地状态等（只读镜像）。
 *
 * <p>权威数据由移动引擎线程上的 {@link PlayerPhysics} 推进，每个 tick 通过
 * {@link #applyPhysics} 一次性镜像到本对象（服务器传送引起的重置同样经由
 * 物理层完成）；除此之外仅 {@link #setEntityId} 接受外部写入。命令线程与
 * 监听器只读取，内部通过同一把锁保证读写一致性。</p>
 */
public final class BotState {
    private final Object lock = new Object();

    private double x;
    private double y;
    private double z;

    private float yaw;
    private float pitch;

    private boolean onGround = true;
    private boolean horizontalCollision;
    private boolean sprinting;
    private boolean positioned;
    private int entityId = -1;

    /** 速度（方块/tick），由物理引擎写入 */
    private double velocityX;
    private double velocityY;
    private double velocityZ;

    public void setEntityId(int entityId) {
        synchronized (lock) {
            this.entityId = entityId;
        }
    }

    /**
     * 批量写入物理模拟结果（由 {@link PlayerPhysics} 每个 tick 调用一次），
     * 单次加锁保证快照一致性。
     */
    public void applyPhysics(double x, double y, double z, float yaw, float pitch,
                             double velocityX, double velocityY, double velocityZ,
                             boolean onGround, boolean horizontalCollision, boolean sprinting) {
        synchronized (lock) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.velocityX = velocityX;
            this.velocityY = velocityY;
            this.velocityZ = velocityZ;
            this.onGround = onGround;
            this.horizontalCollision = horizontalCollision;
            this.sprinting = sprinting;
            this.positioned = true;
        }
    }

    public double getX() {
        synchronized (lock) {
            return x;
        }
    }

    public double getY() {
        synchronized (lock) {
            return y;
        }
    }

    public double getZ() {
        synchronized (lock) {
            return z;
        }
    }

    public float getYaw() {
        synchronized (lock) {
            return yaw;
        }
    }

    public float getPitch() {
        synchronized (lock) {
            return pitch;
        }
    }

    public boolean isOnGround() {
        synchronized (lock) {
            return onGround;
        }
    }

    /** 是否已收到服务器下发的初始位置 */
    public boolean hasPosition() {
        synchronized (lock) {
            return positioned;
        }
    }

    public int getEntityId() {
        synchronized (lock) {
            return entityId;
        }
    }

    public double getVelocityX() {
        synchronized (lock) {
            return velocityX;
        }
    }

    public double getVelocityY() {
        synchronized (lock) {
            return velocityY;
        }
    }

    public double getVelocityZ() {
        synchronized (lock) {
            return velocityZ;
        }
    }

    /** 本 tick 是否与方块/实体发生水平碰撞（官方位置包字段之一） */
    public boolean isHorizontalCollision() {
        synchronized (lock) {
            return horizontalCollision;
        }
    }

    /** 是否处于冲刺状态（由物理引擎维护） */
    public boolean isSprinting() {
        synchronized (lock) {
            return sprinting;
        }
    }

    /** 在同一把锁内读取全部运动字段，保证快照的一致性 */
    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(x, y, z, yaw, pitch, onGround, horizontalCollision,
                    velocityX, velocityY, velocityZ, sprinting);
        }
    }

    /** 运动状态快照 */
    public record Snapshot(double x, double y, double z, float yaw, float pitch,
                           boolean onGround, boolean horizontalCollision,
                           double velocityX, double velocityY, double velocityZ,
                           boolean sprinting) {
    }
}
