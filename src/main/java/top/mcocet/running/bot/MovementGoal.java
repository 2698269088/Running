package top.mcocet.running.bot;

/**
 * 移动目标：由 {@link MovementEngine} 以 20 TPS 驱动的一段移动任务。
 *
 * <p>实现类在 {@link #tick(MovementEngine)} 中通过引擎提供的输入 API 推进移动：
 * {@link MovementEngine#setInput(PlayerInput)} 设置“按键”（注意引擎每个 tick 都会
 * 复位输入，需每 tick 持续设置），{@link MovementEngine#look(float, float)}
 * 调整朝向；物理模拟与位置包发送均由引擎自动完成。
 * 返回 {@code true} 表示任务完成，引擎将调用
 * {@link #onFinish(MovementEngine, boolean)} 并将其移除。
 * 后续的寻路、跟随、跳跃等功能均以实现本接口的形式接入。</p>
 *
 * <p>线程约定：{@link #tick} 运行在引擎线程；{@link #onStart} 与
 * {@link #onFinish} 运行在设置/替换目标的线程（通常是命令线程或引擎线程）。</p>
 */
public interface MovementGoal {
    /** 目标被设置到引擎时调用 */
    default void onStart(MovementEngine engine) {
    }

    /** 每个 tick 调用一次（20 次/秒） */
    void tick(MovementEngine engine);

    /** 目标是否已完成 */
    boolean isFinished();

    /**
     * 目标结束（完成或被替换/取消）时调用。
     *
     * @param completed true 表示目标自行完成；false 表示被替换或引擎停止
     */
    default void onFinish(MovementEngine engine, boolean completed) {
    }
}
