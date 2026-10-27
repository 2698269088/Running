package top.mcocet.running.flight;

/**
 * 飞行动作提案（移植 MovementSync 1.11 的 {@code FlightAction.Glide} 语义）：
 * 不可变的"控制提案"，构造无任何副作用——请求烟花不代表消耗，
 * 只有 {@code execute} 阶段才真正转向/点火。
 *
 * @param yaw      目标偏航（度，有限值）
 * @param pitch    目标俯仰（度；正值俯视、负值抬头，有限值）
 * @param firework 本 tick 是否请求发射一发烟花加速
 */
public record FlightAction(float yaw, float pitch, boolean firework) {

    /** 校验控制量有限（角度不做归一/钳制，交由执行层的限速逻辑）。 */
    public FlightAction {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Nonfinite controls");
        }
    }
}
