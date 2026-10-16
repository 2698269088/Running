package top.mcocet.running.bot;

import top.mcocet.running.math.AABB;
import top.mcocet.running.math.Vec3;
import top.mcocet.running.world.BlockNameTable;
import top.mcocet.running.world.EntityTracker;
import top.mcocet.running.world.RunningWorld;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家刻物理模拟，逐行对齐 Minecraft 官方客户端实现。
 *
 * <p>对齐来源（Minecraft 26.1.2 反编译源码）：
 * <ul>
 *   <li>{@code Player.tick / LocalPlayer.aiStep} —— tick 时间线（updateIsUnderwater →
 *       updateFluidInteraction → updateSwimming → … → updatePlayerPose）与冲刺
 *       三段式状态机（重复触发窗口、canStartSprinting、停止条件、4 角点碰撞脱困）、
 *       modifyInput（归一化 0.98 缩放、潜行 0.3 缩放、方形修正）；</li>
 *   <li>{@code LivingEntity.aiStep / travel / travelInAir / travelInWater / travelInLava}
 *       —— 速度归零阈值、跳跃流体分支（jumpInLiquid / goDownInWater / jumpOutOfFluid）、
 *       重力 0.08、垂直阻力 0.98、地面摩擦 blockFriction×0.91、
 *       getFrictionInfluencedSpeed（0.21600002/f³）、getFluidFallingAdjustedMovement；</li>
 *   <li>{@code EntityFluidInteraction / FlowingFluid.getFlow} —— 逐格流体扫描
 *       （接触高度、眼部浸没、水流冲量；含 FALLING 下落加速与 isSolidFace 面支撑查询）；</li>
 *   <li>{@code Entity.move / collide / Player.maybeBackOffFromEdge} —— 逐轴碰撞解算
 *       （axisStepOrder：Y 轴优先）、maxUpStep=0.6 自动上台阶、潜行防走边回退、
 *       updateEntityMovementAfterFallOn（粘液块弹跳）与 stepOn（粘液块减速）；</li>
 *   <li>{@code Player.updatePlayerPose / Avatar.POSES} —— 姿势系统（站立 1.8 / 蹲伏 1.5 /
 *       游泳 0.6）驱动碰撞盒与眼高；</li>
 *   <li>{@code LocalPlayer.updateAutoJump / canAutoJump} —— 自动跳跃（实际位移或
 *       输入意图方向的前瞻障碍探测、头顶空间检查、autoJumpTime 延迟注入；
 *       开关默认开启，可经命令调整）；</li>
 *   <li>{@code Entity.lerpMotion / setDeltaMovement / addDeltaMovement} —— 击退回写
 *       （自身速度包整体替换速度、爆炸 playerKnockback 叠加；非有限向量忽略）；</li>
 *   <li>{@code LivingEntity.pushEntities / Entity.push(Entity)} —— 被附近实体推挤
 *       （推方资格见 {@code EntitySizes.pushesLocalPlayer}，速度叠加公式逐行对齐）；</li>
 *   <li>{@code FoodData / ServerPlayer.checkMovementStatistics / jumpFromGround} —— 饥饿与
 *       饱和度消耗（疾跑 0.1/m、游泳与水下 0.01/m、行走/潜行 0、疾跑跳 0.2、普通跳 0.05；
 *       exhaustion>4.0 先扣饱和再扣食物；饥饿药水每 tick 0.005×(amp+1)）；</li>
 *   <li>{@code LivingEntity.getJumpPower / getEffectiveGravity / travelInAir / travelInWater}
 *       —— 药水效果（跳跃提升 +0.1×(amp+1) 跳跃力与 +1/级 安全摔落高度、缓降下落重力
 *       min(g,0.01)、漂浮 movementY 趋近 0.05×(amp+1)、海豚恩惠水中衰减 0.96、
 *       速度/缓慢经 ADD_MULTIPLIED_TOTAL 作用于移动速度属性、失明禁用疾跑）；</li>
 *   <li>{@code Entity.checkFallDamage / LivingEntity.causeFallDamage / calculateFallDamage}
 *       —— 摔落伤害（下落累计、着地结算；伤害 = floor((fallDistance+1e-6 − 安全高度)
 *       × 摔落乘数)，安全高度默认 3.0 且跳跃提升每级 +1；水/岩浆/缓降/漂浮时清零摔落距离）。</li>
 * </ul></p>
 *
 * <p>已知简化（相对于官方客户端）：
 * <ul>
 *   <li>不支持梯子/藤蔓攀爬、鞘翅飞行与骑乘；</li>
 *   <li>方块/流体形状按 {@link BlockNameTable} 的近似模型（完整立方体 / 半砖 /
 *       楼梯双盒 / 栅栏 1.5 / 流体高度与流量）；</li>
 *   <li>摔落伤害为本地结算（仅日志与寻路高度决策参考），真实伤害由服务器裁定；
 *       饥饿/饱和为本地镜像（进食与真实饱食度由服务器下发，本镜像用于疾跑门控）。</li>
 * </ul></p>
 *
 * <p>线程约定：物理状态仅由移动引擎线程推进；{@link #reset} 与击退回写
 * （{@link #setDeltaMovement} / {@link #addDeltaMovement}）允许网络线程调用
 * （服务器传送与速度同步）。所有公共方法均以同一把锁保护，{@link #publish()}
 * 把权威状态镜像到 {@link BotState} 供命令与控制台读取。</p>
 */
public final class PlayerPhysics {
    private static final Logger log = LoggerFactory.getLogger(PlayerPhysics.class.getSimpleName());

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
    /** 垂直阻力（LivingEntity.travelInAir 的 0.98F，按官方顺序先取 float 常量再拓宽为 double） */
    public static final double VERTICAL_DRAG = (double) 0.98F;
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

    /** 非冲刺水中水平衰减（LivingEntity.getWaterSlowDown 的 0.8F） */
    public static final float WATER_SLOW_DOWN = 0.8F;
    /** 冲刺水中水平衰减（travelInWater：sprinting ? 0.9F : 0.8F） */
    public static final float SPRINT_WATER_SLOW_DOWN = 0.9F;
    /** 流体中水平加速度（travelInWater / travelInLava 的 0.02F） */
    public static final float FLUID_SPEED = 0.02F;
    /** 水面跳跃阈值（Entity.getFluidJumpThreshold；玩家眼高 ≥ 0.4 时恒 0.4） */
    public static final double FLUID_JUMP_THRESHOLD = 0.4;
    /** 流体落下重力除数（getFluidFallingAdjustedMovement 的 baseGravity / 16） */
    public static final double FLUID_GRAVITY_DIVISOR = 16.0;
    /** 流体下落微调基准（getFluidFallingAdjustedMovement 的 0.005） */
    public static final double FLUID_FALL_BASE = 0.005;
    /** 流体下落微调触发阈值（|movement.y - 0.005| >= 0.003） */
    public static final double FLUID_FALL_EPSILON = 0.003;
    /** 流体下落微调目标值（yd = -0.003） */
    public static final double FLUID_FALL_ADJUST = -0.003;
    /** 水流冲量施加比例（updateFluidInteraction：水 0.014） */
    public static final double WATER_PUSH_SCALE = 0.014;
    /** 岩浆流冲量施加比例（updateFluidInteraction：非快岩浆 0.0023333333333333335） */
    public static final double LAVA_PUSH_SCALE = 0.0023333333333333335;
    /** 水流冲量累加生效下限·平方（Tracker.applyCurrentTo 的 1.0E-5F） */
    public static final double FLUID_FLOW_MIN_SQR = 1.0E-5;
    /** 低速水流冲量提升目标长度（applyCurrentTo 的 0.0045000000000000005） */
    public static final double FLUID_MIN_IMPULSE = 0.0045000000000000005;
    /** 低速水流冲量提升的旧速度阈值（applyCurrentTo 的 0.003） */
    public static final double FLUID_SLOW_MOVEMENT = 0.003;
    /** 流体扫描盒收缩量（Entity.getFluidInteractionBox 的 deflate(0.001)） */
    public static final double FLUID_BOX_DEFLATE = 0.001;
    /** 流体下落底面修正（FlowingFluid.getFlow 的 0.8888889F） */
    public static final float FLUID_FALLING_BASE = 0.8888889F;

    /** 姿势编码：站立（0.6×1.8，眼高 1.62） */
    public static final byte POSE_STANDING = 0;
    /** 姿势编码：蹲伏（0.6×1.5，眼高 1.27） */
    public static final byte POSE_CROUCHING = 1;
    /** 姿势编码：游泳/匍匐（0.6×0.6，眼高 0.4） */
    public static final byte POSE_SWIMMING = 2;

    /** 蹲伏碰撞盒高度（Avatar.POSES：CROUCHING） */
    public static final float CROUCHING_HEIGHT = 1.5F;
    /** 游泳碰撞盒高度（Avatar.POSES：SWIMMING） */
    public static final float SWIMMING_HEIGHT = 0.6F;
    /** 蹲伏眼高（Avatar.POSES：CROUCHING） */
    public static final float EYE_HEIGHT_CROUCHING = 1.27F;
    /** 游泳眼高（Avatar.POSES：SWIMMING） */
    public static final float EYE_HEIGHT_SWIMMING = 0.4F;

    /** 双击疾跑窗口（Options.sprintWindow 默认 7 tick） */
    public static final int SPRINT_WINDOW_TICKS = 7;

    /** 潜行防走边逐轴回退步长（Player.maybeBackOffFromEdge 的 0.05） */
    public static final double EDGE_BACKOFF_STEP = 0.05;

    // ---- 饥饿与饱和（对齐 FoodData / FoodConstants / ServerPlayer.checkMovementStatistics） ----
    /** 饥饿值上限（FoodConstants.MAX_FOOD） */
    public static final int MAX_FOOD = 20;
    /** 饱和度上限（FoodConstants.MAX_SATURATION） */
    public static final float MAX_SATURATION = 20.0F;
    /** 初始饱和度（FoodConstants.START_SATURATION） */
    public static final float START_SATURATION = 5.0F;
    /** 耗尽阈值（FoodConstants.EXHAUSTION_DROP）：超过后先扣饱和再扣食物 */
    public static final float EXHAUSTION_DROP = 4.0F;
    /** 疾跑每米消耗（EXHAUSTION_SPRINT / checkMovementStatistics SPRINT_ONE_CM） */
    public static final float EXHAUSTION_SPRINT_PER_M = 0.1F;
    /** 游泳/水下每米消耗（EXHAUSTION_SWIM / checkMovementStatistics SWIM/WALK_UNDER_WATER） */
    public static final float EXHAUSTION_SWIM_PER_M = 0.01F;
    /** 普通跳跃消耗（EXHAUSTION_JUMP / ServerPlayer.jumpFromGround 非疾跑） */
    public static final float EXHAUSTION_JUMP = 0.05F;
    /** 疾跑跳跃消耗（EXHAUSTION_SPRINT_JUMP / jumpFromGround 疾跑） */
    public static final float EXHAUSTION_SPRINT_JUMP = 0.2F;
    /** 疾跑所需最低食物等级（SPRINT_LEVEL，hasEnoughFood 严格大于） */
    public static final int SPRINT_LEVEL = 6;
    /** 饥饿药水每 tick 消耗（HungerMobEffect.applyEffectTick：0.005×(amp+1)） */
    public static final float EXHAUSTION_HUNGER_EFFECT = 0.005F;

    // ---- 摔落伤害与药水（对齐 LivingEntity.calculateFallDamage / getJumpPower / getEffectiveGravity） ----
    /** 安全摔落高度默认值（Attributes.SAFE_FALL_DISTANCE 默认 3.0） */
    public static final float SAFE_FALL_DISTANCE = 3.0F;
    /** 摔落伤害乘数默认值（Attributes.FALL_DAMAGE_MULTIPLIER 默认 1.0） */
    public static final float FALL_DAMAGE_MULTIPLIER = 1.0F;
    /** 跳跃提升每级附加的安全摔落高度（MobEffects.JUMP_BOOST → SAFE_FALL_DISTANCE +1/级） */
    public static final float JUMP_BOOST_SAFE_FALL = 1.0F;
    /** 跳跃提升每级附加的跳跃力（LivingEntity.getJumpBoostPower：0.1×(amp+1)） */
    public static final float JUMP_BOOST_POWER = 0.1F;
    /** 缓降下落重力上限（LivingEntity.getEffectiveGravity：min(gravity, 0.01)） */
    public static final double SLOW_FALL_GRAVITY = 0.01;
    /** 漂浮每级目标上升速度（travelInAir：0.05×(amp+1)） */
    public static final double LEVITATION_RATE = 0.05;
    /** 漂浮趋近速率（travelInAir：×0.2） */
    public static final double LEVITATION_LERP = 0.2;
    /** 海豚恩惠水中水平衰减（travelInWater：slowDown = 0.96） */
    public static final float DOLPHINS_GRACE_SLOW_DOWN = 0.96F;
    /** 速度药水每级乘算修饰（MobEffects.SPEED → MOVEMENT_SPEED ADD_MULTIPLIED_TOTAL +0.2/级） */
    public static final float SPEED_EFFECT_PER_LEVEL = 0.2F;
    /** 缓慢药水每级乘算修饰（MobEffects.SLOWNESS → MOVEMENT_SPEED ADD_MULTIPLIED_TOTAL −0.15/级） */
    public static final float SLOWNESS_EFFECT_PER_LEVEL = -0.15F;

    /** 水平四向 X 步长（顺序 N,E,S,W，对齐 Direction.Plane.HORIZONTAL） */
    private static final int[] HORIZONTAL_STEP_X = {0, 1, 0, -1};
    /** 水平四向 Z 步长（顺序 N,E,S,W） */
    private static final int[] HORIZONTAL_STEP_Z = {-1, 0, 1, 0};
    /** 水平四向对应的面编码（对齐 {@link BlockNameTable#FACE_NORTH} 等） */
    private static final int[] HORIZONTAL_FACE = {BlockNameTable.FACE_NORTH, BlockNameTable.FACE_EAST,
            BlockNameTable.FACE_SOUTH, BlockNameTable.FACE_WEST};

    // ------------------------------------------------------------------
    // 依赖
    // ------------------------------------------------------------------

    private final BotState state;
    private final RunningWorld world;
    private final BlockNameTable blockNames;
    private final EntityTracker entityTracker;

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

    // ------------------------------------------------------------------
    // 流体交互状态（对齐 Entity.fluidInteraction 的水/岩浆两个 Tracker）
    // ------------------------------------------------------------------

    /** 水接触高度（Tracker.height：扫描格 max(y+height) − 脚底，WATER） */
    private double waterHeight;
    /** 岩浆接触高度（LAVA） */
    private double lavaHeight;
    /** 本次扫描：眼睛是否浸没在水/岩浆内（Tracker.eyesInside） */
    private boolean eyeInWater;
    private boolean eyeInLava;
    /** 本次扫描累积的水流向量与计数（用于 Player 均值冲量） */
    private double waterFlowX;
    private double waterFlowY;
    private double waterFlowZ;
    private int waterFlowCount;
    /** 本次扫描累积的岩浆流向量与计数 */
    private double lavaFlowX;
    private double lavaFlowY;
    private double lavaFlowZ;
    private int lavaFlowCount;

    // ------------------------------------------------------------------
    // 实体流体状态（对齐 Entity 字段；时间线见 tick() 步骤注释）
    // ------------------------------------------------------------------

    /** Entity.wasEyeInWater：baseTick 开头从上一 tick 扫描值保存（供 updateSwimming） */
    private boolean wasEyeInWater;
    /** Entity.wasTouchingWater：updateFluidInteraction 后 = 本 tick 扫描结果（isInWater） */
    private boolean touchingWater;
    /** Player.wasUnderwater：Player.tick 开头 = 上一 tick 扫描的眼部水判定（isUnderWater） */
    private boolean wasUnderwater;
    /** Entity.firstTick：首 tick 时 isInLava 恒 false、入水不触发水花 */
    private boolean firstTick = true;
    /** Entity 共享标志位 4：游泳（updateSwimming 维护） */
    private boolean swimming;

    // ------------------------------------------------------------------
    // 姿势与输入帧（对齐 Player.updatePlayerPose / LocalPlayer.aiStep）
    // ------------------------------------------------------------------

    /** 当前姿势：{@link #POSE_STANDING} / {@link #POSE_CROUCHING} / {@link #POSE_SWIMMING} */
    private byte pose = POSE_STANDING;
    /** LocalPlayer.crouching 字段（用上一 tick 输入帧的 shift 计算，供 isMovingSlowly） */
    private boolean crouching;
    /** 双击自动疾跑窗口（LocalPlayer.sprintTriggerTime） */
    private int sprintTriggerTime;
    /** 上一 tick 输入帧的 shift 键（wasShiftKeyDown） */
    private boolean lastKeyShift;
    /** 上一 tick 输入帧是否有前进冲量（hasForwardImpulse） */
    private boolean lastHasForwardImpulse;
    /** 摔落距离（对齐 Entity.fallDistance；下落累计、着地结算） */
    private double fallDistance;

    // ---- 饥饿与饱和（本地镜像，对齐 FoodData；进食与真实值由服务器下发） ----
    /** 食物等级（0..20） */
    private int foodLevel = MAX_FOOD;
    /** 饱和度（0..foodLevel） */
    private float saturationLevel = START_SATURATION;
    /** 消耗累计（exhaustionLevel，>4.0 时先扣饱和再扣食物） */
    private float exhaustionLevel;

    // ---- 药水效果放大器（-1 = 无；由服务器实体效果包注入，网络线程写、引擎线程读） ----
    /** 速度（SPEED）放大器 */
    private volatile int speedAmplifier = -1;
    /** 缓慢（SLOWNESS）放大器 */
    private volatile int slownessAmplifier = -1;
    /** 跳跃提升（JUMP_BOOST）放大器 */
    private volatile int jumpBoostAmplifier = -1;
    /** 缓降（SLOW_FALLING）放大器 */
    private volatile int slowFallingAmplifier = -1;
    /** 漂浮（LEVITATION）放大器 */
    private volatile int levitationAmplifier = -1;
    /** 失明（BLINDNESS）放大器 */
    private volatile int blindnessAmplifier = -1;
    /** 海豚恩惠（DOLPHINS_GRACE）放大器 */
    private volatile int dolphinsGraceAmplifier = -1;
    /** 饥饿（HUNGER）放大器 */
    private volatile int hungerAmplifier = -1;

    /** 摔落伤害开关（{@code physics.fall-damage}，默认开启；关闭时不结算摔落伤害） */
    private volatile boolean fallDamageEnabled = true;

    /** 是否可进行剧烈运动（对应 hasEnoughFoodToDoExhaustiveManoeuvres；由本地饥饿镜像驱动） */
    private boolean canDoExhaustiveManoeuvres = true;
    /** 移动是否受限（对应 BLINDNESS 效果；由 blindnessAmplifier 驱动） */
    private boolean mobilityRestricted;

    public PlayerPhysics(BotState state, RunningWorld world, BlockNameTable blockNames,
                         EntityTracker entityTracker) {
        this.state = state;
        this.world = world;
        this.blockNames = blockNames;
        this.entityTracker = entityTracker;
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

    /**
     * 自身速度包（ClientboundSetEntityMotionPacket）：用包内速度整体替换当前速度，
     * 对齐官方 {@code ClientPacketListener.handleSetEntityMotion → entity.lerpMotion
     * → Entity.setDeltaMovement}。近战/弓箭/钓鱼竿击退、实体推挤与 mace 冲击
     * 均经由该包下发。向量非有限（NaN/无穷）时忽略（官方 isFinite 门控）；
     * 不改变位置、冲刺、碰撞与跳跃冷却状态。
     */
    public synchronized void setDeltaMovement(double newDeltaX, double newDeltaY, double newDeltaZ) {
        if (!isFinite(newDeltaX, newDeltaY, newDeltaZ)) {
            return;
        }
        this.deltaX = newDeltaX;
        this.deltaY = newDeltaY;
        this.deltaZ = newDeltaZ;
        if (positioned) {
            publish();
        }
    }

    /**
     * 爆炸冲量（ClientboundExplodePacket.playerKnockback）：在现有速度上叠加，
     * 对齐官方 {@code handleExplosion → LocalPlayer.addDeltaMovement}；
     * 向量非有限时忽略（官方 isFinite 门控）。
     */
    public synchronized void addDeltaMovement(double addX, double addY, double addZ) {
        if (!isFinite(addX, addY, addZ)) {
            return;
        }
        this.deltaX += addX;
        this.deltaY += addY;
        this.deltaZ += addZ;
        if (positioned) {
            publish();
        }
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
    // 外部写入：药水效果与配置开关（网络/装配线程调用）
    // ------------------------------------------------------------------

    /** 设置速度（SPEED）药水放大器；-1 表示无效果 */
    public void setSpeedAmplifier(int amplifier) {
        this.speedAmplifier = amplifier;
    }

    /** 设置缓慢（SLOWNESS）药水放大器；-1 表示无效果 */
    public void setSlownessAmplifier(int amplifier) {
        this.slownessAmplifier = amplifier;
    }

    /** 设置跳跃提升（JUMP_BOOST）药水放大器；-1 表示无效果 */
    public void setJumpBoostAmplifier(int amplifier) {
        this.jumpBoostAmplifier = amplifier;
    }

    /** 设置缓降（SLOW_FALLING）药水放大器；-1 表示无效果 */
    public void setSlowFallingAmplifier(int amplifier) {
        this.slowFallingAmplifier = amplifier;
    }

    /** 设置漂浮（LEVITATION）药水放大器；-1 表示无效果 */
    public void setLevitationAmplifier(int amplifier) {
        this.levitationAmplifier = amplifier;
    }

    /** 设置失明（BLINDNESS）药水放大器；-1 表示无效果 */
    public void setBlindnessAmplifier(int amplifier) {
        this.blindnessAmplifier = amplifier;
    }

    /** 设置海豚恩惠（DOLPHINS_GRACE）药水放大器；-1 表示无效果 */
    public void setDolphinsGraceAmplifier(int amplifier) {
        this.dolphinsGraceAmplifier = amplifier;
    }

    /** 设置饥饿（HUNGER）药水放大器；-1 表示无效果 */
    public void setHungerAmplifier(int amplifier) {
        this.hungerAmplifier = amplifier;
    }

    /** 设置摔落伤害开关（{@code physics.fall-damage}） */
    public void setFallDamageEnabled(boolean value) {
        this.fallDamageEnabled = value;
    }

    /** 当前食物等级（本地镜像，供命令/日志查询） */
    public synchronized int foodLevel() {
        return foodLevel;
    }

    /** 当前饱和度（本地镜像） */
    public synchronized float saturationLevel() {
        return saturationLevel;
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

        // (0) 附近实体插值推进（实体位置先于本 tick 的玩家物理推进；推挤查询
        //     使用插值后位置，对齐客户端实体 tick 先于推挤消费的相位）
        entityTracker.tick();

        // (1) 水域下潜判定（Player.tick 开头 updateIsUnderwater：读取上一 tick 扫描的
        //     眼部水判定；LocalPlayer.isUnderWater 直接返回该字段）
        wasUnderwater = eyeInWater;

        // (2) 流体交互扫描（Entity.baseTick：wasEyeInWater ← 上一 tick 扫描值 →
        //     重新逐格扫描 → wasTouchingWater 更新 → 水流冲量施加）
        wasEyeInWater = eyeInWater;
        updateFluidInteraction();

        // (3) 游泳状态（baseTick：updateSwimming；用旧眼部值 + 本 tick 接触值）
        updateSwimming();

        // (3b) 岩浆中摔落距离减半（baseTick：isInLava → fallDistance *= 0.5）
        if (isInLava()) {
            fallDistance *= 0.5;
        }
        // baseTick 末尾：清除首 tick 标志（首 tick 内 isInLava 恒 false、入水不触发水花）
        firstTick = false;

        // (3c) 饥饿镜像与疾跑门控（FoodData.tick + hasEnoughFoodToDoExhaustiveManoeuvres）
        tickFood();
        canDoExhaustiveManoeuvres = foodLevel > SPRINT_LEVEL;
        mobilityRestricted = blindnessAmplifier >= 0;

        // (3d) 缓降/漂浮时清零摔落距离（LivingEntity.aiStep：hasEffect(SLOW_FALLING/LEVITATION) → resetFallDistance）
        if (slowFallingAmplifier >= 0 || levitationAmplifier >= 0) {
            fallDistance = 0.0;
        }

        // (4) 蹲伏平面判定（LocalPlayer.aiStep：input.tick 之前执行，读取上一 tick 输入帧）
        boolean lastShift = lastKeyShift;
        boolean lastForwardImpulse = lastHasForwardImpulse;
        lastKeyShift = input.shift();
        lastHasForwardImpulse = input.hasForwardImpulse();
        crouching = !swimming && canPlayerFitWithinBlocksAndEntitiesWhen(POSE_CROUCHING)
                && (lastShift || !canPlayerFitWithinBlocksAndEntitiesWhen(POSE_STANDING));

        // (5) 自动跳跃延迟消费（input.tick 之后执行 makeJump，向本 tick 注入跳跃输入）
        boolean autoJumping = false;
        if (autoJumpTime > 0) {
            --autoJumpTime;
            autoJumpInjected = true;
            autoJumping = true;
        }

        // (6) 碰撞脱困推力（LocalPlayer.aiStep：4 个水平角点，可能直接覆盖水平速度）
        double cornerOffset = PLAYER_WIDTH * 0.35;
        moveTowardsClosestSpace(x - cornerOffset, z + cornerOffset);
        moveTowardsClosestSpace(x - cornerOffset, z - cornerOffset);
        moveTowardsClosestSpace(x + cornerOffset, z - cornerOffset);
        moveTowardsClosestSpace(x + cornerOffset, z + cornerOffset);

        // (7) 冲刺状态机（LocalPlayer.aiStep：触发窗口 → 开始 → 停止）
        updateSprinting(input, lastShift, lastForwardImpulse);

        // (8) 水中下潜（LocalPlayer.aiStep：isInWater && shift && isAffectedByFluids）
        if (touchingWater && input.shift() && isAffectedByFluids()) {
            goDownInWater();
        }

        // (9) 跳跃延迟递减（LivingEntity.aiStep 开头）
        if (noJumpDelay > 0) {
            --noJumpDelay;
        }

        // (10) 速度归零阈值（aiStep：玩家水平用平方阈值 9.0E-6，垂直 0.003）
        if (deltaX * deltaX + deltaZ * deltaZ < MIN_HORIZONTAL_SPEED_SQR) {
            deltaX = 0.0;
            deltaZ = 0.0;
        }
        if (Math.abs(deltaY) < MIN_VERTICAL_SPEED) {
            deltaY = 0.0;
        }

        // (11) 输入处理（LocalPlayer.applyInput + modifyInput）
        double[] move = modifyInput(input.leftImpulse(), input.forwardImpulse());
        this.xxa = move[0];
        this.zza = move[1];

        // (12) 跳跃段（LivingEntity.aiStep：流体/地面分支；含自动跳跃注入）
        handleJump(input.jump() || autoJumping);

        // (13) 行进分派（travel：流体优先，否则空中）
        double prevX = x;
        double prevY = y;
        double prevZ = z;
        travel(this.xxa, this.zza);

        // (13b) 移动消耗（ServerPlayer.checkMovementStatistics：按本 tick 实际位移计费）
        addMovementExhaustion(x - prevX, y - prevY, z - prevZ);

        // (14) 方块效果（applyEffectsFromBlocks：着地时脚下方块的 stepOn，如粘液块减速）
        applyStepOnEffects();

        // (15) 自动跳跃检测（LocalPlayer.move 末尾：传入本 tick 实际位移）
        updateAutoJump((float) (x - prevX), (float) (z - prevZ), input);

        // (15b) 实体推挤（LivingEntity.aiStep 末尾 pushEntities：把附近可推实体的
        //       推挤速度注入本 tick 速度，由下 tick 的输入处理与 travel 消费）
        pushEntitiesFromNearbyEntities();

        // (16) 姿势更新（Player.tick 末尾 updatePlayerPose）
        updatePlayerPose();

        // (17) 发布权威状态
        publish();
    }

    // ------------------------------------------------------------------
    // 内部：饥饿与饱和（对齐 FoodData / ServerPlayer.checkMovementStatistics）
    // ------------------------------------------------------------------

    /**
     * 对齐 FoodData.tick：exhaustionLevel > 4.0 时先扣饱和度（每级 −1），
     * 饱和耗尽后扣食物等级（非和平难度）；饥饿药水每 tick 追加 0.005×(amp+1) 消耗。
     */
    private void tickFood() {
        if (hungerAmplifier >= 0) {
            addExhaustion(EXHAUSTION_HUNGER_EFFECT * (hungerAmplifier + 1));
        }
        if (exhaustionLevel > EXHAUSTION_DROP) {
            exhaustionLevel -= EXHAUSTION_DROP;
            if (saturationLevel > 0.0F) {
                saturationLevel = Math.max(saturationLevel - 1.0F, 0.0F);
            } else {
                foodLevel = Math.max(foodLevel - 1, 0);
            }
        }
    }

    /** 对齐 FoodData.addExhaustion：累加消耗（上限 40） */
    private void addExhaustion(float amount) {
        exhaustionLevel = Math.min(exhaustionLevel + amount, 40.0F);
    }

    /**
     * 对齐 ServerPlayer.checkMovementStatistics：按本 tick 实际位移计移动消耗。
     * 疾跑 0.1/m、游泳/水下 0.01/m、行走/潜行/攀爬/飞行 0（机器人无鞘翅/骑乘）。
     */
    private void addMovementExhaustion(double dx, double dy, double dz) {
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (swimming || eyeInWater) {
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > 0.0) {
                addExhaustion(EXHAUSTION_SWIM_PER_M * (float) dist);
            }
        } else if (onGround) {
            if (horizontal > 0.0 && sprinting) {
                addExhaustion(EXHAUSTION_SPRINT_PER_M * (float) horizontal);
            }
        }
    }

    /**
     * 被附近实体推挤：对齐官方客户端 {@code LivingEntity.aiStep 末尾 → pushEntities
     * → doPush → Entity.push(Entity)} 作用在本地玩家上的速度注入。
     *
     * <p>官方判定链（26.1.2 源码核实）：推方实体与本玩家碰撞盒相交
     * （{@code ClientLevel.getPushableEntities} 客户端覆写）；推方类型会推玩家
     * （{@code EntitySizes.pushesLocalPlayer} 排除 bat、armor_stand、parrot，
     * vex 与末影龙由 noPhysics 门控排除）；本玩家非旁观、非攀爬、非载具
     * （机器人近似恒真）。公式逐行对齐 {@code Entity.push(Entity)} L1796-1826：
     * Mth.absMax 取水平最大分量、0.01F 距离门控、1/dd 限幅、0.05F 缩放，
     * 最终 {@code this.push(-xa, 0.0, -za)} 向背离推方方向叠加。</p>
     *
     * <p>简化：官方同一路径下还会反向推挤推方实体；推方在本机是模拟实体、
     * 其运动由服务器广播裁定，故不对其施加反向推挤。</p>
     */
    private void pushEntitiesFromNearbyEntities() {
        for (EntityTracker.EntitySnapshot pusher : entityTracker.collidingWith(boundingBox())) {
            double xa = pusher.x() - x;
            double za = pusher.z() - z;
            double dd = Math.max(Math.abs(xa), Math.abs(za)); // Mth.absMax
            if (dd < (double) 0.01F) {
                continue;
            }
            dd = Math.sqrt(dd);
            xa /= dd;
            za /= dd;
            double pow = (double) 1.0F / dd;
            if (pow > (double) 1.0F) {
                pow = (double) 1.0F;
            }
            xa *= pow;
            za *= pow;
            xa *= (double) 0.05F;
            za *= (double) 0.05F;
            // Entity.push：this.push(-xa, 0.0, -za)（this = 本地玩家，背离推方）
            deltaX -= xa;
            deltaZ -= za;
        }
    }

    // ------------------------------------------------------------------
    // 内部：输入与冲刺
    // ------------------------------------------------------------------

    /**
     * 冲刺状态机，对齐 LocalPlayer.aiStep 的三段式逻辑：
     * <ul>
     *   <li>触发窗口清零：按住潜行键（上一 tick 帧）或后退键时清零；</li>
     *   <li>canStartSprinting 成立时：若上一 tick 无前进冲量，则按触发窗口兑现
     *       （窗口内再次前进 → 直接疾跑）或重开窗口；按住冲刺键立即疾跑；</li>
     *   <li>已在冲刺时按游泳/奔跑分支检查停止条件。</li>
     * </ul>
     *
     * @param lastShift           上一 tick 输入帧的 shift 键（wasShiftKeyDown）
     * @param lastForwardImpulse  上一 tick 输入帧是否有前进冲量（hasForwardImpulse）
     */
    private void updateSprinting(PlayerInput input, boolean lastShift, boolean lastForwardImpulse) {
        // 触发窗口清零（LocalPlayer.aiStep：wasShiftKeyDown || 后退键）
        if (lastShift || input.backward()) {
            sprintTriggerTime = 0;
        }

        if (canStartSprinting(input)) {
            if (!lastForwardImpulse) {
                if (sprintTriggerTime > 0) {
                    sprinting = true;
                } else {
                    sprintTriggerTime = SPRINT_WINDOW_TICKS;
                }
            }
            if (input.sprint()) {
                sprinting = true;
            }
        }

        if (sprinting) {
            if (swimming) {
                if (shouldStopSwimSprinting(input)) {
                    sprinting = false;
                }
            } else if (shouldStopRunSprinting(input)) {
                sprinting = false;
            }
        }
    }

    /**
     * 对齐 LocalPlayer.canStartSprinting：未在冲刺、有前进冲量、允许疾跑
     * 且（未缓行或在水下）；无鞘翅/物品使用场景为恒真项。
     */
    private boolean canStartSprinting(PlayerInput input) {
        return !sprinting && input.hasForwardImpulse() && isSprintingPossible(false)
                && (!isMovingSlowly() || isUnderWater());
    }

    /** 对齐 LocalPlayer.shouldStopRunSprinting */
    private boolean shouldStopRunSprinting(PlayerInput input) {
        return !isSprintingPossible(false) || !input.hasForwardImpulse()
                || (horizontalCollision && !minorHorizontalCollision);
    }

    /** 对齐 LocalPlayer.shouldStopSwimSprinting */
    private boolean shouldStopSwimSprinting(PlayerInput input) {
        return !isSprintingPossible(true) || !isInWater()
                || (!input.hasForwardImpulse() && !onGround && !input.shift());
    }

    /**
     * 对齐 LocalPlayer.isSprintingPossible：未受限且可进行剧烈运动，
     * 且（允许浅水 或 未处于浅水）时允许疾跑（无骑乘场景）。
     */
    private boolean isSprintingPossible(boolean allowedInShallowWater) {
        return !mobilityRestricted && canDoExhaustiveManoeuvres
                && (allowedInShallowWater || !isInShallowWater());
    }

    /**
     * 对齐 LocalPlayer.isMovingSlowly：蹲伏平面判定（crouching）或匍匐
     * （游泳姿势但不在水中）。
     */
    private boolean isMovingSlowly() {
        return crouching || (pose == POSE_SWIMMING && !touchingWater);
    }

    /**
     * 输入向量处理，对齐 LocalPlayer.modifyInput：
     * 归一化（KeyboardInput）→ 0.98 缩放 → 缓行 0.3 缩放 → 方形修正。
     * 缓行由 {@link #isMovingSlowly()} 判定（crouching 字段，用旧帧 shift 计算）。
     *
     * @return {xxa, zza}
     */
    private double[] modifyInput(float leftImpulse, float forwardImpulse) {
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
        if (isMovingSlowly()) {
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
     * 起跳后按 ServerPlayer.jumpFromGround 追加饥饿消耗（疾跑 0.2 / 普通 0.05）。
     */
    private void jumpFromGround() {
        float jumpPower = getJumpPower();
        if (jumpPower > 1.0E-5F) {
            deltaY = Math.max((double) jumpPower, deltaY);
            if (sprinting) {
                // 官方 Mth.sin/cos 返回 float：三角函数先按 float 截断再转入 double 乘法
                float angle = yaw * ((float) Math.PI / 180F);
                deltaX += (double) (-(float) Math.sin((double) angle)) * SPRINT_JUMP_BOOST;
                deltaZ += (double) ((float) Math.cos((double) angle)) * SPRINT_JUMP_BOOST;
            }
            addExhaustion(sprinting ? EXHAUSTION_SPRINT_JUMP : EXHAUSTION_JUMP);
        }
    }

    /**
     * 对齐 LivingEntity.getJumpPower：JUMP_STRENGTH × 方块跳跃因子 + 跳跃提升加成
     * （getJumpBoostPower：0.1×(amp+1)，无效果时为 0）。
     */
    private float getJumpPower() {
        return JUMP_STRENGTH * getBlockJumpFactor() + getJumpBoostPower();
    }

    /** 对齐 LivingEntity.getJumpBoostPower：跳跃提升每级 +0.1 跳跃力 */
    private float getJumpBoostPower() {
        return jumpBoostAmplifier >= 0 ? JUMP_BOOST_POWER * (jumpBoostAmplifier + 1) : 0.0F;
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
    // 内部：流体交互（对齐 Entity.updateFluidInteraction / EntityFluidInteraction）
    // ------------------------------------------------------------------

    /**
     * 流体交互扫描（对齐 Entity.updateFluidInteraction → EntityFluidInteraction.update）：
     * 重置水/岩浆 tracker → 逐格扫描接触高度、眼部浸没与水流 → 更新
     * wasTouchingWater → 施加水流冲量（isPushedByFluid 非飞行恒真）。
     */
    private void updateFluidInteraction() {
        // tracker 重置（EntityFluidInteraction.update 开头）
        waterHeight = 0.0;
        lavaHeight = 0.0;
        eyeInWater = false;
        eyeInLava = false;
        waterFlowX = 0.0;
        waterFlowY = 0.0;
        waterFlowZ = 0.0;
        waterFlowCount = 0;
        lavaFlowX = 0.0;
        lavaFlowY = 0.0;
        lavaFlowZ = 0.0;
        lavaFlowCount = 0;

        // 扫描盒 = 碰撞盒四周收缩 0.001（getFluidInteractionBox）
        AABB box = boundingBox().deflate(FLUID_BOX_DEFLATE, FLUID_BOX_DEFLATE, FLUID_BOX_DEFLATE);
        int x0 = floorToInt(box.minX);
        int y0 = floorToInt(box.minY);
        int z0 = floorToInt(box.minZ);
        int x1 = ceilToInt(box.maxX) - 1;
        int y1 = ceilToInt(box.maxY) - 1;
        int z1 = ceilToInt(box.maxZ) - 1;

        // 区块加载门控（hasFluidAndLoaded：外扩一格范围内任一区块未加载则整个扫描跳过）
        if (hasFluidAndLoaded(x0 - 1, z0 - 1, x1 + 1, z1 + 1)) {
            double entityY = boundingBox().minY;
            int eyeBlockX = floorToInt(x);
            double eyeY = y + eyeHeight();
            int eyeBlockZ = floorToInt(z);

            for (int bx = x0; bx <= x1; bx++) {
                for (int by = y0; by <= y1; by++) {
                    for (int bz = z0; bz <= z1; bz++) {
                        int stateId = world.getBlockStateId(bx, by, bz);
                        int fluid = blockNames.fluidType(stateId);
                        if (fluid == BlockNameTable.FLUID_NONE) {
                            continue;
                        }
                        double fluidBottom = (double) by;
                        double fluidTop = fluidBottom + (double) fluidHeightAt(bx, by, bz, stateId);
                        if (fluidTop < box.minY) {
                            continue;
                        }
                        boolean eyesInside = bx == eyeBlockX && bz == eyeBlockZ
                                && eyeY >= fluidBottom && eyeY <= fluidTop;
                        // 水流累积（ignoreCurrent 恒 false；flow 的缩放依赖更新后的 tracker.height）
                        Vec3 flow = getFlow(bx, by, bz);
                        if (fluid == BlockNameTable.FLUID_WATER) {
                            if (eyesInside) {
                                eyeInWater = true;
                            }
                            waterHeight = Math.max(fluidTop - entityY, waterHeight);
                            if (waterHeight < FLUID_JUMP_THRESHOLD) {
                                flow = flow.scale(waterHeight);
                            }
                            waterFlowX += flow.x;
                            waterFlowY += flow.y;
                            waterFlowZ += flow.z;
                            waterFlowCount++;
                        } else {
                            if (eyesInside) {
                                eyeInLava = true;
                            }
                            lavaHeight = Math.max(fluidTop - entityY, lavaHeight);
                            if (lavaHeight < FLUID_JUMP_THRESHOLD) {
                                flow = flow.scale(lavaHeight);
                            }
                            lavaFlowX += flow.x;
                            lavaFlowY += flow.y;
                            lavaFlowZ += flow.z;
                            lavaFlowCount++;
                        }
                    }
                }
            }
        }

        boolean inWater = waterHeight > 0.0;
        boolean inLava = lavaHeight > 0.0;
        if (inWater) {
            // resetFallDistance + 水花粒子/音效（无头客户端无声效，仅保留摔落距离语义）
            fallDistance = 0.0;
        }

        touchingWater = inWater;
        // isPushedByFluid 恒真（机器人无飞行能力）：直接施加水流冲量
        if (inWater) {
            applyCurrentTo(true, WATER_PUSH_SCALE);
        }
        if (inLava) {
            applyCurrentTo(false, LAVA_PUSH_SCALE);
        }
    }

    /**
     * 区块加载门控（对齐 EntityFluidInteraction.hasFluidAndLoaded 的关键语义）：
     * 扫描范围外扩一格覆盖的所有区块列必须已加载，否则返回 false
     * （官方对任一缺失区块直接跳过整个扫描，tracker 保持零值）。
     * section 级 hasFluid 快速检查省略：逐格扫描会自然跳过无流体位置。
     */
    private boolean hasFluidAndLoaded(int x0, int z0, int x1, int z1) {
        for (int chunkZ = z0 >> 4; chunkZ <= z1 >> 4; chunkZ++) {
            for (int chunkX = x0 >> 4; chunkX <= x1 >> 4; chunkX++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 对齐 FlowingFluid.getHeight：上方为同类流体时视为满格 1.0，
     * 否则为自身高度（amount / 9，对齐 getOwnHeight）。
     */
    private float fluidHeightAt(int bx, int by, int bz, int stateId) {
        int ownFluid = blockNames.fluidType(stateId);
        if (blockNames.fluidType(world.getBlockStateId(bx, by + 1, bz)) == ownFluid) {
            return 1.0F;
        }
        return blockNames.fluidOwnHeight(stateId);
    }

    /**
     * 对齐 FlowingFluid.getFlow：水平四向（N,E,S,W）的流量差累积（含下方
     * 瀑布边缘的 0.8888889 修正）；FALLING 时若侧面存在实体支撑面则附加
     * 下落加速 (0,-6,0)；最终单位化。
     */
    private Vec3 getFlow(int bx, int by, int bz) {
        int stateId = world.getBlockStateId(bx, by, bz);
        int ownFluid = blockNames.fluidType(stateId);
        float ownHeight = blockNames.fluidOwnHeight(stateId);
        double flowX = 0.0;
        double flowZ = 0.0;

        for (int dir = 0; dir < HORIZONTAL_STEP_X.length; dir++) {
            int nbX = bx + HORIZONTAL_STEP_X[dir];
            int nbZ = bz + HORIZONTAL_STEP_Z[dir];
            int nbState = world.getBlockStateId(nbX, by, nbZ);
            int nbFluid = blockNames.fluidType(nbState);
            if (!affectsFlow(nbFluid, ownFluid)) {
                continue;
            }
            float neighborHeight = blockNames.fluidOwnHeight(nbState);
            float distance = 0.0F;
            if (neighborHeight == 0.0F) {
                // 邻居无流体高度：若其不阻挡运动，则查其下方是否有同族流体（瀑布边缘）
                if (!blocksMotionForFlow(nbState)) {
                    int belowState = world.getBlockStateId(nbX, by - 1, nbZ);
                    int belowFluid = blockNames.fluidType(belowState);
                    if (affectsFlow(belowFluid, ownFluid)) {
                        neighborHeight = blockNames.fluidOwnHeight(belowState);
                        if (neighborHeight > 0.0F) {
                            distance = ownHeight - (neighborHeight - FLUID_FALLING_BASE);
                        }
                    }
                }
            } else {
                distance = ownHeight - neighborHeight;
            }
            if (distance != 0.0F) {
                flowX += (double) ((float) HORIZONTAL_STEP_X[dir] * distance);
                flowZ += (double) ((float) HORIZONTAL_STEP_Z[dir] * distance);
            }
        }

        Vec3 flow = new Vec3(flowX, 0.0, flowZ);
        if (blockNames.fluidFalling(stateId)) {
            for (int dir = 0; dir < HORIZONTAL_STEP_X.length; dir++) {
                int nbX = bx + HORIZONTAL_STEP_X[dir];
                int nbZ = bz + HORIZONTAL_STEP_Z[dir];
                if (isSolidFace(nbX, by, nbZ, HORIZONTAL_FACE[dir], ownFluid)
                        || isSolidFace(nbX, by + 1, nbZ, HORIZONTAL_FACE[dir], ownFluid)) {
                    flow = flow.normalize().add(0.0, -6.0, 0.0);
                    break;
                }
            }
        }
        return flow.normalize();
    }

    /** 对齐 FlowingFluid.affectsFlow：邻居为空（含未加载视作空气）或同族流体 */
    private boolean affectsFlow(int neighbourFluid, int ownFluid) {
        return neighbourFluid == BlockNameTable.FLUID_NONE || neighbourFluid == ownFluid;
    }

    /**
     * getFlow 内的 blocksMotion 查询：未加载区块视作空气
     * （官方客户端缺失区块返回空气，blocksMotion=false）。
     */
    private boolean blocksMotionForFlow(int stateId) {
        if (stateId == RunningWorld.UNLOADED) {
            return false;
        }
        return blockNames.blocksMotion(stateId);
    }

    /**
     * 对齐 FlowingFluid.isSolidFace：本格为同族流体 → false；UP 面 → true；
     * 冰 → false；否则查方块该面的支撑完整度
     * （未加载区块视作空气 → false，官方缺失区块语义）。
     */
    private boolean isSolidFace(int bx, int by, int bz, int face, int ownFluid) {
        int stateId = world.getBlockStateId(bx, by, bz);
        if (blockNames.fluidType(stateId) == ownFluid) {
            return false;
        }
        if (face == BlockNameTable.FACE_UP) {
            return true;
        }
        if (blockNames.isIce(stateId)) {
            return false;
        }
        if (stateId == RunningWorld.UNLOADED) {
            return false;
        }
        return blockNames.isFaceSturdy(stateId, face);
    }

    /**
     * 施加累积水流冲量（对齐 Tracker.applyCurrentTo）：玩家取累积均值后按
     * scale 缩放；旧水平速度近零且冲量过小时归一到 0.0045000000000000005
     * （微流仍产生可感知推力）。
     *
     * @param water true = 水（0.014），false = 岩浆（非快岩浆 0.0023333333333333335）
     */
    private void applyCurrentTo(boolean water, double scale) {
        double flowX = water ? waterFlowX : lavaFlowX;
        double flowY = water ? waterFlowY : lavaFlowY;
        double flowZ = water ? waterFlowZ : lavaFlowZ;
        int count = water ? waterFlowCount : lavaFlowCount;
        if (count == 0) {
            return;
        }
        if (flowX * flowX + flowY * flowY + flowZ * flowZ < FLUID_FLOW_MIN_SQR) {
            return;
        }
        // Player 特例：累积值取均值（非 Player 为 normalize）
        Vec3 impulse = new Vec3(flowX / count, flowY / count, flowZ / count).scale(scale);
        if (Math.abs(deltaX) < FLUID_SLOW_MOVEMENT && Math.abs(deltaZ) < FLUID_SLOW_MOVEMENT
                && impulse.length() < FLUID_MIN_IMPULSE) {
            impulse = impulse.normalize().scale(FLUID_MIN_IMPULSE);
        }
        deltaX += impulse.x;
        deltaY += impulse.y;
        deltaZ += impulse.z;
    }

    /**
     * 游泳状态（对齐 Entity.updateSwimming）：已在游泳时按 isInWater 维持；
     * 否则需冲刺 + （LocalPlayer 覆写的）wasUnderwater + 所在位置流体为水。
     */
    private void updateSwimming() {
        if (swimming) {
            swimming = sprinting && touchingWater;
        } else {
            int stateId = world.getBlockStateId(floorToInt(x), floorToInt(y), floorToInt(z));
            swimming = sprinting && isUnderWater()
                    && blockNames.fluidType(stateId) == BlockNameTable.FLUID_WATER;
        }
    }

    /** Entity.isInWater（= wasTouchingWater，由本 tick 流体扫描更新） */
    private boolean isInWater() {
        return touchingWater;
    }

    /** Entity.isInLava（= 非首 tick 且岩浆 tracker 高度 &gt; 0） */
    private boolean isInLava() {
        return !firstTick && lavaHeight > 0.0;
    }

    /** LocalPlayer.isUnderWater 覆写（直接返回 wasUnderwater 字段） */
    private boolean isUnderWater() {
        return wasUnderwater;
    }

    /** Entity.isInShallowWater（= 接触水但眼部未浸没） */
    private boolean isInShallowWater() {
        return isInWater() && !isUnderWater();
    }

    /** Player.isAffectedByFluids（= !abilities.flying；机器人无飞行能力，恒真） */
    private boolean isAffectedByFluids() {
        return true;
    }

    /** 当前姿势对应的眼高（getEyeY = y + eyeHeight；姿势由上一 tick 末尾的 updatePlayerPose 决定） */
    public float eyeHeight() {
        return switch (pose) {
            case POSE_CROUCHING -> EYE_HEIGHT_CROUCHING;
            case POSE_SWIMMING -> EYE_HEIGHT_SWIMMING;
            default -> EYE_HEIGHT;
        };
    }

    /**
     * 对齐 Player.updatePlayerPose：游泳姿势可容纳时更新姿势——先取期望姿势，
     * 期望姿势不可容纳时依次回退蹲伏、游泳姿势；游泳姿势本身都容纳不下时
     * 保持原姿势（SLEEPING/FALL_FLYING/SPIN_ATTACK 对机器人不适用，省略）。
     */
    private void updatePlayerPose() {
        if (canPlayerFitWithinBlocksAndEntitiesWhen(POSE_SWIMMING)) {
            byte desiredPose = getDesiredPose();
            if (canPlayerFitWithinBlocksAndEntitiesWhen(desiredPose)) {
                pose = desiredPose;
            } else if (canPlayerFitWithinBlocksAndEntitiesWhen(POSE_CROUCHING)) {
                pose = POSE_CROUCHING;
            } else {
                pose = POSE_SWIMMING;
            }
        }
    }

    /**
     * 对齐 Player.getDesiredPose：游泳时取游泳姿势，否则按住潜行键（新帧，
     * isShiftKeyDown）取蹲伏（abilities.flying 对机器人恒 false），默认站立。
     */
    private byte getDesiredPose() {
        if (swimming) {
            return POSE_SWIMMING;
        }
        return lastKeyShift ? POSE_CROUCHING : POSE_STANDING;
    }

    /**
     * 对齐 Player.canPlayerFitWithinBlocksAndEntitiesWhen：以该姿势尺寸构建的
     * 碰撞盒（四周收缩 1.0E-7）无方块碰撞。
     */
    private boolean canPlayerFitWithinBlocksAndEntitiesWhen(byte newPose) {
        AABB box = AABB.ofEntity(x, y, z, PLAYER_WIDTH, poseHeight(newPose))
                .deflate(1.0E-7, 1.0E-7, 1.0E-7);
        return noCollision(box);
    }

    /** 姿势对应的碰撞盒高度（Avatar.POSES：STANDING 1.8 / CROUCHING 1.5 / SWIMMING 0.6） */
    private float poseHeight(byte pose) {
        return switch (pose) {
            case POSE_CROUCHING -> CROUCHING_HEIGHT;
            case POSE_SWIMMING -> SWIMMING_HEIGHT;
            default -> PLAYER_HEIGHT;
        };
    }

    // ------------------------------------------------------------------
    // 内部：行进（LivingEntity.travel / travelInWater / travelInLava）
    // ------------------------------------------------------------------

    /**
     * 对齐 LivingEntity.travel：优先流体行进（shouldTravelInFluid），否则空中行进
     * （鞘翅滑翔分支需要可滑翔装备，机器人场景恒不成立，省略）。
     */
    private void travel(double xxa, double zza) {
        if (shouldTravelInFluid()) {
            travelInFluid(xxa, zza);
        } else {
            travelInAir(xxa, zza);
        }
    }

    /**
     * 对齐 LivingEntity.shouldTravelInFluid：接触水/岩浆且受流体影响
     * （canStandOnFluid 对玩家恒 false）。
     */
    private boolean shouldTravelInFluid() {
        return (isInWater() || isInLava()) && isAffectedByFluids();
    }

    /**
     * 对齐 LivingEntity.travelInFluid：记录当前下落方向、起始 Y 与基准重力，
     * 按所在流体分派水分支/岩浆分支（ridden 浮水分支对玩家无意义，省略）。
     */
    private void travelInFluid(double xxa, double zza) {
        boolean isFalling = deltaY <= 0.0;
        double oldY = y;
        double baseGravity = getEffectiveGravity();
        if (isInWater()) {
            travelInWater(xxa, zza, baseGravity, isFalling, oldY);
        } else {
            travelInLava(xxa, zza, baseGravity, isFalling, oldY);
        }
    }

    /**
     * 对齐 LivingEntity.getEffectiveGravity：下落且持缓降药水时取 min(重力, 0.01)。
     */
    private double getEffectiveGravity() {
        boolean isFalling = deltaY <= 0.0;
        return isFalling && slowFallingAmplifier >= 0 ? Math.min(GRAVITY, SLOW_FALL_GRAVITY) : GRAVITY;
    }

    /**
     * 对齐 LivingEntity.travelInWater：0.02 加速度相对移动 → 碰撞移动 →
     * 乘算衰减（水平：冲刺 0.9/常规 0.8，海豚恩惠 0.96；垂直 0.8）→ 流体下落修正 →
     * 贴墙出水抬升（WATER_MOVEMENT_EFFICIENCY / 攀爬柱对机器人均无加成，省略）。
     */
    private void travelInWater(double xxa, double zza, double baseGravity, boolean isFalling, double oldY) {
        float slowDown = sprinting ? SPRINT_WATER_SLOW_DOWN : WATER_SLOW_DOWN;
        if (dolphinsGraceAmplifier >= 0) {
            slowDown = DOLPHINS_GRACE_SLOW_DOWN;
        }
        moveRelative(FLUID_SPEED, xxa, zza);
        move(new Vec3(deltaX, deltaY, deltaZ));
        Vec3 ladderMovement = new Vec3(deltaX, deltaY, deltaZ)
                .multiply((double) slowDown, (double) 0.8F, (double) slowDown);
        Vec3 adjusted = getFluidFallingAdjustedMovement(baseGravity, isFalling, ladderMovement);
        deltaX = adjusted.x;
        deltaY = adjusted.y;
        deltaZ = adjusted.z;
        jumpOutOfFluid(oldY);
    }

    /**
     * 对齐 LivingEntity.travelInLava：0.02 加速度相对移动 → 碰撞移动 →
     * 浅岩浆（高度 ≤ 跳跃阈值）乘算 0.5/0.8/0.5 + 流体下落修正，否则整体乘 0.5 →
     * 附加下沉 -baseGravity/4 → 贴墙出水抬升。
     */
    private void travelInLava(double xxa, double zza, double baseGravity, boolean isFalling, double oldY) {
        moveRelative(FLUID_SPEED, xxa, zza);
        move(new Vec3(deltaX, deltaY, deltaZ));
        if (lavaHeight <= getFluidJumpThreshold()) {
            deltaX *= (double) 0.5F;
            deltaY *= (double) 0.8F;
            deltaZ *= (double) 0.5F;
            Vec3 adjusted = getFluidFallingAdjustedMovement(baseGravity, isFalling,
                    new Vec3(deltaX, deltaY, deltaZ));
            deltaX = adjusted.x;
            deltaY = adjusted.y;
            deltaZ = adjusted.z;
        } else {
            deltaX *= (double) 0.5F;
            deltaY *= (double) 0.5F;
            deltaZ *= (double) 0.5F;
        }
        if (baseGravity != 0.0) {
            deltaY += -baseGravity / (double) 4.0F;
        }
        jumpOutOfFluid(oldY);
    }

    /**
     * 对齐 LivingEntity.jumpOutOfFluid：水平碰撞且抬升 0.6 格后的位置自由
     * （无方块碰撞、无液体）时，垂直速度设为 0.3（贴墙出水自动爬升）。
     */
    private void jumpOutOfFluid(double oldY) {
        if (horizontalCollision && isFree(deltaX, deltaY + 0.6 - y + oldY, deltaZ)) {
            deltaY = 0.3;
        }
    }

    /**
     * 对齐 LivingEntity.getFluidFallingAdjustedMovement：非冲刺且存在基准重力时，
     * 垂直速度逐 tick 递减 baseGravity/16；下落且速度落在 -baseGravity/16 附近时
     * 锁定为 -0.003（水中匀速下沉）。
     */
    private Vec3 getFluidFallingAdjustedMovement(double baseGravity, boolean isFalling, Vec3 movement) {
        if (baseGravity != 0.0 && !sprinting) {
            double yd;
            if (isFalling && Math.abs(movement.y - FLUID_FALL_BASE) >= FLUID_FALL_EPSILON
                    && Math.abs(movement.y - baseGravity / FLUID_GRAVITY_DIVISOR) < FLUID_FALL_EPSILON) {
                yd = FLUID_FALL_ADJUST;
            } else {
                yd = movement.y - baseGravity / FLUID_GRAVITY_DIVISOR;
            }
            return new Vec3(movement.x, yd, movement.z);
        }
        return movement;
    }

    /**
     * 对齐 LivingEntity.aiStep 跳跃段：按流体高度/地面状态五层分支决定起跳
     * （jumpFromGround + 10 tick 冷却）、流体上浮（jumpInLiquid），
     * 或者清零跳跃冷却（未按跳跃键或不受流体影响时 noJumpDelay = 0）。
     */
    private void handleJump(boolean jumping) {
        if (jumping && isAffectedByFluids()) {
            double fluidHeight = isInLava() ? lavaHeight : waterHeight;
            boolean inWaterAndHasFluidHeight = isInWater() && fluidHeight > 0.0;
            double fluidJumpThreshold = getFluidJumpThreshold();
            if (!inWaterAndHasFluidHeight || (onGround && !(fluidHeight > fluidJumpThreshold))) {
                if (!isInLava() || (onGround && !(fluidHeight > fluidJumpThreshold))) {
                    if ((onGround || (inWaterAndHasFluidHeight && fluidHeight <= fluidJumpThreshold))
                            && noJumpDelay == 0) {
                        jumpFromGround();
                        noJumpDelay = JUMP_COOLDOWN_TICKS;
                    }
                } else {
                    jumpInLiquid();
                }
            } else {
                jumpInLiquid();
            }
        } else {
            noJumpDelay = 0;
        }
    }

    /** 对齐 Entity.getFluidJumpThreshold：眼高 < 0.4 时阈值 0（玩家各姿势恒 0.4） */
    private double getFluidJumpThreshold() {
        return (double) eyeHeight() < 0.4 ? 0.0 : FLUID_JUMP_THRESHOLD;
    }

    /** 对齐 LivingEntity.goDownInWater：水中按住潜行时下沉 0.04/tick */
    private void goDownInWater() {
        deltaY += -0.04;
    }

    /** 对齐 LivingEntity.jumpInLiquid：流体中按跳跃键上浮 0.04/tick（水/岩浆行为一致） */
    private void jumpInLiquid() {
        deltaY += 0.04;
    }

    /** 对齐 Entity.isFree：位移后的碰撞盒无方块碰撞且不含液体 */
    private boolean isFree(double xa, double ya, double za) {
        AABB box = boundingBox().move(xa, ya, za);
        return noCollision(box) && !containsAnyLiquid(box);
    }

    /**
     * 对齐 CollisionGetter.noCollision（无实体/世界边框场景）：
     * 碰撞盒与范围内任何方块碰撞盒不相交（未加载区块视作空气）。
     */
    private boolean noCollision(AABB box) {
        for (AABB shape : collectBlockBoxes(box)) {
            if (shape.intersects(box)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 对齐 LevelReader.containsAnyLiquid：包围盒内任一方块含非空流体即返回 true
     * （循环边界 [floor, ceil)，未加载区块视作空流体）。
     */
    private boolean containsAnyLiquid(AABB box) {
        int x0 = floorToInt(box.minX);
        int x1 = ceilToInt(box.maxX);
        int y0 = floorToInt(box.minY);
        int y1 = ceilToInt(box.maxY);
        int z0 = floorToInt(box.minZ);
        int z1 = ceilToInt(box.maxZ);
        for (int bx = x0; bx < x1; bx++) {
            for (int by = y0; by < y1; by++) {
                for (int bz = z0; bz < z1; bz++) {
                    if (blockNames.fluidType(world.getBlockStateId(bx, by, bz)) != BlockNameTable.FLUID_NONE) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 内部：空中行进（LivingEntity.travelInAir）
    // ------------------------------------------------------------------

    /**
     * 对齐 Player.getSpeed：移动速度属性值，含速度/缓慢药水与疾跑修饰符
     * （均为 ADD_MULTIPLIED_TOTAL：速度 +0.2/级、缓慢 −0.15/级、疾跑 +30%）。
     */
    private float getSpeed() {
        double multiplier = 1.0;
        if (speedAmplifier >= 0) {
            multiplier *= 1.0 + SPEED_EFFECT_PER_LEVEL * (speedAmplifier + 1);
        }
        if (slownessAmplifier >= 0) {
            multiplier *= 1.0 + SLOWNESS_EFFECT_PER_LEVEL * (slownessAmplifier + 1);
        }
        if (sprinting) {
            multiplier *= SPRINT_SPEED_MULTIPLIER;
        }
        return (float) (MOVEMENT_SPEED * multiplier);
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
                int belowState = world.getBlockStateId(floorToInt(x), floorToInt(y - (double) 0.500001F), floorToInt(z));
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
            return blockNames.jumpFactor(world.getBlockStateId(floorToInt(x), floorToInt(y - (double) 0.500001F), floorToInt(z)));
        }
        return hereFactor;
    }

    /** 对齐 LivingEntity.travelInAir（无流体、无攀爬；含漂浮/缓降药水） */
    private void travelInAir(double xxa, double zza) {
        // 脚下方块：getBlockPosBelowThatAffectsMyMovement = getOnPos(0.500001F)
        int belowX = floorToInt(x);
        int belowY = floorToInt(y - (double) 0.500001F);
        int belowZ = floorToInt(z);
        boolean belowLoaded = world.isChunkLoaded(belowX >> 4, belowZ >> 4);
        int belowState = world.getBlockStateId(belowX, belowY, belowZ);

        float blockFriction = onGround ? (float) blockNames.friction(belowState) : 1.0F;
        float friction = blockFriction * (float) FRICTION_BASE;

        // handleRelativeFrictionAndCalculateMovement：先施加输入速度再碰撞移动
        // getFrictionInfluencedSpeed：地面 = getSpeed ×(0.21600002 / f³)；空中 = 0.02 / 冲刺 0.025999999
        float speed;
        if (onGround) {
            float f3 = blockFriction * blockFriction * blockFriction;
            speed = getSpeed() * (0.21600002F / f3);
        } else {
            speed = sprinting ? AIR_SPEED_SPRINTING : AIR_SPEED;
        }
        moveRelative(speed, xxa, zza);
        move(new Vec3(deltaX, deltaY, deltaZ));

        // 重力（脚下方块未加载时对齐客户端：仅缓慢下移、不施加重力；漂浮药水优先）
        double movementY = deltaY;
        if (levitationAmplifier >= 0) {
            movementY += (LEVITATION_RATE * (levitationAmplifier + 1) - movementY) * LEVITATION_LERP;
        } else if (!belowLoaded) {
            movementY = y > world.getMinY() ? -0.1 : 0.0;
        } else {
            movementY = deltaY - getEffectiveGravity();
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

    /**
     * 对齐 Entity.move(MoverType.SELF, delta)：潜行防走边回退 → 碰撞解算 → 位移 →
     * 碰撞标志 → 脚下方块与摔落距离结算 → 水平碰撞轴归零 → 落地弹跳
     * （updateEntityMovementAfterFallOn）→ 方块速度因子乘算。
     *
     * <p>piston 移动、stuckSpeedMultiplier、其它实体碰撞对机器人恒无；
     * FALLDAMAGE_RESETTING 射线检测（干草捆等提前清零摔落距离）省略。</p>
     */
    private void move(Vec3 delta) {
        delta = maybeBackOffFromEdge(delta);

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
        } else {
            minorHorizontalCollision = false;
        }

        // 脚下方块与摔落距离结算（均基于位移后的新位置）
        int effectState = onPosLegacyState();
        checkFallDamage(movement.y);

        // 水平碰撞轴速度归零
        if (horizontalCollision) {
            if (xCollision) {
                deltaX = 0.0;
            }
            if (zCollision) {
                deltaZ = 0.0;
            }
        }

        // 落地弹跳分派（粘液块/床反弹，其余方块垂直速度归零）
        if (yCollision) {
            updateEntityMovementAfterFallOn(effectState);
        }

        // 方块速度因子（对齐 Entity.move 末尾：速度 × (speedFactor, 1, speedFactor)）
        float blockSpeedFactor = getBlockSpeedFactor();
        deltaX *= blockSpeedFactor;
        deltaZ *= blockSpeedFactor;
    }

    /**
     * 对齐 Player.maybeBackOffFromEdge（moverType 恒 SELF）：未上升、按住潜行键
     * （isStayingOnGroundSurface）且在地面附近（isAboveGround）时，水平位移若使玩家
     * 悬空（canFallAtLeast）则按 0.05 逐步回退，直至不再悬空（潜行防走边；
     * abilities.flying 对机器人恒 false）。
     */
    private Vec3 maybeBackOffFromEdge(Vec3 delta) {
        if (!(delta.y > 0.0) && lastKeyShift && isAboveGround(MAX_UP_STEP)) {
            double deltaX = delta.x;
            double deltaZ = delta.z;
            double stepX = Math.signum(deltaX) * EDGE_BACKOFF_STEP;
            double stepZ = Math.signum(deltaZ) * EDGE_BACKOFF_STEP;
            while (deltaX != 0.0 && canFallAtLeast(deltaX, 0.0, (double) MAX_UP_STEP)) {
                if (Math.abs(deltaX) <= EDGE_BACKOFF_STEP) {
                    deltaX = 0.0;
                    break;
                }
                deltaX -= stepX;
            }
            while (deltaZ != 0.0 && canFallAtLeast(0.0, deltaZ, (double) MAX_UP_STEP)) {
                if (Math.abs(deltaZ) <= EDGE_BACKOFF_STEP) {
                    deltaZ = 0.0;
                    break;
                }
                deltaZ -= stepZ;
            }
            while (deltaX != 0.0 && deltaZ != 0.0 && canFallAtLeast(deltaX, deltaZ, (double) MAX_UP_STEP)) {
                if (Math.abs(deltaX) <= EDGE_BACKOFF_STEP) {
                    deltaX = 0.0;
                } else {
                    deltaX -= stepX;
                }
                if (Math.abs(deltaZ) <= EDGE_BACKOFF_STEP) {
                    deltaZ = 0.0;
                } else {
                    deltaZ -= stepZ;
                }
            }
            return new Vec3(deltaX, delta.y, deltaZ);
        }
        return delta;
    }

    /** 对齐 Player.isAboveGround：着地，或摔落距离小于下台阶高度且下方不会悬空 */
    private boolean isAboveGround(float maxDownStep) {
        return onGround || (fallDistance < (double) maxDownStep
                && !canFallAtLeast(0.0, 0.0, (double) maxDownStep - fallDistance));
    }

    /** 对齐 Player.canFallAtLeast：水平偏移 (deltaX, deltaZ) 后向下 minHeight 内无碰撞 */
    private boolean canFallAtLeast(double deltaX, double deltaZ, double minHeight) {
        AABB box = boundingBox();
        AABB test = new AABB(
                box.minX + 1.0E-7 + deltaX, box.minY - minHeight - 1.0E-7, box.minZ + 1.0E-7 + deltaZ,
                box.maxX - 1.0E-7 + deltaX, box.minY, box.maxZ - 1.0E-7 + deltaZ);
        return noCollision(test);
    }

    /**
     * 对齐 Entity.checkFallDamage + LivingEntity.causeFallDamage / calculateFallDamage：
     * 未入水且垂直位移为负时累计摔落距离（位移先按 float 截断再转入 double）；
     * 着地时结算摔落伤害并清零。伤害 = floor((fallDistance + 1e-6 − 安全高度) × 摔落乘数)，
     * 安全高度默认 3.0 且跳跃提升每级 +1；水/岩浆/缓降/漂浮时摔落距离清零。
     */
    private void checkFallDamage(double ya) {
        if (!isInWater() && ya < 0.0) {
            fallDistance -= (double) ((float) ya);
        }
        if (onGround) {
            if (fallDistance > 0.0) {
                causeFallDamage(fallDistance);
            }
            fallDistance = 0.0;
        }
    }

    /**
     * 对齐 LivingEntity.causeFallDamage / calculateFallDamage：按原版公式结算摔落伤害。
     * 本结算仅用于日志与寻路高度决策参考，真实伤害由服务器裁定；开关关闭时不结算。
     */
    private void causeFallDamage(double fallDist) {
        if (!fallDamageEnabled) {
            return;
        }
        float safeFall = SAFE_FALL_DISTANCE
                + (jumpBoostAmplifier >= 0 ? JUMP_BOOST_SAFE_FALL * (jumpBoostAmplifier + 1) : 0.0F);
        int dmg = (int) Math.floor((fallDist + 1.0E-6 - safeFall) * FALL_DAMAGE_MULTIPLIER);
        if (dmg > 0) {
            log.info("[Running] 摔落伤害结算：高度 {} 格 → {} 点伤害（本地模拟，真实伤害以服务器为准）。",
                    String.format("%.2f", fallDist), dmg);
        }
    }

    /**
     * 对齐 Block.updateEntityMovementAfterFallOn 的方块分派：粘液块按 -y×1.0 反弹、
     * 床按 -y×0.66 反弹（按住潜行键 isSuppressingBounce 时回落默认行为：垂直速度归零）。
     */
    private void updateEntityMovementAfterFallOn(int effectState) {
        String name = blockNames.nameOf(effectState);
        if ("slime_block".equals(name)) {
            if (lastKeyShift) {
                deltaY = 0.0;
            } else {
                bounceUp((double) 1.0F);
            }
        } else if (name != null && name.endsWith("_bed")) {
            if (lastKeyShift) {
                deltaY = 0.0;
            } else {
                bounceUp((double) 0.66F);
            }
        } else {
            deltaY = 0.0;
        }
    }

    /** 对齐 SlimeBlock/BedBlock.bounceUp：下落速度反向放大（玩家 factor 恒 1.0） */
    private void bounceUp(double factor) {
        if (deltaY < 0.0) {
            deltaY = -deltaY * factor;
        }
    }

    /**
     * 对齐 Entity.applyEffectsFromBlocks 的 stepOn 部分（isAffectedByBlocks 恒真）：
     * 着地时取脚下方块（getOnPosLegacy）结算踩踏效果——粘液块在未按潜行键
     * （isSteppingCarefully）且垂直速度近零时按 0.4+|y|×0.2 衰减水平速度。
     */
    private void applyStepOnEffects() {
        if (!onGround) {
            return;
        }
        String name = blockNames.nameOf(onPosLegacyState());
        if ("slime_block".equals(name)) {
            double absDeltaY = Math.abs(deltaY);
            if (absDeltaY < 0.1 && !lastKeyShift) {
                double scale = 0.4 + absDeltaY * 0.2;
                deltaX *= scale;
                deltaZ *= scale;
            }
        }
    }

    /**
     * 脚下方块状态（getOnPosLegacy = getOnPos(0.2F)）；未追踪支持方块
     * （mainSupportingBlockPos），回退官方 fallback：floor(y − 0.2)。
     */
    private int onPosLegacyState() {
        return world.getBlockStateId(floorToInt(x), floorToInt(y - (double) 0.2F), floorToInt(z));
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

    /** 玩家碰撞盒：宽 0.6 × 当前姿势高度，以脚底中心为基准（EntityDimensions.makeBoundingBox） */
    private AABB boundingBox() {
        return AABB.ofEntity(x, y, z, PLAYER_WIDTH, poseHeight(pose));
    }

    /** 对齐 MathHelper.floor */
    private static int floorToInt(double value) {
        return (int) Math.floor(value);
    }

    /** 对齐 MathHelper.ceil */
    private static int ceilToInt(double value) {
        int truncated = (int) value;
        return value > (double) truncated ? truncated + 1 : truncated;
    }

    /** 对齐 Mth.equal（1.0E-5 容差） */
    private static boolean equal(double a, double b) {
        return Math.abs(b - a) < 1.0E-5;
    }

    /** 对齐 Vec3.isFinite：三分量均为有限双精度值（NaN/无穷视为非法向量） */
    private static boolean isFinite(double vx, double vy, double vz) {
        return Double.isFinite(vx) && Double.isFinite(vy) && Double.isFinite(vz);
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
                segmentEnd.x, segmentEnd.y + (double) poseHeight(pose), segmentEnd.z)
                .inflate((double) PLAYER_WIDTH, 0.0, (double) PLAYER_WIDTH);
        Vec3 segBegin = moveBegin.add(0.0, (double) 0.51F, 0.0);
        Vec3 segEnd = segmentEnd.add(0.0, (double) 0.51F, 0.0);
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
                if (aboveState != RunningWorld.UNLOADED && aboveState != RunningWorld.AIR
                        && blockNames.hasCollision(aboveState)) {
                    obstacleHeight = (float) (blockNames.collisionHeight(aboveState) + (double) aboveY);
                    if ((double) obstacleHeight - y > (double) jumpHeight) {
                        return;
                    }
                }
                // 官方 ceilingPos 复检：自 steps=2 起逐格上移检查头顶空间（对齐 LocalPlayer.updateAutoJump）
                if (steps > 1) {
                    ceilingY++;
                    if (hasCollisionShapeAt(ceilingX, ceilingY, ceilingZ)) {
                        return;
                    }
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

    /**
     * 收集指定范围内的方块碰撞盒（未加载区块视为无障碍；楼梯双盒、半砖 0.5 高近似）。
     *
     * <p>迭代范围与过滤均对齐原版 {@code BlockCollisions}：范围在
     * {@code floor(min ∓ 1e-7)} 的基础上向外扩一格，再把生成出的碰撞盒按
     * “与查询盒严格相交”过滤（等价于原版的
     * {@code Shapes.joinIsNotEmpty(shape, entityShape, AND)}）。两者缺一不可：
     * 部分不完整方块的碰撞形状越出自身格（栅栏/墙高 1.5），其顶面位于上一格，
     * 若只按 {@code floor(minY)..floor(maxY)} 迭代，玩家正好站在栅栏顶（脚部 Y
     * 为己提交界值）时会漏掉该碰撞格而直接穿模坠落；而只外扩不过滤则会把相邻格
     * 里够不到本实体的形状误当成上台阶候选高度，导致无谓抬升。</p>
     */
    private List<AABB> collectBlockBoxes(AABB area) {
        List<AABB> boxes = new ArrayList<>();
        int minBX = floorToInt(area.minX - 1.0E-7) - 1;
        int maxBX = floorToInt(area.maxX + 1.0E-7) + 1;
        int minBZ = floorToInt(area.minZ - 1.0E-7) - 1;
        int maxBZ = floorToInt(area.maxZ + 1.0E-7) + 1;
        int minBY = Math.max(floorToInt(area.minY - 1.0E-7) - 1, world.getMinY());
        int maxBY = Math.min(floorToInt(area.maxY + 1.0E-7) + 1, world.getMinY() + world.getHeight() - 1);

        List<AABB> cellBoxes = new ArrayList<>(4);
        for (int bx = minBX; bx <= maxBX; bx++) {
            for (int bz = minBZ; bz <= maxBZ; bz++) {
                for (int by = minBY; by <= maxBY; by++) {
                    int stateId = world.getBlockStateId(bx, by, bz);
                    if (stateId == RunningWorld.UNLOADED) {
                        continue;
                    }
                    cellBoxes.clear();
                    blockNames.addCollisionBoxes(stateId, bx, by, bz, cellBoxes);
                    for (int i = 0; i < cellBoxes.size(); i++) {
                        AABB box = cellBoxes.get(i);
                        if (box.intersects(area)) {
                            boxes.add(box);
                        }
                    }
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
