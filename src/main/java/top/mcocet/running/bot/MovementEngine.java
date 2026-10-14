package top.mcocet.running.bot;

import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerState;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerStatusOnlyPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerCommandPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bbtt.mcbot.Bot;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 20 TPS 移动引擎：官方位置包发送策略 + 物理驱动。
 *
 * <p>引擎在独立的守护线程上以 50ms 周期运行，每个 tick 依次执行：
 * <ol>
 *   <li>复位本 tick 输入为 {@link PlayerInput#EMPTY}，驱动当前 {@link MovementGoal}
 *       （目标通过 {@link #setInput} / {@link #look} 写入输入与朝向）；</li>
 *   <li>调用 {@link PlayerPhysics#tick} 推进一个物理 tick；</li>
 *   <li>输入位标志（{@link PlayerInput#toMask()}）变化时发
 *       ServerboundPlayerInputPacket（自动跳跃注入帧含 jump，对齐官方 makeJump）；</li>
 *   <li>冲刺状态变化时发 ServerboundPlayerCommandPacket；</li>
 *   <li>按官方 {@code LocalPlayer.sendPosition} 策略发送位置包：
 *       位移平方超过 (2.0E-4)² 或每 20 tick 提醒一次时发
 *       Pos / Rot / PosRot 包，着地或水平碰撞状态变化时发 StatusOnly 包。</li>
 *   <li>末尾发送 ServerboundClientTickEndPacket（1.21.2+ 客户端 tick 结束包，
 *       对齐官方 {@code Minecraft.tick}）：Grim 的 TickTimer 据此标记本 tick 边界，
 *       要求任意两个飞行包之间必须夹一个 tick 结束包，否则报 type=flying。</li>
 * </ol></p>
 *
 * <p>机器人移动的本质与官方客户端一致：按输入键预测物理位移，
 * 再把预测结果发给服务器；服务器下发的传送与击退/爆炸冲量经
 * 本引擎写回物理状态（见“服务器同步对接”）。</p>
 */
public final class MovementEngine {
    private static final Logger log = LoggerFactory.getLogger(MovementEngine.class.getSimpleName());

    /** tick 周期（毫秒），对应 20 TPS */
    private static final long TICK_INTERVAL_MS = 50L;

    /**
     * 飞行包最小间隔（纳秒），约为一个 tick 周期的一半。
     * <p>用于单发包守卫：正常情况下相邻两次发送间隔约 50ms，恒不小于该阈值，
     * 因此不会拦截任何常规每-tick 发送；仅拦截异常的紧邻连发，确保每个 tick
     * 至多一个飞行包——Grim TickTimer 会在一个 tick 结束包之前收到多于一个飞行包
     * 时报 type=end，故这里杜绝一切每 tick 多发的可能。</p>
     */
    private static final long MIN_FLYING_INTERVAL_NS = 25_000_000L;

    /** 位置发送位移阈值·平方（对齐 LocalPlayer.sendPosition：Mth.square(2.0E-4)） */
    private static final double POSITION_SEND_THRESHOLD_SQR = 2.0E-4 * 2.0E-4;

    /** 静止时位置提醒间隔（tick），对齐 LocalPlayer.POSITION_REMINDER_INTERVAL */
    private static final int POSITION_REMINDER_INTERVAL = 20;

    private final BotState state;
    private final PlayerPhysics physics;

    private final AtomicReference<MovementGoal> goal = new AtomicReference<>();

    private volatile ScheduledExecutorService scheduler;

    // ---- 本 tick 输入（引擎线程访问） ----
    private PlayerInput currentInput = PlayerInput.EMPTY;

    // ---- 官方发送状态（对齐 LocalPlayer 的 xLast/yRotLast/positionReminder 等） ----
    private double xLast;
    private double yLast;
    private double zLast;
    private float yRotLast;
    private float xRotLast;
    private boolean lastOnGround;
    private boolean lastHorizontalCollision;
    private boolean lastSprinting;
    private int lastInputMask;
    private int positionReminder;

    /** 上一次发出飞行包的时刻（纳秒），用于单发包守卫 */
    private long lastFlyingNano;

    /** 请求下一次 tick 强制发送位置包（服务器传送后使用；由网络线程写入） */
    private volatile boolean forceSyncPending;

    public MovementEngine(BotState state, PlayerPhysics physics) {
        this.state = state;
        this.physics = physics;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 启动引擎（重复调用无副作用） */
    public synchronized void start() {
        if (isRunning()) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Running-Movement-Engine");
            thread.setDaemon(true);
            return thread;
        });
        // 采用固定延迟：一次 tick 超时不会引发补偿性的连发
        scheduler.scheduleWithFixedDelay(this::safeTick, TICK_INTERVAL_MS, TICK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("[Running] 移动引擎已启动（20 TPS）。");
    }

    /** 停止引擎并取消当前目标 */
    public synchronized void stop() {
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        if (current != null) {
            current.shutdownNow();
        }
        MovementGoal currentGoal = goal.getAndSet(null);
        if (currentGoal != null) {
            currentGoal.onFinish(this, false);
        }
        log.info("[Running] 移动引擎已停止。");
    }

    public boolean isRunning() {
        ScheduledExecutorService current = scheduler;
        return current != null && !current.isShutdown();
    }

    // ------------------------------------------------------------------
    // tick 主循环
    // ------------------------------------------------------------------

    private void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            log.warn("[Running] 移动引擎 tick 异常（已跳过本 tick）: {}", t.toString());
        }
    }

    private void tick() {
        if (!isInGame()) return;

        // 1) 复位输入并驱动目标（目标可为本 tick 填输入）
        currentInput = PlayerInput.EMPTY;
        driveGoal();

        if (!physics.isPositioned()) return;

        // 2) 物理推进
        physics.tick(currentInput);

        // 3) 发送（对齐官方 LocalPlayer.tick：输入包 → 冲刺包 → 位置包）
        sendInputIfChanged();
        sendSprintIfNeeded();
        sendPosition();

        // 4) tick 结束包（对齐官方 Minecraft.tick 末尾）：本 tick 的所有飞行/输入包
        //    发完后必发一个 ServerboundClientTickEndPacket，向服务器标记本 tick 边界。
        //    Grim TickTimer 要求任意两个飞行包之间夹一个 tick 结束包，否则报 type=flying；
        //    静止 tick 无飞行包时同样发送（与官方一致，供服务器按 idle tick 预测）。
        sendClientTickEnd();
    }

    private void driveGoal() {
        MovementGoal current = goal.get();
        if (current == null) return;
        if (current.isFinished()) {
            // 仅当目标未被并发替换时才清除，避免误结束新目标
            if (goal.compareAndSet(current, null)) {
                current.onFinish(this, true);
            }
        } else {
            current.tick(this);
        }
    }

    /** 会话是否处于游戏状态（引擎可正常驱动物理与发包；命令层据此校验可操作性） */
    public boolean isInGame() {
        ClientSession session = Bot.INSTANCE.getSession();
        if (session == null || !session.isConnected()) return false;
        MinecraftProtocol protocol = Bot.INSTANCE.getProtocol();
        if (protocol == null) return false;
        return protocol.getInboundState() == ProtocolState.GAME
                && protocol.getOutboundState() == ProtocolState.GAME;
    }

    // ------------------------------------------------------------------
    // 发送（对齐 LocalPlayer.sendPosition / sendIsSprintingIfNeeded）
    // ------------------------------------------------------------------

    private void sendPosition() {
        ClientSession session = Bot.INSTANCE.getSession();
        if (session == null || !session.isConnected()) return;

        double x = physics.x();
        double y = physics.y();
        double z = physics.z();
        float yaw = physics.yaw();
        float pitch = physics.pitch();
        boolean onGround = physics.onGround();
        boolean horizontalCollision = physics.horizontalCollision();

        double deltaX = x - xLast;
        double deltaY = y - yLast;
        double deltaZ = z - zLast;
        float deltaYaw = yaw - yRotLast;
        float deltaPitch = pitch - xRotLast;
        ++positionReminder;

        boolean move = forceSyncPending
                || deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ > POSITION_SEND_THRESHOLD_SQR
                || positionReminder >= POSITION_REMINDER_INTERVAL;
        boolean rot = deltaYaw != 0.0F || deltaPitch != 0.0F;
        boolean statusOnly = !move && !rot
                && (lastOnGround != onGround || lastHorizontalCollision != horizontalCollision);
        boolean willSend = move || rot || statusOnly;

        // 单发包守卫：本 tick 至多一个飞行包，且与上一次飞行包保持最小间隔。
        // 正常节律（每 ~50ms 一个 tick）下恒不触发；仅拦截异常连发。配合每 tick
        // 末尾的 tick 结束包，保证“飞行包 ≤1 且两飞行包之间必有 tick 结束包”，
        // 从而不触发 Grim TickTimer 的 type=flying / type=end。
        long now = System.nanoTime();
        if (willSend && lastFlyingNano != 0L && now - lastFlyingNano < MIN_FLYING_INTERVAL_NS) {
            return;
        }

        if (move && rot) {
            session.send(new ServerboundMovePlayerPosRotPacket(
                    onGround, horizontalCollision, x, y, z, yaw, pitch));
        } else if (move) {
            session.send(new ServerboundMovePlayerPosPacket(
                    onGround, horizontalCollision, x, y, z));
        } else if (rot) {
            session.send(new ServerboundMovePlayerRotPacket(
                    onGround, horizontalCollision, yaw, pitch));
        } else if (statusOnly) {
            session.send(new ServerboundMovePlayerStatusOnlyPacket(onGround, horizontalCollision));
        }
        if (willSend) {
            lastFlyingNano = now;
        }

        if (move) {
            xLast = x;
            yLast = y;
            zLast = z;
            positionReminder = 0;
            forceSyncPending = false;
        }
        if (rot) {
            yRotLast = yaw;
            xRotLast = pitch;
        }
        lastOnGround = onGround;
        lastHorizontalCollision = horizontalCollision;
    }

    private void sendClientTickEnd() {
        ClientSession session = Bot.INSTANCE.getSession();
        if (session == null || !session.isConnected()) return;
        session.send(ServerboundClientTickEndPacket.INSTANCE);
    }

    private void sendSprintIfNeeded() {
        boolean sprinting = physics.sprinting();
        if (sprinting == lastSprinting) return;

        ClientSession session = Bot.INSTANCE.getSession();
        int entityId = state.getEntityId();
        if (session == null || !session.isConnected() || entityId < 0) return;

        session.send(new ServerboundPlayerCommandPacket(entityId,
                sprinting ? PlayerState.START_SPRINTING : PlayerState.STOP_SPRINTING));
        lastSprinting = sprinting;
    }

    private void sendInputIfChanged() {
        // 自动跳跃注入 tick 的输入帧含 jump（对齐官方 makeJump 后发送的 keyPresses）
        PlayerInput sentInput = physics.wasAutoJumpInjected()
                ? currentInput.withJump(true) : currentInput;
        int mask = sentInput.toMask();
        if (mask == lastInputMask) return;

        ClientSession session = Bot.INSTANCE.getSession();
        if (session == null || !session.isConnected()) return;

        session.send(new ServerboundPlayerInputPacket(
                sentInput.forward(), sentInput.backward(),
                sentInput.left(), sentInput.right(),
                sentInput.jump(), sentInput.shift(), sentInput.sprint()));
        lastInputMask = mask;
    }

    // ------------------------------------------------------------------
    // 移动目标管理
    // ------------------------------------------------------------------

    /**
     * 设置当前移动目标（旧的未完成目标会被取消）。
     *
     * @return 被替换掉的旧目标，无则为 null
     */
    public MovementGoal setGoal(MovementGoal newGoal) {
        MovementGoal old = goal.getAndSet(newGoal);
        if (old != null) {
            old.onFinish(this, false);
        }
        if (newGoal != null) {
            newGoal.onStart(this);
        }
        return old;
    }

    /** 当前移动目标，无则为 null */
    public MovementGoal getGoal() {
        return goal.get();
    }

    // ------------------------------------------------------------------
    // 输入与朝向（供 MovementGoal 与命令使用；均在 tick 内生效）
    // ------------------------------------------------------------------

    /**
     * 设置本 tick 的输入帧。
     *
     * <p>注意：引擎在每个 tick 开始时把输入复位为 {@link PlayerInput#EMPTY}，
     * 因此移动目标需要在每次 {@link MovementGoal#tick(MovementEngine)} 中
     * 持续设置输入（与"按住按键"语义一致）。</p>
     */
    public void setInput(PlayerInput input) {
        currentInput = input != null ? input : PlayerInput.EMPTY;
    }

    /** 本 tick 生效的输入帧 */
    public PlayerInput input() {
        return currentInput;
    }

    /** 设置朝向（角度制，yaw 水平、pitch 垂直）；由下一次位置包同步 */
    public void look(float yaw, float pitch) {
        physics.setRotation(yaw, pitch);
    }

    /** 物理实例（供目标与控制台查询位置、速度、碰撞状态） */
    public PlayerPhysics physics() {
        return physics;
    }

    // ------------------------------------------------------------------
    // 服务器同步对接（传送与击退）
    // ------------------------------------------------------------------

    /**
     * 服务器传送：绝对重置物理状态（位置、朝向与速度，对齐官方
     * {@code setValuesFromPositionPacket} 的 setPos + setDeltaMovement +
     * setYRot/setXRot，不改变冲刺、碰撞与跳跃冷却状态），并请求下一次
     * tick 立即回报位置（与 {@code ServerboundAcceptTeleportationPacket}
     * 回执配合）。
     */
    public void handleTeleport(double x, double y, double z, float yaw, float pitch,
                               double deltaX, double deltaY, double deltaZ) {
        physics.reset(x, y, z, yaw, pitch, deltaX, deltaY, deltaZ);
        forceSyncPending = true;
    }

    /**
     * 自身速度包（ClientboundSetEntityMotionPacket）：用包内速度整体替换当前速度
     * （对齐官方 {@code ClientPacketListener.handleSetEntityMotion →
     * Entity.lerpMotion → setDeltaMovement}）。近战/弓箭/钓鱼竿击退、实体推挤
     * 与 mace 冲击均经由该包下发；不强制发送位置包（与官方一致，
     * 由常规发送策略在后续 tick 跟进）。
     */
    public void handleSetMotion(double movementX, double movementY, double movementZ) {
        physics.setDeltaMovement(movementX, movementY, movementZ);
    }

    /**
     * 爆炸冲量（ClientboundExplodePacket.playerKnockback）：在现有速度上叠加
     * （对齐官方 {@code handleExplosion → LocalPlayer.addDeltaMovement}）。
     */
    public void handleExplosionKnockback(double knockbackX, double knockbackY, double knockbackZ) {
        physics.addDeltaMovement(knockbackX, knockbackY, knockbackZ);
    }

    /** 请求下一次 tick 强制发送一次位置包 */
    public void requestPositionSync() {
        forceSyncPending = true;
    }
}
