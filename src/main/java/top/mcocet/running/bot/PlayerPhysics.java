package top.mcocet.running.bot;

import top.mcocet.running.math.AABB;
import top.mcocet.running.math.Vec3;
import top.mcocet.running.world.BlockNameTable;
import top.mcocet.running.world.RunningWorld;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家刻物理模拟，逐行对齐 Minecraft 官方客户端实现。
 *
 * <p>对齐来源（Minecraft 26.1.2 反编译源码）：
 * <ul>
 *   <li>{@code LocalPlayer.modifyInput / applyInput} —— 输入向量处理（归一化、0.98 缩放、
 *       潜行 0.3 缩放、方形修正）；</li>
 *   <li>{@code LocalPlayer.updateAutoJump / canAutoJump} —— 自动跳跃（实际位移或
 *       输入意图方向的前瞻障碍探测、头顶空间检查、autoJumpTime 延迟注入；
 *       开关默认开启，可经命令调整）；</li>
 *   <li>{@code LocalPlayer.aiStep} —— 冲刺三段式状态机（canStartSprinting /
 *       shouldStopRunSprinting / 擦墙 minor 碰撞判定）、4 角点碰撞脱困推力；</li>
 *   <li>{@code LivingEntity.aiStep} —— 速度归零阈值、跳跃判定与 noJumpDelay=10；</li>
 *   <li>{@code LivingEntity.travelInAir / getFrictionInfluencedSpeed} —— 重力 0.08、
 *       垂直阻力 0.98、地面摩擦 blockFriction×0.91、速度 getSpeed()×0.21600002/f³
 *       （冲刺含 +30% 速度修饰符）、方块速度/跳跃因子；</li>
 *   <li>{@code Entity.move / collide / collideBoundingBox / collideWithShapes} ——
 *       逐轴碰撞解算（axisStepOrder：Y 轴优先）、maxUpStep=0.6 自动上台阶、
 *       水平碰撞轴速度归零、落地垂直速度归零。</li>
 * </ul></p>
 *
 * <p>已知简化（相对于官方客户端）：
 * <ul>
 *   <li>不支持流体（水/岩浆）中的移动、梯子/藤蔓攀爬与游泳；</li>
 *   <li>不支持药水效果（跳跃提升/缓降/失明）、骑乘与实体推挤；
 *       饥饿与失明影响通过可注入门控字段近似（默认允许疾跑）；</li>
 *   <li>方块碰撞形状按启发式近似（完整立方体 / 半砖 / 栅栏 1.5 / 楼梯双盒 /
 *       无碰撞），见 {@link BlockNameTable}；</li>
 *   <li>潜行不改变碰撞盒高度（官方为 1.5），也不做防走边回退。</li>
 * </ul></p>
 *
 * <p>线程约定：物理状态仅由移动引擎线程推进；{@link #reset} 允许网络线程调用
 * （服务器传送）。所有公共方法均以同一把锁保护，{@link #publish()} 把权威状态
 * 镜像到 {@link BotState} 供命令与控制台读取。</p>
 */
public final class PlayerPhysics {

    // ------------------------------------------------------------------
    // 常量（对齐 MC Attributes / EntityType.PLAYER / LivingEntity）
    // ------------------------------------------------------------------

    /** 玩家碰撞盒宽度（EntityType.PLAYER） */
    public static final float PLAYER_WIDTH = 0.6F;
    /** 玩家站立碰撞盒高度（EntityType.PLAYER） */
    public static final float PLAYER_HEIGHT = 1.8F;
    /** 玩家眼高（EntityType.PLAYER） */
    public static final float EYE_HEIGHT = 1.62F;

    /** 移动速度属性默认值（Attributes.MOVEMENT_SPEED） */
    public static final float MOVEMENT_SPEED = 0.1F;
    /** 跳跃强度属性默认值（Attributes.JUMP_STRENGTH） */
    public static final float JUMP_STRENGTH = 0.42F;
    /** 潜行速度乘数（Attributes.SNEAKING_SPEED） */
    public static final float SNEAKING_SPEED = 0.3F;

    /** 空中速度（Player.getFlyingSpeed 非冲刺） */
    public static final float AIR_SPEED = 0.02F;
    /** 空中速度·冲刺（Player.getFlyingSpeed 冲刺，官方常量 0.025999999F） */
    public static final float AIR_SPEED_SPRINTING = 0.025999999F;

    /** 冲刺移动速度修饰符（SPEED_MODIFIER_SPRINTING：+30%，属性值 0.1 → 0.13） */
    public static final float SPRINT_SPEED_MULTIPLIER = 1.3F;

    /** 擦墙碰撞判定夹角上限（弧度，LocalPlayer.isHorizontalCollisionMinor 的 0.13962634F） */
    public static final float MINOR_COLLISION_MAX_ANGLE = 0.13962634F;
    /** 擦墙碰撞判定最小向量长度平方（官方 1.0E-5F） */
    public static final float MINOR_COLLISION_MIN_LENGTH_SQR = 1.0E-5F;

    /** 重力加速度（Attributes.GRAVITY，每 tick） */
    public static final double GRAVITY = 0.08;
    /** 垂直阻力（LivingEntity.travelInAir 的 0.98F） */
    public static final double VERTICAL_DRAG = 0.98;
    /** 水平速度衰减基数（LivingEntity.travelInAir 的 0.91F） */
    public static final double FRICTION_BASE = 0.91;

    /** 冲刺跳跃水平加成（LivingEntity.jumpFromGround） */
    public static final double SPRINT_JUMP_BOOST = 0.2;
    /** 跳跃冷却（LivingEntity.aiStep：跳跃后 noJumpDelay=10） */
    public static final int JUMP_COOLDOWN_TICKS = 10;

    /** 最大自动上台阶高度（Entity.maxUpStep） */
    public static final float MAX_UP_STEP = 0.6F;

    /** 自动跳跃高度上限（LocalPlayer.updateAutoJump 的 jumpHeight，无跳跃提升药水） */
    public static final float AUTO_JUMP_MAX_HEIGHT = 1.2F;

    /** 玩家水平速度归零阈值·平方（LivingEntity.aiStep 玩家特例 9.0E-6） */
    public static final double MIN_HORIZONTAL_SPEED_SQR = 9.0E-6;
    /** 垂直速度归零阈值（LivingEntity.aiStep 的 0.003） */
    public static final double MIN_VERTICAL_SPEED = 0.003;

    // ------------------------------------------------------------------
    // 依赖
    // ------------------------------------------------------------------

    private final BotState state;
    private final RunningWorld world;
    private final BlockNameTable blockNames;

    // ------------------------------------------------------------------
    // 权威物理状态（锁保护）
    // ------------------------------------------------------------------

    /** 脚底中心坐标 */
    private double x;
    private double y;
    private double z;

    /** 朝向（角度制） */
    private float yaw;
    private float pitch;

    /** 速度（方块/tick） */
    private double deltaX;
    private double deltaY;
    private double deltaZ;

    private boolean onGround = true;
    private boolean horizontalCollision;
    /** 上一 tick 的水平碰撞是否属于"擦墙"（与输入夹角小于 8°，不中断疾跑） */
    private boolean minorHorizontalCollision;
    private boolean sprinting;
    private int noJumpDelay;
    private boolean positioned;

    /** 自动跳跃开关（对应官方"自动跳跃"选项；默认开启，命令可调整） */
    private boolean autoJumpEnabled = true;
    /** 自动跳跃延迟计数：&gt;0 时本 tick 注入一帧跳跃输入（对齐 LocalPlayer.autoJumpTime） */
    private int autoJumpTime;
    /** 本 tick 是否注入了自动跳跃（供引擎对齐官方 makeJump 后的输入帧发送） */
    private boolean autoJumpInjected;

    /** 本 tick 输入向量（对齐 Entity.xxa/zza：左侧为正、前侧为正） */
    private double xxa;
    private double zza;

    /** 是否可进行剧烈运动（对应 hasEnoughFoodToDoExhaustiveManoeuvres；机器人无饥饿数据） */
    private boolean canDoExhaustiveManoeuvres = true;
    /** 移动是否受限（对应 BLINDNESS 效果；机器人无药水数据） */
    private boolean mobilityRestricted;

    public PlayerPhysics(BotState state, RunningWorld world, BlockNameTable blockNames) {
        this.state = state;
        this.world = world;
        this.blockNames = blockNames;
    }

    // ------------------------------------------------------------------
    // 状态访问（供引擎与命令读取）
    // ------------------------------------------------------------------

    public synchronized boolean isPositioned() {
        return positioned;
    }

    public synchronized double x() {
        return x;
    }

    public synchronized double y() {
        return y;
    }

    public synchronized double z() {
        return z;
    }

    public synchronized float yaw() {
        return yaw;
    }

    public synchronized float pitch() {
        return pitch;
    }

    public synchronized double deltaX() {
        return deltaX;
    }

    public synchronized double deltaY() {
        return deltaY;
    }

    public synchronized double deltaZ() {
        return deltaZ;
    }

    public synchronized boolean onGround() {
        return onGround;
    }

    /** 是否可立即起跳（着地且跳跃冷却已过，对齐 LivingEntity 的跳跃前提） */
    public synchronized boolean canJump() {
        return onGround && noJumpDelay == 0;
    }

    /** 自动跳跃是否开启 */
    public synchronized boolean isAutoJumpEnabled() {
        return autoJumpEnabled;
    }

    /** 设置自动跳跃开关（命令线程调用，下一 tick 生效） */
    public synchronized void setAutoJumpEnabled(boolean value) {
        this.autoJumpEnabled = value;
    }

    /** 本 tick 物理是否注入了自动跳跃（引擎在 tick 后读取，对齐官方 makeJump 后的输入帧） */
    public synchronized boolean wasAutoJumpInjected() {
        return autoJumpInjected;
    }

    public synchronized boolean horizontalCollision() {
        return horizontalCollision;
    }

    public synchronized boolean sprinting() {
        return sprinting;
    }

    // ------------------------------------------------------------------
    // 外部写入
    // ------------------------------------------------------------------

    /**
     * 服务器传送：绝对设置位置、朝向与速度，不改变冲刺、碰撞与跳跃冷却状态
     * （对齐 ClientPacketListener.setValuesFromPositionPacket：
     * setPos + setDeltaMovement + setYRot/setXRot）。
     */
    public synchronized void reset(double newX, double newY, double newZ, float newYaw, float newPitch,
                                   double newDeltaX, double newDeltaY, double newDeltaZ) {
        this.x = newX;
        this.y = newY;
        this.z = newZ;
        this.yaw = newYaw;
        this.pitch = newPitch;
        this.deltaX = newDeltaX;
        this.deltaY = newDeltaY;
        this.deltaZ = newDeltaZ;
        this.positioned = true;
        publish();
    }

    /** 设置朝向（角度制）；不影响速度，供移动目标 look() 使用 */
    public synchronized void setRotation(float newYaw, float newPitch) {
        this.yaw = newYaw;
        this.pitch = Math.max(-90.0F, Math.min(90.0F, newPitch));
        publish();
    }

    /**
     * 设置"可进行剧烈运动"门控（对应 {@code hasEnoughFoodToDoExhaustiveManoeuvres}）。
     * 机器人无饥饿数据，默认允许；上层可按服务器状态注入。
     */
    public synchronized void setCanDoExhaustiveManoeuvres(boolean value) {
        this.canDoExhaustiveManoeuvres = value;
    }

    /** 设置移动限制门控（对应 BLINDNESS 效果）；默认无限制 */
    public synchronized void setMobilityRestricted(boolean value) {
        this.mobilityRestricted = value;
    }

    // ------------------------------------------------------------------
    // tick 主流程（对齐 LivingEntity.aiStep 的顺序）
    // ------------------------------------------------------------------

    /**
     * 推进一个物理 tick（1/20 秒）。
     *
     * @param input 本 tick 的输入帧；调用方（引擎）在每次 tick 前复位为
     *              {@link PlayerInput#EMPTY}，由移动目标填充
     */
    public synchronized void tick(PlayerInput input) {
        autoJumpInjected = false;
        if (!positioned) {
            return;
        }

        // (0) 自动跳跃延迟消费（LocalPlayer.aiStep：input.tick 之后执行 makeJump，
        //     向本 tick 注入一帧跳跃输入）
        boolean autoJumping = false;
        if (autoJumpTime > 0) {
            --autoJumpTime;
            autoJumpInjected = true;
            autoJumping = true;
        }

        // (1) 碰撞脱困推力（LocalPlayer.aiStep：4 个水平角点，位于速度归零阈值之前，
        //     可能直接覆盖水平速度）
        double cornerOffset = PLAYER_WIDTH * 0.35;
        moveTowardsClosestSpace(x - cornerOffset, z + cornerOffset);
        moveTowardsClosestSpace(x - cornerOffset, z - cornerOffset);
        moveTowardsClosestSpace(x + cornerOffset, z - cornerOffset);
        moveTowardsClosestSpace(x + cornerOffset, z + cornerOffset);

        // (2) 冲刺状态维护（LocalPlayer.aiStep 三段式；读取上一 tick 的碰撞结果）
        updateSprinting(input);

        // (3) 跳跃延迟递减（LivingEntity.aiStep 开头）
        if (noJumpDelay > 0) {
            --noJumpDelay;
        }

        // (4) 速度归零阈值（aiStep：玩家水平用平方阈值 9.0E-6，垂直 0.003）
        if (deltaX * deltaX + deltaZ * deltaZ < MIN_HORIZONTAL_SPEED_SQR) {
            deltaX = 0.0;
            deltaZ = 0.0;
        }
        if (Math.abs(deltaY) < MIN_VERTICAL_SPEED) {
            deltaY = 0.0;
        }

        // (5) 输入处理（LocalPlayer.applyInput + modifyInput）
        double[] move = modifyInput(input.leftImpulse(), input.forwardImpulse(), input.shift());
        this.xxa = move[0];
        this.zza = move[1];

        // (6) 跳跃（aiStep 跳跃段；不支持流体跳跃，跳跃输入含自动跳跃注入）
        boolean jumpInput = input.jump() || autoJumping;
        if (jumpInput && onGround && noJumpDelay == 0) {
            jumpFromGround();
            noJumpDelay = JUMP_COOLDOWN_TICKS;
        } else if (!jumpInput) {
            noJumpDelay = 0;
        }

        // (7) 空中行进（LivingEntity.travelInAir）
        double prevX = x;
        double prevZ = z;
        travelInAir(this.xxa, this.zza);

        // (7b) 自动跳跃检测（对齐 LocalPlayer.move 末尾：传入本 tick 实际位移）
        updateAutoJump((float) (x - prevX), (float) (z - prevZ), input);

        // (8) 发布权威状态
        publish();
    }

    // ------------------------------------------------------------------
    // 内部：输入与冲刺
    // ------------------------------------------------------------------

    /**
     * 冲刺状态机，对齐 LocalPlayer.aiStep 的三段式逻辑：
     * <ul>
     *   <li>canStartSprinting：未在冲刺、有前进输入、允许疾跑、未缓行且
     *       按住冲刺键时开始（官方还含双击自动疾跑窗口与游泳分支，机器人不使用；
     *       潜行仅阻止开始，不清除已有冲刺）；</li>
     *   <li>shouldStopRunSprinting：失去疾跑条件、失去前进输入，或发生
     *       "非擦墙"水平碰撞时停止。</li>
     * </ul>
     */
    private void updateSprinting(PlayerInput input) {
        if (!sprinting && input.hasForwardImpulse() && !input.shift() && input.sprint() && isSprintingPossible()) {
            sprinting = true;
        }
        if (sprinting && (!isSprintingPossible() || !input.hasForwardImpulse()
                || (horizontalCollision && !minorHorizontalCollision))) {
            sprinting = false;
        }
    }

    /**
     * 对齐 LocalPlayer.isSprintingPossible：未受限且可进行剧烈运动时允许疾跑。
     * （官方骑乘时改判载具、浅水与飞行需额外许可；机器人无此场景，
     * 缺失的饥饿/失明数据由门控字段近似。）
     */
    private boolean isSprintingPossible() {
        return !mobilityRestricted && canDoExhaustiveManoeuvres;
    }

    /**
     * 输入向量处理，对齐 LocalPlayer.modifyInput：
     * 归一化（KeyboardInput）→ 0.98 缩放 → 潜行 0.3 缩放 → 方形修正。
     *
     * @return {xxa, zza}
     */
    private static double[] modifyInput(float leftImpulse, float forwardImpulse, boolean sneaking) {
        double vx = leftImpulse;
        double vy = forwardImpulse;
        double len = Math.sqrt(vx * vx + vy * vy);
        if (len < 1.0E-4) {
            return new double[]{0.0, 0.0};
        }
        vx /= len;
        vy /= len;

        vx *= 0.98;
        vy *= 0.98;
        if (sneaking) {
            vx *= SNEAKING_SPEED;
            vy *= SNEAKING_SPEED;
        }

        double scaledLen = Math.sqrt(vx * vx + vy * vy);
        if (scaledLen <= 0.0) {
            return new double[]{vx, vy};
        }
        double dirX = vx / scaledLen;
        double dirY = vy / scaledLen;
        double modifiedLength = Math.min(scaledLen * distanceToUnitSquare(dirX, dirY), 1.0);
        return new double[]{dirX * modifiedLength, dirY * modifiedLength};
    }

    /** 对齐 LocalPlayer.distanceToUnitSquare */
    private static double distanceToUnitSquare(double dirX, double dirY) {
        double absX = Math.abs(dirX);
        double absY = Math.abs(dirY);
        double tan = absY > absX ? absX / absY : absY / absX;
        return Math.sqrt(1.0 + tan * tan);
    }

    /**
     * 对齐 LivingEntity.jumpFromGround：y = max(getJumpPower, 当前y)，
     * 跳跃力不足（≤ 1E-5）时不跳；冲刺附加前向 0.2 动量。
     */
    private void jumpFromGround() {
        float jumpPower = getJumpPower();
        if (jumpPower > 1.0E-5F) {
            deltaY = Math.max((double) jumpPower, deltaY);
            if (sprinting) {
                float angle = yaw * ((float) Math.PI / 180F);
                deltaX += -Math.sin(angle) * SPRINT_JUMP_BOOST;
                deltaZ += Math.cos(angle) * SPRINT_JUMP_BOOST;
            }
        }
    }

    /** 对齐 LivingEntity.getJumpPower：JUMP_STRENGTH × 方块跳跃因子（无跳跃提升药水） */
    private float getJumpPower() {
        return JUMP_STRENGTH * getBlockJumpFactor();
    }

    // ------------------------------------------------------------------
    // 内部：碰撞脱困（LocalPlayer.moveTowardsClosestSpace / suffocatesAt）
    // ------------------------------------------------------------------

    /**
     * 对齐 LocalPlayer.moveTowardsClosestSpace（26.1.2 新版）：当给定水平角点
     * 所在的竖直柱内存在窒息方块时，找出水平四向中最近的非窒息邻居，
     * 并以 0.1/tick 把对应轴速度推向该方向（其余轴保留当前速度）。
     *
     * <p>官方通过 suffocatesAt（玩家碰撞盒与窒息方块形状相交）判定；
     * 本实现中"窒息方块"按完整碰撞体近似（见 {@link BlockNameTable#suffocates}）。</p>
     */
    private void moveTowardsClosestSpace(double checkX, double checkZ) {
        int bx = floorToInt(checkX);
        int bz = floorToInt(checkZ);
        if (!suffocatesAt(bx, bz)) {
            return;
        }

        double xd = checkX - (double) bx;
        double zd = checkZ - (double) bz;

        // 官方循环顺序：WEST → EAST → NORTH → SOUTH（严格小于才更新）
        int dir = -1;
        double closest = Double.MAX_VALUE;
        if (xd < closest && !suffocatesAt(bx - 1, bz)) {
            closest = xd;
            dir = 0;
        }
        double eastDistance = 1.0 - xd;
        if (eastDistance < closest && !suffocatesAt(bx + 1, bz)) {
            closest = eastDistance;
            dir = 1;
        }
        if (zd < closest && !suffocatesAt(bx, bz - 1)) {
            closest = zd;
            dir = 2;
        }
        double southDistance = 1.0 - zd;
        if (southDistance < closest && !suffocatesAt(bx, bz + 1)) {
            dir = 3;
        }

        if (dir == 0) {
            deltaX = -0.1;
        } else if (dir == 1) {
            deltaX = 0.1;
        } else if (dir == 2) {
            deltaZ = -0.1;
        } else if (dir == 3) {
            deltaZ = 0.1;
        }
    }

    /**
     * 对齐 LocalPlayer.suffocatesAt：检查以 (bx, bz) 为底、玩家碰撞盒 y 范围为高
     * 的柱状区域（四周收缩 1E-7）是否与窒息方块相交。
     */
    private boolean suffocatesAt(int bx, int bz) {
        AABB box = boundingBox();
        double minY = box.minY + 1.0E-7;
        double maxY = box.maxY - 1.0E-7;
        int yStart = floorToInt(minY);
        int yEnd = floorToInt(maxY);
        for (int y0 = yStart; y0 <= yEnd; y0++) {
            if ((double) y0 >= maxY) {
                break;
            }
            int stateId = world.getBlockStateId(bx, y0, bz);
            if (stateId == RunningWorld.UNLOADED || stateId == RunningWorld.AIR) {
                continue;
            }
            if (!blockNames.suffocates(stateId)) {
                continue;
            }
            if ((double) y0 + blockNames.collisionHeight(stateId) > minY) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 内部：行进（LivingEntity.travelInAir）
    // ------------------------------------------------------------------

    /**
     * 对齐 Player.getSpeed：移动速度属性值，疾跑时含 SPEED_MODIFIER_SPRINTING
     * （+30% 乘算修饰符），0.1 → 0.13。
     */
    private float getSpeed() {
        return MOVEMENT_SPEED * (sprinting ? SPRINT_SPEED_MULTIPLIER : 1.0F);
    }

    /**
     * 对齐 Entity.getBlockSpeedFactor：取当前位置方块的速度因子；
     * 为 1.0 且非水/气泡柱时回落到脚下方块（getOnPos(0.500001F)）。
     */
    private float getBlockSpeedFactor() {
        int hereState = world.getBlockStateId(floorToInt(x), floorToInt(y), floorToInt(z));
        float hereFactor = blockNames.speedFactor(hereState);
        String hereName = blockNames.nameOf(hereState);
        if (!"water".equals(hereName) && !"bubble_column".equals(hereName)) {
            if (hereFactor == 1.0F) {
                int belowState = world.getBlockStateId(floorToInt(x), floorToInt(y - 0.500001), floorToInt(z));
                return blockNames.speedFactor(belowState);
            }
            return hereFactor;
        }
        return hereFactor;
    }

    /**
     * 对齐 Entity.getBlockJumpFactor：取当前位置方块的跳跃因子；
     * 为 1.0 时回落到脚下方块（getOnPos(0.500001F)）。
     */
    private float getBlockJumpFactor() {
        float hereFactor = blockNames.jumpFactor(world.getBlockStateId(floorToInt(x), floorToInt(y), floorToInt(z)));
        if (hereFactor == 1.0F) {
            return blockNames.jumpFactor(world.getBlockStateId(floorToInt(x), floorToInt(y - 0.500001), floorToInt(z)));
        }
        return hereFactor;
    }

    /** 对齐 LivingEntity.travelInAir（无流体、无攀爬、无药水） */
    private void travelInAir(double xxa, double zza) {
        // 脚下方块：getBlockPosBelowThatAffectsMyMovement = getOnPos(0.500001F)
        int belowX = floorToInt(x);
        int belowY = floorToInt(y - 0.500001);
        int belowZ = floorToInt(z);
        boolean belowLoaded = world.isChunkLoaded(belowX >> 4, belowZ >> 4);
        int belowState = world.getBlockStateId(belowX, belowY, belowZ);

        float blockFriction = onGround ? (float) blockNames.friction(belowState) : 1.0F;
        float friction = blockFriction * (float) FRICTION_BASE;

        // handleRelativeFrictionAndCalculateMovement：先施加输入速度再碰撞移动
        // getFrictionInfluencedSpeed：地面 = getSpeed（0.1，疾跑 0.13）×(0.21600002 / f³)；
        // 空中 = 0.02 / 冲刺 0.025999999
        float speed;
        if (onGround) {
            float f3 = blockFriction * blockFriction * blockFriction;
            speed = getSpeed() * (0.21600002F / f3);
        } else {
            speed = sprinting ? AIR_SPEED_SPRINTING : AIR_SPEED;
        }
        moveRelative(speed, xxa, zza);
        move(new Vec3(deltaX, deltaY, deltaZ));

        // 重力（脚下方块未加载时对齐客户端：仅缓慢下移、不施加重力）
        double movementY;
        if (!belowLoaded) {
            movementY = y > world.getMinY() ? -0.1 : 0.0;
        } else {
            movementY = deltaY - GRAVITY;
        }

        // 摩擦
        deltaX *= friction;
        deltaY = movementY * VERTICAL_DRAG;
        deltaZ *= friction;
    }

    /** 对齐 Entity.moveRelative：把输入向量按朝向旋转后叠加到速度 */
    private void moveRelative(float speed, double xxa, double zza) {
        Vec3 added = getInputVector(new Vec3(xxa, 0.0, zza), speed, yaw);
        deltaX += added.x;
        deltaY += added.y;
        deltaZ += added.z;
    }

    /** 对齐 Entity.getInputVector */
    private static Vec3 getInputVector(Vec3 input, float speed, float yRot) {
        double length = input.lengthSqr();
        if (length < 1.0E-7) {
            return Vec3.ZERO;
        }
        Vec3 movement = (length > 1.0 ? input.normalize() : input).scale(speed);
        float sin = (float) Math.sin(yRot * ((float) Math.PI / 180F));
        float cos = (float) Math.cos(yRot * ((float) Math.PI / 180F));
        return new Vec3(movement.x * cos - movement.z * sin, movement.y,
                movement.z * cos + movement.x * sin);
    }

    /** 对齐 Entity.move(MoverType.SELF, delta) 的简化实现 */
    private void move(Vec3 delta) {
        AABB box = boundingBox();
        Vec3 movement = collide(delta, box);

        double movementLengthSqr = movement.lengthSqr();
        if (movementLengthSqr > 1.0E-7 || delta.lengthSqr() - movementLengthSqr < 1.0E-7) {
            x += movement.x;
            y += movement.y;
            z += movement.z;
        }

        // 水平碰撞状态（x/z 用 Mth.equal 容差）
        boolean xCollision = !equal(delta.x, movement.x);
        boolean zCollision = !equal(delta.z, movement.z);
        horizontalCollision = xCollision || zCollision;

        // 垂直碰撞状态：官方用精确比较（无容差）；LocalPlayer 恒为本地权威实例，条件恒真
        boolean yCollision = delta.y != movement.y;
        boolean verticalCollisionBelow = yCollision && delta.y < 0.0;
        onGround = verticalCollisionBelow;

        if (horizontalCollision) {
            // 擦墙判定（LocalPlayer 覆写：与输入方向夹角 < 8° 时不中断疾跑）
            minorHorizontalCollision = isHorizontalCollisionMinor(movement);
            // 水平碰撞轴速度归零
            if (xCollision) {
                deltaX = 0.0;
            }
            if (zCollision) {
                deltaZ = 0.0;
            }
        } else {
            minorHorizontalCollision = false;
        }

        // 落地垂直速度归零（对齐 Block.updateEntityMovementAfterFallOn 默认行为）
        if (delta.y != movement.y) {
            deltaY = 0.0;
        }

        // 方块速度因子（对齐 Entity.move 末尾：速度 × (speedFactor, 1, speedFactor)）
        float blockSpeedFactor = getBlockSpeedFactor();
        deltaX *= blockSpeedFactor;
        deltaZ *= blockSpeedFactor;
    }

    /**
     * 对齐 LocalPlayer.isHorizontalCollisionMinor：把本 tick 输入向量按朝向旋转到
     * 世界坐标系，与实际位移（碰撞解算结果）比较，夹角小于约 8° 时视为擦墙碰撞。
     */
    private boolean isHorizontalCollisionMinor(Vec3 movement) {
        float yRotInRadians = yaw * ((float) Math.PI / 180F);
        double yRotSin = (float) Math.sin(yRotInRadians);
        double yRotCos = (float) Math.cos(yRotInRadians);
        double globalXA = xxa * yRotCos - zza * yRotSin;
        double globalZA = zza * yRotCos + xxa * yRotSin;
        double aLengthSqr = globalXA * globalXA + globalZA * globalZA;
        double movementLengthSqr = movement.x * movement.x + movement.z * movement.z;
        if (aLengthSqr < (double) MINOR_COLLISION_MIN_LENGTH_SQR
                || movementLengthSqr < (double) MINOR_COLLISION_MIN_LENGTH_SQR) {
            return false;
        }
        double dotProduct = globalXA * movement.x + globalZA * movement.z;
        double angle = Math.acos(dotProduct / Math.sqrt(aLengthSqr * movementLengthSqr));
        return angle < (double) MINOR_COLLISION_MAX_ANGLE;
    }

    /** 玩家碰撞盒：0.6 × 1.8，以脚底中心为基准（EntityDimensions.makeBoundingBox） */
    private AABB boundingBox() {
        return AABB.ofEntity(x, y, z, PLAYER_WIDTH, PLAYER_HEIGHT);
    }

    /** 对齐 MathHelper.floor */
    private static int floorToInt(double value) {
        return (int) Math.floor(value);
    }

    /** 对齐 Mth.equal（1.0E-5 容差） */
    private static boolean equal(double a, double b) {
        return Math.abs(b - a) < 1.0E-5;
    }

    // ------------------------------------------------------------------
    // 内部：自动跳跃（LocalPlayer.updateAutoJump / canAutoJump）
    // ------------------------------------------------------------------

    /**
     * 对齐 LocalPlayer.canAutoJump：开关开启、无进行中的自动跳跃、着地、
     * 未按潜行键（isStayingOnGroundSurface）、存在移动输入、脚下方块跳跃因子 ≥ 1.0
     * （机器人为本地权威实例，无乘客检查恒成立）。
     */
    private boolean canAutoJump(PlayerInput input) {
        return autoJumpEnabled && autoJumpTime <= 0 && onGround && !input.shift()
                && input.hasMovement() && getBlockJumpFactor() >= 1.0F;
    }

    /**
     * 对齐 LocalPlayer.updateAutoJump：物理移动之后向前探测障碍，若障碍高度
     * 落在 (0.5, 1.2] 区间且上方空间足够，则设置 autoJumpTime，下一 tick 注入
     * 一帧跳跃输入。
     *
     * <p>探测方式：以本 tick 实际位移为方向（位移近零时退回输入意图方向），
     * 从脚上方 0.51 高度向左右各偏移半宽展开两条探测线段，扫描前瞻测试盒内的
     * 方块碰撞盒；同时要求当前头顶两格无碰撞形状。移动方向与水平视线方向夹角
     * 过大（点积 &lt; -0.15，如后退）时不触发。</p>
     *
     * @param xa    本 tick 实际水平位移 x（move 前后差，对齐官方传参）
     * @param za    本 tick 实际水平位移 z
     * @param input 本 tick 输入帧（位移近零时构造回退方向用）
     */
    private void updateAutoJump(float xa, float za, PlayerInput input) {
        if (!canAutoJump(input)) {
            return;
        }

        Vec3 moveBegin = new Vec3(x, y, z);
        Vec3 moveEnd = moveBegin.add((double) xa, 0.0, (double) za);
        double diffX = (double) xa;
        double diffZ = (double) za;
        float currentSpeed = getSpeed();
        float moveDistSq = (float) (diffX * diffX + diffZ * diffZ);
        if (moveDistSq <= 0.001F) {
            // 实际位移近零（被障碍挡住）：退回输入意图方向（对齐官方的 getMoveVector 分支）
            float inputX = input.leftImpulse();
            float inputZ = input.forwardImpulse();
            float inputLen = (float) Math.sqrt((double) (inputX * inputX + inputZ * inputZ));
            if (inputLen < 1.0E-4F) {
                return;
            }
            float inputXa = currentSpeed * (inputX / inputLen);
            float inputZa = currentSpeed * (inputZ / inputLen);
            float sin = (float) Math.sin((double) (yaw * ((float) Math.PI / 180F)));
            float cos = (float) Math.cos((double) (yaw * ((float) Math.PI / 180F)));
            diffX = (double) (inputXa * cos - inputZa * sin);
            diffZ = (double) (inputZa * cos + inputXa * sin);
            moveDistSq = (float) (diffX * diffX + diffZ * diffZ);
            if (moveDistSq <= 0.001F) {
                return;
            }
        }

        float moveDistInverted = (float) (1.0 / Math.sqrt((double) moveDistSq));
        double dirX = diffX * (double) moveDistInverted;
        double dirZ = diffZ * (double) moveDistInverted;

        // 水平视线方向点积（对齐 Entity.getForward → Vec3.directionFromRotation 的 x/z 分量）
        float viewYaw = -yaw * ((float) Math.PI / 180F) - (float) Math.PI;
        float viewYawSin = (float) Math.sin((double) viewYaw);
        float viewYawCos = (float) Math.cos((double) viewYaw);
        float viewPitchCos = -(float) Math.cos((double) (-pitch * ((float) Math.PI / 180F)));
        double forwardX = (double) (viewYawSin * viewPitchCos);
        double forwardZ = (double) (viewYawCos * viewPitchCos);
        float facingDot = (float) (forwardX * dirX + forwardZ * dirZ);
        if (facingDot < -0.15F) {
            return;
        }

        // 头顶两格必须无碰撞形状（官方 ceilingPos 双重检查）
        int ceilingX = floorToInt(x);
        int ceilingY = floorToInt(boundingBox().maxY);
        int ceilingZ = floorToInt(z);
        if (hasCollisionShapeAt(ceilingX, ceilingY, ceilingZ)
                || hasCollisionShapeAt(ceilingX, ceilingY + 1, ceilingZ)) {
            return;
        }

        // 前瞻距离 = max(速度 × 7, 本 tick 位移长度)；探测线段从脚上方 0.51 展开
        float lookAheadDist = Math.max(currentSpeed * 7.0F, 1.0F / moveDistInverted);
        Vec3 segmentEnd = moveEnd.add(dirX * (double) lookAheadDist, 0.0, dirZ * (double) lookAheadDist);
        AABB testBox = new AABB(moveBegin.x, moveBegin.y, moveBegin.z,
                segmentEnd.x, segmentEnd.y + (double) PLAYER_HEIGHT, segmentEnd.z)
                .inflate((double) PLAYER_WIDTH, 0.0, (double) PLAYER_WIDTH);
        Vec3 segBegin = moveBegin.add(0.0, 0.51, 0.0);
        Vec3 segEnd = segmentEnd.add(0.0, 0.51, 0.0);
        double halfWidth = (double) (PLAYER_WIDTH * 0.5F);
        Vec3 rightOffset = new Vec3(-dirZ * halfWidth, 0.0, dirX * halfWidth);
        Vec3 leftSegBegin = segBegin.subtract(rightOffset);
        Vec3 leftSegEnd = segEnd.subtract(rightOffset);
        Vec3 rightSegBegin = segBegin.add(rightOffset);
        Vec3 rightSegEnd = segEnd.add(rightOffset);

        float jumpHeight = AUTO_JUMP_MAX_HEIGHT;
        float obstacleHeight = Float.MIN_VALUE;
        for (AABB shape : collectBlockBoxes(testBox)) {
            if (!shape.intersects(testBox)) {
                continue;
            }
            if (!intersectsPointBox(shape, leftSegBegin, leftSegEnd)
                    && !intersectsPointBox(shape, rightSegBegin, rightSegEnd)) {
                continue;
            }

            // 取第一个命中的障碍盒（官方 break 语义）：记录高度并检查其上方空间
            obstacleHeight = (float) shape.maxY;
            int obstacleBx = floorToInt((shape.minX + shape.maxX) * 0.5);
            int obstacleBy = floorToInt((shape.minY + shape.maxY) * 0.5);
            int obstacleBz = floorToInt((shape.minZ + shape.maxZ) * 0.5);
            for (int steps = 1; (float) steps < jumpHeight; steps++) {
                int aboveY = obstacleBy + steps;
                int aboveState = world.getBlockStateId(obstacleBx, aboveY, obstacleBz);
                if (aboveState == RunningWorld.UNLOADED || aboveState == RunningWorld.AIR
                        || !blockNames.hasCollision(aboveState)) {
                    continue;
                }
                obstacleHeight = (float) (blockNames.collisionHeight(aboveState) + (double) aboveY);
                if ((double) obstacleHeight - y > (double) jumpHeight) {
                    return;
                }
            }
            break;
        }

        if (obstacleHeight != Float.MIN_VALUE) {
            float yDelta = (float) ((double) obstacleHeight - y);
            if (yDelta > 0.5F && yDelta <= jumpHeight) {
                this.autoJumpTime = 1;
            }
        }
    }

    /** 指定方块位置是否存在碰撞形状（对齐 getCollisionShape().isEmpty()；未加载区块视为空气） */
    private boolean hasCollisionShapeAt(int bx, int by, int bz) {
        int stateId = world.getBlockStateId(bx, by, bz);
        if (stateId == RunningWorld.UNLOADED || stateId == RunningWorld.AIR) {
            return false;
        }
        return blockNames.hasCollision(stateId);
    }

    /** 两点构成的包围盒与方块碰撞盒是否相交（对齐 AABB.intersects(Vec3, Vec3)） */
    private static boolean intersectsPointBox(AABB box, Vec3 begin, Vec3 end) {
        return box.intersects(new AABB(begin.x, begin.y, begin.z, end.x, end.y, end.z));
    }

    // ------------------------------------------------------------------
    // 内部：碰撞解算（对齐 Entity.collide / collideWithShapes）
    // ------------------------------------------------------------------

    /** 对齐 Entity.collide：普通解算 + maxUpStep=0.6 自动上台阶 */
    private Vec3 collide(Vec3 delta, AABB box) {
        Vec3 movementStep = delta.lengthSqr() == 0.0 ? delta : collideBoundingBox(delta, box);

        boolean xCollision = delta.x != movementStep.x;
        boolean yCollision = delta.y != movementStep.y;
        boolean zCollision = delta.z != movementStep.z;
        boolean onGroundAfterCollision = yCollision && delta.y < 0.0;

        if (MAX_UP_STEP > 0.0F && (onGroundAfterCollision || onGround) && (xCollision || zCollision)) {
            AABB groundedAABB = onGroundAfterCollision ? box.move(0.0, movementStep.y, 0.0) : box;
            AABB stepUpAABB = groundedAABB.expandTowards(delta.x, MAX_UP_STEP, delta.z);
            if (!onGroundAfterCollision) {
                stepUpAABB = stepUpAABB.expandTowards(0.0, -1.0E-5, 0.0);
            }

            // 收集上台阶搜索范围内的碰撞形状，供候选高度提取与逐候选解算复用（对齐 Entity.collide）
            List<AABB> stepColliders = collectBlockBoxes(stepUpAABB);
            double[] candidates = collectStepUpHeights(stepColliders, groundedAABB.minY, movementStep.y);
            for (double candidate : candidates) {
                Vec3 stepFromGround = collideWithShapes(new Vec3(delta.x, candidate, delta.z), groundedAABB, stepColliders);
                if (stepFromGround.horizontalDistanceSqr() > movementStep.horizontalDistanceSqr()) {
                    double distanceToGround = box.minY - groundedAABB.minY;
                    return stepFromGround.subtract(0.0, distanceToGround, 0.0);
                }
            }
        }
        return movementStep;
    }

    /** 对包围盒扩张范围内收集全部方块碰撞盒后逐轴解算（对齐 Entity.collideBoundingBox） */
    private Vec3 collideBoundingBox(Vec3 movement, AABB box) {
        // 未加载区块的方块不出现在碰撞列表中（当作空气，与客户端缺区块时一致）
        return collideWithShapes(movement, box, collectBlockBoxes(box.expandTowards(movement)));
    }

    /** 对齐 Entity.collideWithShapes：按 axisStepOrder 逐轴解算 */
    private static Vec3 collideWithShapes(Vec3 movement, AABB box, List<AABB> shapes) {
        if (shapes.isEmpty()) {
            return movement;
        }
        double rx = 0.0;
        double ry = 0.0;
        double rz = 0.0;
        for (int axis : axisStepOrder(movement)) {
            double amount = axis == 0 ? movement.x : axis == 1 ? movement.y : movement.z;
            if (amount == 0.0) {
                continue;
            }
            double resolved = clipAxis(axis, box.move(rx, ry, rz), shapes, amount);
            if (axis == 0) {
                rx = resolved;
            } else if (axis == 1) {
                ry = resolved;
            } else {
                rz = resolved;
            }
        }
        return new Vec3(rx, ry, rz);
    }

    /**
     * 轴步进顺序（对齐 Direction.axisStepOrder：Y 轴永远优先，
     * 再按 |x| 与 |z| 的大小决定剩余顺序）。
     */
    private static int[] axisStepOrder(Vec3 movement) {
        return Math.abs(movement.x) < Math.abs(movement.z)
                ? new int[]{1, 2, 0}
                : new int[]{1, 0, 2};
    }

    /** 沿单轴收紧移动量（对齐 VoxelShape.collide 语义；axis：0=X, 1=Y, 2=Z） */
    private static double clipAxis(int axis, AABB box, List<AABB> shapes, double amount) {
        if (Math.abs(amount) < 1.0E-7) {
            return 0.0;
        }
        for (AABB shape : shapes) {
            if (Math.abs(amount) < 1.0E-7) {
                break;
            }
            double gap;
            if (axis == 0) {
                if (box.maxY <= shape.minY || box.minY >= shape.maxY
                        || box.maxZ <= shape.minZ || box.minZ >= shape.maxZ) {
                    continue;
                }
                gap = amount > 0.0 ? shape.minX - box.maxX : shape.maxX - box.minX;
            } else if (axis == 1) {
                if (box.maxX <= shape.minX || box.minX >= shape.maxX
                        || box.maxZ <= shape.minZ || box.minZ >= shape.maxZ) {
                    continue;
                }
                gap = amount > 0.0 ? shape.minY - box.maxY : shape.maxY - box.minY;
            } else {
                if (box.maxX <= shape.minX || box.minX >= shape.maxX
                        || box.maxY <= shape.minY || box.minY >= shape.maxY) {
                    continue;
                }
                gap = amount > 0.0 ? shape.minZ - box.maxZ : shape.maxZ - box.minZ;
            }
            if (amount > 0.0) {
                if (gap >= -1.0E-7 && gap < amount) {
                    amount = gap;
                }
            } else if (gap <= 1.0E-7 && gap > amount) {
                amount = gap;
            }
        }
        return amount;
    }

    /** 收集指定范围内的方块碰撞盒（未加载区块视为无障碍；楼梯双盒、半砖 0.5 高近似） */
    private List<AABB> collectBlockBoxes(AABB area) {
        List<AABB> boxes = new ArrayList<>();
        int minBX = floorToInt(area.minX);
        int maxBX = floorToInt(area.maxX);
        int minBZ = floorToInt(area.minZ);
        int maxBZ = floorToInt(area.maxZ);
        int minBY = Math.max(floorToInt(area.minY), world.getMinY());
        int maxBY = Math.min(floorToInt(area.maxY), world.getMinY() + world.getHeight() - 1);

        for (int bx = minBX; bx <= maxBX; bx++) {
            for (int bz = minBZ; bz <= maxBZ; bz++) {
                for (int by = minBY; by <= maxBY; by++) {
                    int stateId = world.getBlockStateId(bx, by, bz);
                    if (stateId == RunningWorld.UNLOADED) {
                        continue;
                    }
                    blockNames.addCollisionBoxes(stateId, bx, by, bz, boxes);
                }
            }
        }
        return boxes;
    }

    /**
     * 从方块碰撞盒列表中收集上台阶候选高度（盒体上下表面相对 baseY 的高度），
     * 过滤到 [0, MAX_UP_STEP] 区间并升序排序
     * （对齐 Entity.collectCandidateStepUpHeights）。
     */
    private static double[] collectStepUpHeights(List<AABB> colliders, double baseY, double skipHeight) {
        List<Double> candidates = new ArrayList<>(4);
        for (AABB collider : colliders) {
            addStepCandidate(candidates, collider.minY - baseY, skipHeight);
            addStepCandidate(candidates, collider.maxY - baseY, skipHeight);
        }
        candidates.sort(Double::compare);
        double[] result = new double[candidates.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = candidates.get(i);
        }
        return result;
    }

    private static void addStepCandidate(List<Double> candidates, double relative, double skipHeight) {
        if (relative < 0.0 || relative > MAX_UP_STEP || relative == skipHeight) {
            return;
        }
        if (!candidates.contains(relative)) {
            candidates.add(relative);
        }
    }

    // ------------------------------------------------------------------
    // 内部：状态发布
    // ------------------------------------------------------------------

    /** 把物理状态镜像到 BotState（一次加锁批量写入） */
    private void publish() {
        state.applyPhysics(x, y, z, yaw, pitch, deltaX, deltaY, deltaZ,
                onGround, horizontalCollision, sprinting);
    }
}
