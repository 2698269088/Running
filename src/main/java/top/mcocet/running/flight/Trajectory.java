package top.mcocet.running.flight;

import top.mcocet.running.math.Vec3;

/**
 * 一条候选飞行动作的<b>纯物理向前推演结果</b>（思路移植自 MovementSync 1.11 的
 * {@code FlightTrajectory} + {@code FlightHazards}：预测只查询世界，绝不改变
 * 活体状态；执行留给调用方）。
 *
 * <p>推演模型（与 {@code PlayerPhysics} 权威物理同源，公式见 {@link FlightPhysics}）：</p>
 * <ul>
 *   <li>逐 tick 步进：火箭候选先叠加 {@link FlightPhysics#rocketStep} 附着冲量，
 *       再走 {@link FlightPhysics#glideStep}；视线（yaw/pitch）保持候选动作值不变；</li>
 *   <li>碰撞按滑翔姿势碰撞盒（0.6 宽 × 0.6 高、脚底中心为原点）逐轴解算，
 *       Y 轴优先（对齐官方 {@code Entity.axisStepOrder}）；水平撞实心即判
 *       撞壁停止并按官方 {@code (lastH − newH) × 10 − 3} 累计撞墙伤害；</li>
 *   <li>摔落距离记账对齐官方：下落累计，垂直速度浅于 −0.5 时封顶 1.0
 *       （{@code Entity.checkFallDistanceAccumulation}），因此正常浅滑翔着陆免伤，
 *       陡降/俯冲着陆会累计真实落差并结算 {@code floor(fall − 3)} 落伤；</li>
 *   <li>触水即停（安全但飞行中断，{@link Outcome#WATER}）；触岩浆/火判致命；
 *       掠到未加载区块整条判 {@link Outcome#UNKNOWN}——<b>未知永远不被接受</b>
 *       （MS 的 "never approve blind low flight" 原则）。</li>
 * </ul>
 */
public final class Trajectory {

    /** 推演终止类型 */
    public enum Outcome {
        /** 推演步数耗尽，末端仍在空中（弹道可继续，代价按末端对目标的推进评估） */
        AIRBORNE,
        /** 落到可站立台面（正常着陆） */
        LANDED,
        /** 水平撞壁停止（末端贴墙，位置未必可站） */
        BLOCKED,
        /** 触水停止：安全但滑翔/飞行中断 */
        WATER,
        /** 触岩浆/火：致命 */
        HAZARD,
        /** 掠到未加载区块：未知，选择器必须拒绝 */
        UNKNOWN
    }

    /** 滑翔姿势碰撞盒半宽（PlayerPhysics.PLAYER_WIDTH / 2） */
    private static final double HALF_WIDTH = 0.3;
    /** 滑翔姿势碰撞盒高度（PlayerPhysics.GLIDING_HEIGHT） */
    private static final double GLIDE_HEIGHT = 0.6;
    /** 撞墙伤害公式（LivingEntity.handleFallFlyingCollisions：速度损失 ×10 − 3） */
    private static final double WALL_DAMAGE_SCALE = 10.0;
    private static final double WALL_DAMAGE_BASE = 3.0;
    /** 安全摔落高度（Attributes.SAFE_FALL_DISTANCE 默认） */
    private static final double SAFE_FALL_DISTANCE = 3.0;
    /** 摔落距离封顶阈值（checkFallDistanceAccumulation：vy > −0.5 时封顶 1.0） */
    private static final double FALL_CAP_SPEED = -0.5;
    private static final double FALL_CAP_DISTANCE = 1.0;

    public final Outcome outcome;
    /** 推演 tick 数 */
    public final int ticks;
    /** 末端位置与速度 */
    public final double endX, endY, endZ, endVx, endVy, endVz;
    /** 着陆点（仅 {@link Outcome#LANDED} 有效，脚底坐标） */
    public final double landX, landY, landZ;
    /** 累计撞墙伤害（减免前） */
    public final double wallDamage;
    /** 着陆摔落伤害（未着陆为 0） */
    public final double fallDamage;
    /** 是否消耗了火箭（供代价计火箭预算） */
    public final boolean usedFirework;

    private Trajectory(Outcome outcome, int ticks,
                       double endX, double endY, double endZ,
                       double endVx, double endVy, double endVz,
                       double landX, double landY, double landZ,
                       double wallDamage, double fallDamage, boolean usedFirework) {
        this.outcome = outcome;
        this.ticks = ticks;
        this.endX = endX;
        this.endY = endY;
        this.endZ = endZ;
        this.endVx = endVx;
        this.endVy = endVy;
        this.endVz = endVz;
        this.landX = landX;
        this.landY = landY;
        this.landZ = landZ;
        this.wallDamage = wallDamage;
        this.fallDamage = fallDamage;
        this.usedFirework = usedFirework;
    }

    /** 总伤害（减免前近似等于减免后：本插件无盔甲/抗性建模，保守计）。 */
    public double totalDamage() {
        return wallDamage + fallDamage;
    }

    /**
     * 从给定物理状态向前推演一条滑翔弹道。
     *
     * @param query      世界查询（只读；未加载区块出现即判 UNKNOWN）
     * @param x, y, z    起始脚底中心坐标
     * @param vx, vy, vz 起始速度（方块/tick）
     * @param fallDist   起始累计摔落距离（滑翔中通常已被封顶到 1.0）
     * @param yaw        候选偏航（度）
     * @param pitch      候选俯仰（度；负值抬头）
     * @param firework   候选是否挂火箭（附着窗口内逐 tick 近似持续加速）
     * @param gravity    有效重力
     * @param worldMinY  世界最低高度（触底仍未着陆 → UNKNOWN）
     * @param maxTicks   推演步数上限
     */
    public static Trajectory predict(FlightWorldQuery query,
                                     double x, double y, double z,
                                     double vx, double vy, double vz,
                                     double fallDist,
                                     float yaw, float pitch, boolean firework,
                                     double gravity, int worldMinY, int maxTicks) {
        double wallDamage = 0.0;
        double fall = fallDist;
        int t = 0;
        for (; t < maxTicks; t++) {
            Vec3 motion = new Vec3(vx, vy, vz);
            if (firework) {
                motion = FlightPhysics.rocketStep(motion, yaw, pitch);
            }
            motion = FlightPhysics.glideStep(motion, yaw, pitch, gravity);
            vx = motion.x;
            vy = motion.y;
            vz = motion.z;

            // Y 轴优先（对齐 Entity.axisStepOrder 与本物理层的滑翔解算顺序）
            double dy = vy;
            if (dy != 0.0) {
                double ny = y + dy;
                int probe = probeBox(query, x, ny, z);
                if ((probe & FLAG_UNLOADED) != 0) {
                    return unknown(t, x, y, z, vx, vy, vz, wallDamage, firework);
                }
                if ((probe & FLAG_HAZARD) != 0) {
                    return finish(Outcome.HAZARD, t + 1, x, ny, z, vx, 0, vz,
                            0, 0, 0, wallDamage, Double.MAX_VALUE, firework);
                }
                if ((probe & FLAG_WATER) != 0) {
                    return finish(Outcome.WATER, t + 1, x, ny, z, vx, 0, vz,
                            0, 0, 0, wallDamage, 0, firework);
                }
                if ((probe & FLAG_SOLID) != 0) {
                    if (dy < 0.0) {
                        // 下落碰撞 → 着陆：脚底吸附到碰撞列的顶面
                        double landY = Math.floor(ny) + 1.0;
                        boolean onSurface = isStandableBeneath(query, x, landY, z);
                        if (!onSurface) {
                            // 侧面贴住（碰撞盒扫到整叠方块）：按撞壁处理更安全
                            return finish(Outcome.BLOCKED, t + 1, x, landY, z, 0, 0, vz,
                                    0, 0, 0, wallDamage, 0, firework);
                        }
                        double fallDamage = Math.max(0.0,
                                Math.floor((fall + 1.0E-6 - SAFE_FALL_DISTANCE)));
                        return finish(Outcome.LANDED, t + 1, x, landY, z, vx, 0, vz,
                                x, landY, z, wallDamage, fallDamage, firework);
                    }
                    // 上升碰撞（顶棚）：垂直速度归零，继续下一轴
                    vy = 0.0;
                } else {
                    y = ny;
                    if (dy < 0.0) {
                        fall += -dy;
                        if (vy > FALL_CAP_SPEED) {
                            fall = Math.min(fall, FALL_CAP_DISTANCE);
                        }
                    } else {
                        fall = Math.max(0.0, fall - dy);
                    }
                }
                if (y <= worldMinY) {
                    return unknown(t + 1, x, y, z, vx, vy, vz, wallDamage, firework);
                }
            }

            // X 轴
            double dx = vx;
            if (dx != 0.0) {
                double nx = x + dx;
                int probe = probeBox(query, nx, y, z);
                if ((probe & FLAG_UNLOADED) != 0) {
                    return unknown(t + 1, x, y, z, vx, vy, vz, wallDamage, firework);
                }
                if ((probe & (FLAG_SOLID | FLAG_HAZARD | FLAG_WATER)) != 0) {
                    if ((probe & FLAG_HAZARD) != 0 || (probe & FLAG_WATER) != 0) {
                        Outcome o = (probe & FLAG_HAZARD) != 0 ? Outcome.HAZARD : Outcome.WATER;
                        return finish(o, t + 1, nx, y, z, 0, vy, vz, 0, 0, 0,
                                wallDamage, o == Outcome.HAZARD ? Double.MAX_VALUE : 0, firework);
                    }
                    // 撞壁停止：官方按速度损失结算伤害，预测保守取全部水平损失
                    double lost = Math.hypot(vx, vz);
                    double dmg = lost * WALL_DAMAGE_SCALE - WALL_DAMAGE_BASE;
                    if (dmg > 0.0) {
                        wallDamage += dmg;
                    }
                    return finish(Outcome.BLOCKED, t + 1, x, y, z, 0, vy, vz,
                            0, 0, 0, wallDamage, 0, firework);
                }
                x = nx;
            }

            // Z 轴
            double dz = vz;
            if (dz != 0.0) {
                double nz = z + dz;
                int probe = probeBox(query, x, y, nz);
                if ((probe & FLAG_UNLOADED) != 0) {
                    return unknown(t + 1, x, y, z, vx, vy, vz, wallDamage, firework);
                }
                if ((probe & (FLAG_SOLID | FLAG_HAZARD | FLAG_WATER)) != 0) {
                    if ((probe & FLAG_HAZARD) != 0 || (probe & FLAG_WATER) != 0) {
                        Outcome o = (probe & FLAG_HAZARD) != 0 ? Outcome.HAZARD : Outcome.WATER;
                        return finish(o, t + 1, x, y, nz, vx, vy, 0, 0, 0, 0,
                                wallDamage, o == Outcome.HAZARD ? Double.MAX_VALUE : 0, firework);
                    }
                    double lost = Math.hypot(vx, vz);
                    double dmg = lost * WALL_DAMAGE_SCALE - WALL_DAMAGE_BASE;
                    if (dmg > 0.0) {
                        wallDamage += dmg;
                    }
                    return finish(Outcome.BLOCKED, t + 1, x, y, z, vx, vy, 0,
                            0, 0, 0, wallDamage, 0, firework);
                }
                z = nz;
            }
        }
        // 步数耗尽仍在空中：弹道可延后重估，按末端位置参与排序
        return finish(Outcome.AIRBORNE, t, x, y, z, vx, vy, vz, 0, 0, 0,
                wallDamage, 0, firework);
    }

    // ------------------------------------------------------------------
    // 碰撞盒探测
    // ------------------------------------------------------------------

    private static final int FLAG_SOLID = 1;
    private static final int FLAG_UNLOADED = 2;
    private static final int FLAG_WATER = 4;
    private static final int FLAG_HAZARD = 8;

    /** 扫描滑翔碰撞盒覆盖的全部格子，返回标志位（任一格未加载即暴露 UNKNOWN）。 */
    private static int probeBox(FlightWorldQuery q, double x, double y, double z) {
        int flags = 0;
        int x0 = (int) Math.floor(x - HALF_WIDTH);
        int x1 = (int) Math.floor(x + HALF_WIDTH);
        int z0 = (int) Math.floor(z - HALF_WIDTH);
        int z1 = (int) Math.floor(z + HALF_WIDTH);
        int y0 = (int) Math.floor(y);
        int y1 = (int) Math.floor(y + GLIDE_HEIGHT - 1.0E-9);
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                for (int cy = y0; cy <= y1; cy++) {
                    if (!q.isLoaded(cx, cy, cz)) {
                        flags |= FLAG_UNLOADED;
                    } else if (q.isLava(cx, cy, cz)) {
                        flags |= FLAG_HAZARD;
                    } else if (q.isWater(cx, cy, cz)) {
                        flags |= FLAG_WATER;
                    } else if (q.isSolid(cx, cy, cz)) {
                        flags |= FLAG_SOLID;
                    }
                }
            }
        }
        return flags;
    }

    /** 着陆点可站判定：脚底正下方（水平中心所在格）为可站台面。 */
    private static boolean isStandableBeneath(FlightWorldQuery q, double x, double landY, double z) {
        int cx = (int) Math.floor(x);
        int cz = (int) Math.floor(z);
        int cy = (int) Math.floor(landY - 1.0E-9);
        return q.isStandable(cx, cy, cz)
                && !q.isSolid(cx, (int) Math.floor(landY), cz);
    }

    private static Trajectory unknown(int ticks, double x, double y, double z,
                                      double vx, double vy, double vz,
                                      double wallDamage, boolean firework) {
        return finish(Outcome.UNKNOWN, ticks, x, y, z, vx, vy, vz,
                0, 0, 0, wallDamage, 0, firework);
    }

    private static Trajectory finish(Outcome outcome, int ticks,
                                     double endX, double endY, double endZ,
                                     double endVx, double endVy, double endVz,
                                     double landX, double landY, double landZ,
                                     double wallDamage, double fallDamage, boolean firework) {
        return new Trajectory(outcome, ticks, endX, endY, endZ, endVx, endVy, endVz,
                landX, landY, landZ, wallDamage, fallDamage, firework);
    }
}
