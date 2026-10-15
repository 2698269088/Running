package top.mcocet.running.world;

import top.mcocet.running.math.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 附近实体跟踪表：维护会推挤本地玩家的实体位置与插值状态，
 * 供玩家物理的实体推挤（pushEntities）使用。
 *
 * <p>对齐官方客户端（Minecraft 26.1.2）的处理链：
 * <ul>
 *   <li>{@code ClientPacketListener.handleAddEntity} —— 新实体按包内位置直接放置
 *       （recreateFromPacket → setPos，无插值）；仅会推挤本地玩家的实体参与跟踪
 *       （{@link EntitySizes#pushesLocalPlayer}；其余类型或因无 pushEntities
 *       行为、或因覆写不推玩家而被忽略）；</li>
 *   <li>{@code handleMoveEntity} —— 增量包经 {@code VecDeltaCodec} 解码：
 *       新位置 = 网格化(base) + 增量（分量为 0 时保持 base 原值），随后 base
 *       更新为新位置；再经 {@code moveOrInterpolateTo} 进入插值；</li>
 *   <li>{@code handleEntityPositionSync}——绝对位置包，位置基准同步更新
 *       （setBase），随后进入插值；距离平方超过 4096（64 格）时直接对齐
 *       （官方 tooBigToInterpolate 判定）；</li>
 *   <li>{@code handleTeleportEntity} —— 绝对位置包（支持 relatives 相对字段），
 *       近距走插值；不更新增量基准（与官方客户端一致，服务端同一路径下
 *       也不更新 codec base）；</li>
 *   <li>{@code InterpolationHandler} —— 插值目标默认 3 步，每 tick 按剩余步数
 *       线性逼近（alpha = 1/剩余步数，对齐 Mth.lerp）。</li>
 * </ul></p>
 *
 * <p>线程模型：网络线程写入（add/move/remove），移动引擎线程每 tick 推进插值
 * 并读取查询；所有状态访问以本对象锁保护，查询返回不可变快照。</p>
 */
public final class EntityTracker {

    /** 官方客户端插值步数（InterpolationHandler.DEFAULT_INTERPOLATION_STEPS） */
    private static final int DEFAULT_INTERPOLATION_STEPS = 3;

    /** 超过该平方距离（64 格）直接对齐（官方 tooBigToInterpolate 的 4096.0） */
    private static final double TOO_BIG_TO_INTERPOLATE_SQR = 4096.0;

    /** 增量包坐标网格（VecDeltaCodec 的 4096.0F） */
    private static final double DELTA_GRID = 4096.0;

    private final Map<Integer, TrackedEntity> entities = new HashMap<>();
    /** 玩家 UUID → 当前实体 ID（仅带 UUID 且进入跟踪表的实体登记；供按名定位） */
    private final Map<UUID, Integer> uuidToId = new HashMap<>();

    /** 单个被跟踪实体的状态（位置为脚底中心；base 为增量解码基准） */
    private static final class TrackedEntity {
        final int id;
        final UUID uuid;
        final String typeName;
        final double width;
        final double height;
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
        double baseX;
        double baseY;
        double baseZ;
        double targetX;
        double targetY;
        double targetZ;
        int lerpSteps;

        TrackedEntity(int id, UUID uuid, String typeName, double width, double height,
                      double x, double y, double z, float yaw, float pitch) {
            this.id = id;
            this.uuid = uuid;
            this.typeName = typeName;
            this.width = width;
            this.height = height;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.baseX = x;
            this.baseY = y;
            this.baseZ = z;
            this.targetX = x;
            this.targetY = y;
            this.targetZ = z;
        }
    }

    /**
     * 推挤查询使用的实体位置快照（不可变，脱离跟踪表锁安全读取）。
     *
     * @param id       实体 ID
     * @param typeName 实体类型注册名
     * @param x        脚底中心 x
     * @param y        脚底中心 y
     * @param z        脚底中心 z
     * @param width    碰撞盒宽度
     * @param height   碰撞盒高度
     */
    public record EntitySnapshot(int id, String typeName,
                                 double x, double y, double z,
                                 double width, double height) {
    }

    // ------------------------------------------------------------------
    // 网络线程写入
    // ------------------------------------------------------------------

    /**
     * 新增实体（对齐 handleAddEntity：直接放置，无插值）。
     * 不推本地玩家的类型（{@link EntitySizes#pushesLocalPlayer} 为 false）忽略。
     *
     * @param uuid 实体 UUID（玩家经 {@code ClientboundAddEntityPacket} 携带；可为 null）
     */
    public synchronized void addEntity(int id, UUID uuid, String typeName, double x, double y, double z,
                                       float yaw, float pitch) {
        if (!EntitySizes.pushesLocalPlayer(typeName)) {
            return;
        }
        float[] size = EntitySizes.dimensionsOf(typeName);
        entities.put(id, new TrackedEntity(id, uuid, typeName, size[0], size[1], x, y, z, yaw, pitch));
        if (uuid != null) {
            uuidToId.put(uuid, id);
        }
    }

    /**
     * 增量位置更新（对齐 handleMoveEntity 的 VecDeltaCodec.decode + setBase + 插值）：
     * 新位置 = 网格化(base) + 增量（分量为 0 时保持 base 原值），base 更新为新位置。
     */
    public synchronized void moveRelative(int id, double dx, double dy, double dz) {
        TrackedEntity entity = entities.get(id);
        if (entity == null) {
            return;
        }
        if (dx == 0.0 && dy == 0.0 && dz == 0.0) {
            return;
        }
        double newX = dx == 0.0 ? entity.baseX : gridRound(entity.baseX) + dx;
        double newY = dy == 0.0 ? entity.baseY : gridRound(entity.baseY) + dy;
        double newZ = dz == 0.0 ? entity.baseZ : gridRound(entity.baseZ) + dz;
        entity.baseX = newX;
        entity.baseY = newY;
        entity.baseZ = newZ;
        interpolateTo(entity, newX, newY, newZ);
    }

    /**
     * 绝对位置更新（对齐 handleEntityPositionSync 的 moveOrInterpolateTo；
     * 距离平方超过 4096 时直接对齐）。
     *
     * @param updateBase 是否同步更新增量基准（位置同步包为 true）
     */
    public synchronized void moveAbsolute(int id, double x, double y, double z, boolean updateBase) {
        TrackedEntity entity = entities.get(id);
        if (entity == null) {
            return;
        }
        if (updateBase) {
            entity.baseX = x;
            entity.baseY = y;
            entity.baseZ = z;
        }
        interpolateOrSnap(entity, x, y, z);
    }

    /**
     * 传送（对齐 {@code handleTeleportEntity → setValuesFromPositionPacket}）：
     * relatives 含 X/Y/Z 的分量为相对当前（插值后）位置的偏移；近距走插值，
     * 距离平方超过 4096 直接对齐；不更新增量基准——官方该路径下 positionCodec
     * 的 base 保持不变，后续增量包仍以旧基准解码。
     */
    public synchronized void teleport(int id, double x, double y, double z,
                                      boolean relativeX, boolean relativeY, boolean relativeZ) {
        TrackedEntity entity = entities.get(id);
        if (entity == null) {
            return;
        }
        interpolateOrSnap(entity,
                relativeX ? x + entity.x : x,
                relativeY ? y + entity.y : y,
                relativeZ ? z + entity.z : z);
    }

    /** 朝向更新（对齐 handleMoveEntity 的 Rot 分支；推挤计算不使用朝向） */
    public synchronized void rotate(int id, float yaw, float pitch) {
        TrackedEntity entity = entities.get(id);
        if (entity == null) {
            return;
        }
        entity.yaw = yaw;
        entity.pitch = pitch;
    }

    /** 移除实体（对齐 handleRemoveEntities） */
    public synchronized void removeEntities(int[] ids) {
        for (int id : ids) {
            TrackedEntity entity = entities.remove(id);
            if (entity != null && entity.uuid != null) {
                uuidToId.remove(entity.uuid, id);
            }
        }
    }

    /**
     * 清空全部实体（登录无条件清空；重生仅在维度变化时清空，对齐官方
     * handleRespawn 的 dimensionChanged 门控——同维度死亡重生保留实体）。
     */
    public synchronized void clear() {
        entities.clear();
        uuidToId.clear();
    }

    // ------------------------------------------------------------------
    // 引擎线程推进与查询
    // ------------------------------------------------------------------

    /** 推进一个 tick 的插值（对齐 InterpolationHandler.interpolate：按剩余步数线性逼近） */
    public synchronized void tick() {
        for (TrackedEntity entity : entities.values()) {
            if (entity.lerpSteps <= 0) {
                continue;
            }
            double alpha = 1.0 / entity.lerpSteps;
            entity.x += alpha * (entity.targetX - entity.x);
            entity.y += alpha * (entity.targetY - entity.y);
            entity.z += alpha * (entity.targetZ - entity.z);
            --entity.lerpSteps;
        }
    }

    /**
     * 按 UUID 查询该玩家实体的当前位置快照（脱离跟踪表锁安全读取）；
     * 未跟踪（未加载 / 已移除 / 非推挤类型）返回 {@code null}。
     */
    public synchronized EntitySnapshot findByUuid(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        Integer id = uuidToId.get(uuid);
        if (id == null) {
            return null;
        }
        TrackedEntity entity = entities.get(id);
        if (entity == null) {
            return null;
        }
        return new EntitySnapshot(entity.id, entity.typeName,
                entity.x, entity.y, entity.z, entity.width, entity.height);
    }

    /**
     * 查询碰撞盒与给定包围盒相交的实体快照（对应官方
     * {@code Level.getPushableEntities} 的相交查询，查询盒为推方实体的碰撞盒）。
     */
    public synchronized List<EntitySnapshot> collidingWith(AABB box) {
        List<EntitySnapshot> result = new ArrayList<>();
        for (TrackedEntity entity : entities.values()) {
            if (box.intersects(AABB.ofEntity(entity.x, entity.y, entity.z, entity.width, entity.height))) {
                result.add(new EntitySnapshot(entity.id, entity.typeName,
                        entity.x, entity.y, entity.z, entity.width, entity.height));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 近距进入插值 / 距离平方超过 4096（64 格）直接对齐（对齐官方
     * moveOrInterpolateTo 的 tooBigToInterpolate 分支）。
     */
    private static void interpolateOrSnap(TrackedEntity entity, double x, double y, double z) {
        double distSqr = sqr(x - entity.x) + sqr(y - entity.y) + sqr(z - entity.z);
        if (distSqr > TOO_BIG_TO_INTERPOLATE_SQR) {
            entity.x = x;
            entity.y = y;
            entity.z = z;
            entity.targetX = x;
            entity.targetY = y;
            entity.targetZ = z;
            entity.lerpSteps = 0;
        } else {
            interpolateTo(entity, x, y, z);
        }
    }

    /**
     * 设置插值目标（对齐 InterpolationHandler.interpolateTo：目标变化时重置步数，
     * 无进行中插值时同样重置；位置/朝向一致（位置相等）时不重启插值）。
     */
    private static void interpolateTo(TrackedEntity entity, double x, double y, double z) {
        if (entity.lerpSteps > 0
                && entity.targetX == x && entity.targetY == y && entity.targetZ == z) {
            return;
        }
        entity.targetX = x;
        entity.targetY = y;
        entity.targetZ = z;
        entity.lerpSteps = DEFAULT_INTERPOLATION_STEPS;
    }

    /** VecDeltaCodec.encode：网格化到 1/4096（Math.round(input * 4096.0F)） */
    private static double gridRound(double value) {
        return Math.round(value * DELTA_GRID) / DELTA_GRID;
    }

    private static double sqr(double value) {
        return value * value;
    }
}
