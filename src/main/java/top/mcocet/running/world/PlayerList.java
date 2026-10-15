package top.mcocet.running.world;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家列表镜像：维护服务器下发的 {@code UUID ↔ 用户名} 映射，
 * 供按用户名定位玩家实体使用（如 {@code goto follow <name>}）。
 *
 * <p>数据来源为 {@code ClientboundPlayerInfoUpdatePacket} 的 {@code ADD_PLAYER}
 * 动作（其 {@code GameProfile} 含用户名与 {@code profileId}）。重新登录后
 * 玩家列表整体重建，故登录包会清空本表。</p>
 *
 * <p>线程模型：网络线程写入，移动引擎 / 寻路计算线程读取；采用并发容器，
 * 读取无需加锁。用户名匹配大小写不敏感（内部以小写键存储）。</p>
 */
public final class PlayerList {

    /** UUID → 用户名（原始大小写，用于展示） */
    private final Map<UUID, String> idToName = new ConcurrentHashMap<>();
    /** 用户名（小写） → UUID */
    private final Map<String, UUID> nameToId = new ConcurrentHashMap<>();

    /** 记录一名玩家（ADD_PLAYER 时调用）。 */
    public void addPlayer(UUID uuid, String name) {
        if (uuid == null || name == null || name.isEmpty()) {
            return;
        }
        idToName.put(uuid, name);
        nameToId.put(name.toLowerCase(java.util.Locale.ROOT), uuid);
    }

    /** 按用户名（大小写不敏感）解析 UUID；未知返回 {@code null}。 */
    public UUID findByName(String name) {
        if (name == null) {
            return null;
        }
        return nameToId.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** 按 UUID 解析用户名；未知返回 {@code null}。 */
    public String getName(UUID uuid) {
        return uuid == null ? null : idToName.get(uuid);
    }

    /** 已知玩家用户名集合（并发视图，供 tab 补全遍历）。 */
    public Collection<String> knownNames() {
        return idToName.values();
    }

    /** 玩家数量（供调试日志）。 */
    public int size() {
        return idToName.size();
    }

    /** 清空全部映射（重新登录时调用）。 */
    public void clear() {
        idToName.clear();
        nameToId.clear();
    }
}
