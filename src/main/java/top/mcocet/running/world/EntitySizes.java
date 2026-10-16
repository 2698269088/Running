package top.mcocet.running.world;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 可被实体推挤的实体类型尺寸表（宽 × 高，单位：格）。
 *
 * <p>数据来源：Minecraft 26.1.2 反编译源码 {@code net.minecraft.world.entity.EntityType}
 * 中各实体的注册行 {@code register("name", Builder...sized(w, h))}。表内仅收录
 * {@code LivingEntity} 系实体——只有它们拥有 {@code pushEntities()} 行为、能在
 * 客户端把本地玩家推开（对齐 {@code EntitySelector.pushableBy} 的客户端分支：
 * 客户端上实体只推本地玩家）。</p>
 *
 * <p>{@code MobCategory} 非 MISC 的注册实体全部为 {@code LivingEntity} 系；
 * MISC 中的例外为 {@code armor_stand / copper_golem / iron_golem / mannequin /
 * player / snow_golem / villager}（逐类核对继承链确认），其余 MISC 实体
 * （物品、箭、船、矿车、展示框等）均不可推。</p>
 *
 * <p>已知近似：使用注册尺寸（站立基准），不模拟姿态变化（潜行/游泳）、
 * 幼体缩放与史莱姆大小段位；未知类型（模组实体）不参与推挤。</p>
 *
 * <p>推方资格由 {@link #pushesLocalPlayer} 判定：表内实体再排除
 * {@link #NEVER_PUSHES} 中"有覆写、不推玩家"的类型；未建模旁观者玩家
 * （官方 noPhysics 阻断推挤）与队伍碰撞规则，按无队伍普通玩家近似。</p>
 */
public final class EntitySizes {

    /** 表内条目格式：{@code name:width,height}，分号分隔 */
    private static final String DATA =
            "allay:0.35,0.6;armadillo:0.7,0.65;armor_stand:0.5,1.975;axolotl:0.75,0.42;"
          + "bat:0.5,0.9;bee:0.7,0.6;blaze:0.6,1.8;bogged:0.6,1.99;"
          + "breeze:0.6,1.77;camel:1.7,2.375;camel_husk:1.7,2.375;cat:0.6,0.7;"
          + "cave_spider:0.7,0.5;chicken:0.4,0.7;cod:0.5,0.3;copper_golem:0.49,0.98;"
          + "cow:0.9,1.4;creaking:0.9,2.7;creeper:0.6,1.7;dolphin:0.9,0.6;"
          + "donkey:1.3964844,1.5;drowned:0.6,1.95;elder_guardian:1.9975,1.9975;enderman:0.6,2.9;"
          + "endermite:0.4,0.3;ender_dragon:16.0,8.0;evoker:0.6,1.95;fox:0.6,0.7;"
          + "frog:0.5,0.5;ghast:4.0,4.0;happy_ghast:4.0,4.0;giant:3.6,12.0;"
          + "glow_squid:0.8,0.8;goat:0.9,1.3;guardian:0.85,0.85;hoglin:1.3964844,1.4;"
          + "horse:1.3964844,1.6;husk:0.6,1.95;illusioner:0.6,1.95;iron_golem:1.4,2.7;"
          + "llama:0.9,1.87;magma_cube:0.52,0.52;mannequin:0.6,1.8;mooshroom:0.9,1.4;"
          + "mule:1.3964844,1.6;nautilus:0.875,0.95;ocelot:0.6,0.7;panda:1.3,1.25;"
          + "parched:0.6,1.99;parrot:0.5,0.9;phantom:0.9,0.5;pig:0.9,0.9;"
          + "piglin:0.6,1.95;piglin_brute:0.6,1.95;pillager:0.6,1.95;polar_bear:1.4,1.4;"
          + "pufferfish:0.7,0.7;rabbit:0.49,0.6;ravager:1.95,2.2;salmon:0.7,0.4;"
          + "sheep:0.9,1.3;shulker:1.0,1.0;silverfish:0.4,0.3;skeleton:0.6,1.99;"
          + "skeleton_horse:1.3964844,1.6;slime:0.52,0.52;sniffer:1.9,1.75;snow_golem:0.7,1.9;"
          + "spider:1.4,0.9;squid:0.8,0.8;stray:0.6,1.99;strider:0.9,1.7;"
          + "tadpole:0.4,0.3;trader_llama:0.9,1.87;tropical_fish:0.5,0.4;turtle:1.2,0.4;"
          + "vex:0.4,0.8;villager:0.6,1.95;vindicator:0.6,1.95;wandering_trader:0.6,1.95;"
          + "warden:0.9,2.9;witch:0.6,1.95;wither:0.9,3.5;wither_skeleton:0.7,2.4;"
          + "wolf:0.6,0.85;zoglin:1.3964844,1.4;zombie:0.6,1.95;zombie_horse:1.3964844,1.6;"
          + "zombie_nautilus:0.875,0.95;zombie_villager:0.6,1.95;zombified_piglin:0.6,1.95;player:0.6,1.8;";

    /** 实体类型名（协议注册名，小写）→ 尺寸 {width, height} */
    private static final Map<String, float[]> TABLE = new HashMap<>(192);

    static {
        for (String entry : DATA.split(";")) {
            if (entry.isEmpty()) {
                continue;
            }
            int colon = entry.indexOf(':');
            int comma = entry.indexOf(',');
            if (colon <= 0 || comma <= colon) {
                continue;
            }
            String name = entry.substring(0, colon);
            float width = Float.parseFloat(entry.substring(colon + 1, comma));
            float height = Float.parseFloat(entry.substring(comma + 1));
            TABLE.put(name, new float[]{width, height});
        }
    }

    /**
     * 客户端上永不推挤本地玩家的类型（即便为 LivingEntity 系、即便在尺寸表内）：
     * <ul>
     *   <li>{@code bat} —— {@code Bat.pushEntities()/doPush()} 皆为空覆写；</li>
     *   <li>{@code armor_stand} —— {@code doPush()} 空覆写，
     *       {@code pushEntities()} 仅推可骑乘矿车；</li>
     *   <li>{@code parrot} —— {@code doPush()} 对 {@code Player} 直接跳过；</li>
     *   <li>{@code vex} —— {@code Vex.tick()} 全程 {@code noPhysics=true}，
     *       {@code Entity.push} 的 noPhysics 门控失败；</li>
     *   <li>{@code ender_dragon} —— 构造起永久 {@code noPhysics=true}，同上。</li>
     * </ul>
     * 已核对的会推例外：{@code iron_golem}/{@code warden} 的 {@code doPush()}
     * 无条件调用 super；{@code creaking} 的覆写仅在接收方一侧（推方行为正常）。
     */
    private static final Set<String> NEVER_PUSHES = Set.of(
            "bat", "armor_stand", "parrot", "vex", "ender_dragon");

    private EntitySizes() {
    }

    /**
     * 实体类型的碰撞盒尺寸；非可推类型（不在表内）返回 {@code null}。
     *
     * @param typeName 实体类型注册名（小写或大写均可，内部统一转小写）
     * @return {width, height}，或 {@code null} 表示该类型不参与推挤
     */
    public static float[] dimensionsOf(String typeName) {
        if (typeName == null) {
            return null;
        }
        return TABLE.get(typeName.toLowerCase(Locale.ROOT));
    }

    /** 该类型是否为可推的 LivingEntity 系实体（等价于是否在表内） */
    public static boolean isPushable(String typeName) {
        return dimensionsOf(typeName) != null;
    }

    /**
     * 该类型是否会推挤本地玩家（客户端 {@code ClientLevel.getPushableEntities}
     * 覆写语义）：在尺寸表内且不属于 {@link #NEVER_PUSHES}。
     */
    public static boolean pushesLocalPlayer(String typeName) {
        if (typeName == null) {
            return false;
        }
        String name = typeName.toLowerCase(Locale.ROOT);
        return TABLE.containsKey(name) && !NEVER_PUSHES.contains(name);
    }

    /** 表内实体类型数量（供启动日志） */
    public static int size() {
        return TABLE.size();
    }
}
