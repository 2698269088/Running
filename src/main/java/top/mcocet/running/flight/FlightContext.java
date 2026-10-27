package top.mcocet.running.flight;

/**
 * 一次飞行决策的<b>只读输入快照</b>（移植 MovementSync 1.11 的 {@code FlightContext}
 * 约定：预测钩子只能查询这些输入，绝不改变世界或活体物理；由调用方在引擎
 * tick 内从 {@code PlayerPhysics} 组装）。
 *
 * @param query     非空的世界查询视图（活区块视图，导航/引擎线程只读）
 * @param x, y, z   当前脚底中心坐标
 * @param vx, vy, vz 当前速度（方块/tick）
 * @param fallDist  当前累计摔落距离
 * @param yaw       当前偏航（度）
 * @param pitch     当前俯仰（度）
 * @param targetX, targetY, targetZ 目标点（飞行航向与着陆评估的锚点；自由巡航
 *                  时可传"前方参考点 + 维持高度"）
 * @param radius    到达判定半径（格，&gt;0）
 * @param fireworks 当前可用的火箭预算（受冷却限制后的剩余可发射数）
 * @param attached  是否已有火箭附着加速窗口在途
 * @param health    当前血量（用于把伤害分类为致命；&lt;=0 视为致命阈值）
 * @param emergency 是否损伤优先（true 时不再要求 SAFE，按伤害最小排序——
 *                  用于迫降/耐久将尽/低血场景）
 * @param worldMinY 世界最低高度（推演触底判 UNKNOWN）
 */
public record FlightContext(FlightWorldQuery query,
                            double x, double y, double z,
                            double vx, double vy, double vz, double fallDist,
                            float yaw, float pitch,
                            double targetX, double targetY, double targetZ,
                            double radius, int fireworks, boolean attached,
                            double health, boolean emergency, int worldMinY) {

    /** 拒绝非法输入：非有限量、非正半径、负火箭数直接抛异常（对齐 MS 的构造校验）。 */
    public FlightContext {
        if (query == null) {
            throw new NullPointerException("query");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(vx) || !Double.isFinite(vy) || !Double.isFinite(vz)
                || !Double.isFinite(targetX) || !Double.isFinite(targetY) || !Double.isFinite(targetZ)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || !Double.isFinite(health) || health < 0
                || !Double.isFinite(radius) || radius <= 0 || fireworks < 0) {
            throw new IllegalArgumentException("Invalid flight inputs");
        }
    }

    /** 水平面上到目标的距离（起点）。 */
    public double distanceToTarget() {
        return Math.hypot(targetX - x, targetZ - z);
    }
}
