package top.mcocet.running.flight;

import top.mcocet.running.math.Vec3;

/**
 * 鞘翅滑翔/火箭加速的<b>纯物理步进器</b>（无世界查询、无副作用），思路移植自
 * MovementSync 1.11 的 {@code GlidingPhysics}：同一份公式既供 {@code PlayerPhysics}
 * 权威推进，也供 {@link Trajectory} 在决策 tick 内向前推演候选弹道，
 * 保证"预测的就是执行的"，决策与物理永不分叉。
 *
 * <p>公式对齐（Minecraft 26.1.2 反编译源码）：</p>
 * <ul>
 *   <li>{@code LivingEntity.updateFallFlyingMovement} —— 重力被俯仰余弦平方升力
 *       部分抵消（{@code gravity × (−1 + cos²θ × 0.75)}）；下落时按视线方向分摊
 *       前推（{@code vy × −0.1 × cos²θ}）；抬头（pitch &lt; 0）时把前推动能换为
 *       抬升（{@code moveH × |sinθ| × 0.04}，垂直 ×3.2）；水平速度向视线水平
 *       方向插值对齐（0.1）；末尾乘 {@code 0.99 / 0.98 / 0.99} 衰减；</li>
 *   <li>{@code FireworkRocketEntity.tick} 附着滑翔玩家分支 —— 每 tick 叠加
 *       {@code look × 0.1 + (look × 1.5 − motion) × 0.5}；推演中的 {@code firework}
 *       候选表示"当前挂上火箭后、附着窗口内逐 tick 持续加速"的近似；</li>
 *   <li>{@code Entity.calculateViewVector} —— 视线单位向量
 *       {@code (sin(−yaw)·cos(pitch), −sin(pitch), cos(−yaw)·cos(pitch))}。</li>
 * </ul>
 *
 * <p>三角函数与 {@code PlayerPhysics} 现有约定一致：按 float 精度取 sin/cos 后
 * 参与 double 运算（官方为 65536 项 LUT 截断，本实现与物理层保持同源即自洽）。</p>
 */
public final class FlightPhysics {

    /** 重力（Attributes.GRAVITY 默认值；预测器与物理层同一常量入口） */
    public static final double GRAVITY = 0.08;

    private FlightPhysics() {
    }

    /**
     * 视线单位向量（对齐 Entity.calculateViewVector：realXRot = xRot 不取负、
     * realYRot = −yRot；pitch 正值俯视时 y 分量为负）。
     */
    public static Vec3 lookVector(float yaw, float pitch) {
        float rotX = pitch * ((float) Math.PI / 180F);
        float rotY = -yaw * ((float) Math.PI / 180F);
        float sinX = (float) Math.sin((double) rotX);
        float cosX = (float) Math.cos((double) rotX);
        float sinY = (float) Math.sin((double) rotY);
        float cosY = (float) Math.cos((double) rotY);
        return new Vec3((double) sinY * (double) cosX, (double) (-sinX), (double) cosY * (double) cosX);
    }

    /**
     * 滑翔速度推进一个 tick（对齐 LivingEntity.updateFallFlyingMovement，逐行）。
     *
     * @param motion  本 tick 初速度
     * @param yaw     视线偏航（度）
     * @param pitch   视线俯仰（度；负值抬头）
     * @param gravity 有效重力（含缓降药水的 min 处理由调用方决定）
     * @return 推进后的速度（新实例，不修改入参）
     */
    public static Vec3 glideStep(Vec3 motion, float yaw, float pitch, double gravity) {
        Vec3 look = lookVector(yaw, pitch);
        float leanAngle = pitch * ((float) Math.PI / 180F);
        double lookHorLength = Math.sqrt(look.x * look.x + look.z * look.z);
        double moveHorLength = motion.horizontalDistance();
        double liftForce = Math.cos((double) leanAngle) * Math.cos((double) leanAngle);
        Vec3 result = motion.add(0.0, gravity * (-1.0 + liftForce * 0.75), 0.0);
        if (result.y < 0.0 && lookHorLength > 0.0) {
            double convert = result.y * -0.1 * liftForce;
            result = result.add(look.x * convert / lookHorLength, convert, look.z * convert / lookHorLength);
        }
        if (leanAngle < 0.0F && lookHorLength > 0.0) {
            double convert = moveHorLength * (-Math.sin((double) leanAngle)) * 0.04;
            result = result.add(-look.x * convert / lookHorLength, convert * 3.2,
                    -look.z * convert / lookHorLength);
        }
        if (lookHorLength > 0.0) {
            result = result.add((look.x / lookHorLength * moveHorLength - result.x) * 0.1, 0.0,
                    (look.z / lookHorLength * moveHorLength - result.z) * 0.1);
        }
        return result.multiply(0.99, 0.98, 0.99);
    }

    /**
     * 火箭附着冲量（对齐 FireworkRocketEntity.tick 附着分支）：每 tick 叠加
     * {@code look × 0.1 + (look × 1.5 − motion) × 0.5}。
     */
    public static Vec3 rocketStep(Vec3 motion, float yaw, float pitch) {
        Vec3 look = lookVector(yaw, pitch);
        return motion.add(
                look.x * 0.1 + (look.x * 1.5 - motion.x) * 0.5,
                look.y * 0.1 + (look.y * 1.5 - motion.y) * 0.5,
                look.z * 0.1 + (look.z * 1.5 - motion.z) * 0.5);
    }
}
