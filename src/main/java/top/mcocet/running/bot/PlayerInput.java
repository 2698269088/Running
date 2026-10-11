package top.mcocet.running.bot;

/**
 * 玩家单刻输入帧，对齐 Minecraft {@code net.minecraft.world.entity.player.Input}。
 *
 * <p>7 个按键构成一个不可变输入帧：
 * <ul>
 *   <li>{@code forward/backward/left/right} —— WASD 移动键；</li>
 *   <li>{@code jump} —— 跳跃键（空格）；</li>
 *   <li>{@code shift} —— 潜行键（同时触发缓行）；</li>
 *   <li>{@code sprint} —— 冲刺键（左 Ctrl）。</li>
 * </ul>
 * {@link #toMask()} 的位标志与官方输入包
 * （{@code ServerboundPlayerInputPacket}）的编码完全一致。</p>
 */
public record PlayerInput(boolean forward, boolean backward, boolean left, boolean right,
                          boolean jump, boolean shift, boolean sprint) {

    public static final PlayerInput EMPTY =
            new PlayerInput(false, false, false, false, false, false, false);

    // ------------------------------------------------------------------
    // 位标志（对齐 MC Input.STREAM_CODEC：1/2/4/8/16/32/64）
    // ------------------------------------------------------------------

    /** 打包为官方输入包的位标志 */
    public int toMask() {
        int mask = 0;
        if (forward) mask |= 1;
        if (backward) mask |= 2;
        if (left) mask |= 4;
        if (right) mask |= 8;
        if (jump) mask |= 16;
        if (shift) mask |= 32;
        if (sprint) mask |= 64;
        return mask;
    }

    public static PlayerInput fromMask(int mask) {
        return new PlayerInput(
                (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0,
                (mask & 16) != 0, (mask & 32) != 0, (mask & 64) != 0);
    }

    // ------------------------------------------------------------------
    // 移动冲量（对齐 KeyboardInput.calculateImpulse：左为正、前为正）
    // ------------------------------------------------------------------

    /** 左为正、右为负；左右同时按下或都未按为 0 */
    public float leftImpulse() {
        return left == right ? 0.0F : (left ? 1.0F : -1.0F);
    }

    /** 前为正、后为负；前后同时按下或都未按为 0 */
    public float forwardImpulse() {
        return forward == backward ? 0.0F : (forward ? 1.0F : -1.0F);
    }

    /** 是否存在任何方向输入 */
    public boolean hasMovement() {
        return leftImpulse() != 0.0F || forwardImpulse() != 0.0F;
    }

    /** 是否按住了前进键（对齐 ClientInput.hasForwardImpulse 的布尔语义） */
    public boolean hasForwardImpulse() {
        return forwardImpulse() > 0.0F;
    }

    // ------------------------------------------------------------------
    // 组合工具
    // ------------------------------------------------------------------

    /** 按键取并集（用于叠加多个来源的输入，如"前进 + 跳跃"） */
    public PlayerInput merge(PlayerInput other) {
        return new PlayerInput(
                forward || other.forward, backward || other.backward,
                left || other.left, right || other.right,
                jump || other.jump, shift || other.shift, sprint || other.sprint);
    }

    public PlayerInput withForward(boolean value) {
        return new PlayerInput(value, backward, left, right, jump, shift, sprint);
    }

    public PlayerInput withBackward(boolean value) {
        return new PlayerInput(forward, value, left, right, jump, shift, sprint);
    }

    public PlayerInput withLeft(boolean value) {
        return new PlayerInput(forward, backward, value, right, jump, shift, sprint);
    }

    public PlayerInput withRight(boolean value) {
        return new PlayerInput(forward, backward, left, value, jump, shift, sprint);
    }

    public PlayerInput withJump(boolean value) {
        return new PlayerInput(forward, backward, left, right, value, shift, sprint);
    }

    public PlayerInput withShift(boolean value) {
        return new PlayerInput(forward, backward, left, right, jump, value, sprint);
    }

    public PlayerInput withSprint(boolean value) {
        return new PlayerInput(forward, backward, left, right, jump, shift, value);
    }
}
