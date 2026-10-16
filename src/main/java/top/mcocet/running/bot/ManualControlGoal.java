package top.mcocet.running.bot;

/**
 * 手动控制目标：由控制台命令持续注入按键输入。
 *
 * <p>维护一组"按住"的按键状态（前后左右移动、疾跑、潜行），引擎每个 tick
 * 调用 {@link #tick(MovementEngine)} 时把状态快照注入；跳跃为一次性请求，
 * 在机器人可起跳（着地且跳跃冷却已过）时注入一个 tick 的跳跃输入后自动清除。
 * 目标本身永不完成，由命令层替换或移除（{@code move stop}）。</p>
 *
 * <p>线程约定：命令线程通过各访问器读写状态（同一把锁保护），引擎线程在
 * {@link #tick} 中取快照；{@link #isFinished()} 恒为 false。</p>
 */
public final class ManualControlGoal implements MovementGoal {
    private final Object lock = new Object();

    /** 按住的按键状态（跳跃键不在其中，由 {@link #jumpRequested} 单独管理） */
    private PlayerInput keys = PlayerInput.EMPTY;

    /** 待执行的跳跃请求：可起跳时注入一个 tick 的跳跃输入后清除 */
    private boolean jumpRequested;

    // ------------------------------------------------------------------
    // 命令层操作
    // ------------------------------------------------------------------

    public void setForward(boolean value) {
        synchronized (lock) {
            keys = keys.withForward(value);
        }
    }

    public void setBackward(boolean value) {
        synchronized (lock) {
            keys = keys.withBackward(value);
        }
    }

    public void setLeft(boolean value) {
        synchronized (lock) {
            keys = keys.withLeft(value);
        }
    }

    public void setRight(boolean value) {
        synchronized (lock) {
            keys = keys.withRight(value);
        }
    }

    /** 疾跑键（左 Ctrl；需配合前进输入才会真正进入冲刺状态） */
    public void setSprint(boolean value) {
        synchronized (lock) {
            keys = keys.withSprint(value);
        }
    }

    /** 潜行键（Shift；同时触发缓行，并阻止进入冲刺状态） */
    public void setSneak(boolean value) {
        synchronized (lock) {
            keys = keys.withShift(value);
        }
    }

    public boolean forward() {
        synchronized (lock) {
            return keys.forward();
        }
    }

    public boolean backward() {
        synchronized (lock) {
            return keys.backward();
        }
    }

    public boolean left() {
        synchronized (lock) {
            return keys.left();
        }
    }

    public boolean right() {
        synchronized (lock) {
            return keys.right();
        }
    }

    public boolean sprint() {
        synchronized (lock) {
            return keys.sprint();
        }
    }

    public boolean sneak() {
        synchronized (lock) {
            return keys.shift();
        }
    }

    /** 请求跳跃一次（可起跳时执行） */
    public void requestJump() {
        synchronized (lock) {
            jumpRequested = true;
        }
    }

    /** 复位全部按键状态与跳跃请求 */
    public void clear() {
        synchronized (lock) {
            keys = PlayerInput.EMPTY;
            jumpRequested = false;
        }
    }

    // ------------------------------------------------------------------
    // MovementGoal
    // ------------------------------------------------------------------

    @Override
    public void tick(MovementEngine engine) {
        synchronized (lock) {
            PlayerInput input = keys;
            if (jumpRequested && engine.physics().canJump()) {
                input = input.withJump(true);
                jumpRequested = false;
            }
            engine.setInput(input);
        }
    }

    @Override
    public boolean isFinished() {
        return false;
    }
}
