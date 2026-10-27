package top.mcocet.running;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Running 插件的功能配置：以「工作目录 / Running / config.properties」持久化各项功能开关，
 * 与 {@code blocks.json} / {@code items.json} 一样相对进程工作目录解析（本插件是 xinbot 插件，
 * 没有 Bukkit 的 {@code getDataFolder()}）。
 *
 * <p>加载时机为插件 {@code onLoad}（见 {@link RunningCore#load()}），修改文件后需重启机器人 /
 * 重载插件才会生效。文件或其父目录缺失时，会自动写入一份带中文注释的默认模板；单个键缺失或
 * 非法（布尔写错、数值越界等）时按项回退到内置默认并告警，绝不因一处笔误使整个插件启动失败。</p>
 *
 * <p>取值格式为标准 {@code key=value}（{@link Properties}），支持 {@code #} 注释、UTF-8 编码。</p>
 */
public final class RunningConfig {
    private static final Logger log = LoggerFactory.getLogger(RunningConfig.class.getSimpleName());

    /** 配置所在子文件夹名（相对工作目录） */
    public static final String CONFIG_DIR = "Running";
    /** 配置文件名 */
    public static final String CONFIG_FILE = "config.properties";

    // ---- 键名 ----
    private static final String KEY_ALLOW_SPRINT = "pathfinding.allow-sprint";
    private static final String KEY_ALLOW_BREAK = "pathfinding.allow-break";
    private static final String KEY_PATH_MODE = "pathfinding.mode";
    private static final String KEY_STUCK_DIG = "pathfinding.stuck-dig-recovery";
    private static final String KEY_ALLOW_PLACE = "pathfinding.allow-place";
    private static final String KEY_TUNNEL_MAX_TICKS = "pathfinding.tunnel-max-ticks";
    private static final String KEY_BRIDGE_MAX_TICKS = "pathfinding.bridge-max-ticks";
    private static final String KEY_MAX_PLACE_TRIES = "pathfinding.max-place-tries";
    private static final String KEY_ALLOW_PARKOUR = "pathfinding.allow-parkour";
    private static final String KEY_ALLOW_GLIDE = "pathfinding.allow-glide";
    private static final String KEY_GLIDE_MAX_DISTANCE = "pathfinding.glide-max-distance";
    private static final String KEY_GLIDE_ROCKET_BUDGET = "pathfinding.glide-rocket-budget";
    private static final String KEY_MAX_FALL_HEIGHT = "pathfinding.max-fall-height";
    private static final String KEY_ARRIVE_RADIUS = "pathfinding.arrive-radius";
    private static final String KEY_WATER_PRIORITY = "pathfinding.water-priority";
    private static final String KEY_AUTO_JUMP = "physics.auto-jump";
    private static final String KEY_FALL_DAMAGE = "physics.fall-damage";
    private static final String KEY_KNOCKBACK = "physics.knockback";
    private static final String KEY_FLY_AUTO_EQUIP = "fly.auto-equip";
    private static final String KEY_FLY_AUTO_BOOST = "fly.auto-boost";
    private static final String KEY_FLY_AUTO_RESCUE = "fly.auto-rescue";
    private static final String KEY_FLY_FORCE_FLIGHT = "fly.force-flight";
    private static final String KEY_FLY_AUTO_REPLACE = "fly.auto-replace";
    private static final String KEY_FLY_BOOST_MIN_SPEED = "fly.boost-min-speed";
    private static final String KEY_FLY_GLIDE_PITCH = "fly.glide-pitch";
    private static final String KEY_FLY_ROCKET_DURATION = "fly.rocket-flight-duration";

    // ---- 默认值 ----
    public static final boolean DEFAULT_ALLOW_SPRINT = true;
    public static final boolean DEFAULT_ALLOW_BREAK = true;
    /** 寻路模式默认值：{@code move}=移动优先（能绕则绕，挖比绕快才挖）；{@code dig}=挖掘优先（直挖前进） */
    public static final String DEFAULT_PATH_MODE = "move";
    public static final boolean DEFAULT_STUCK_DIG = true;
    public static final boolean DEFAULT_ALLOW_PLACE = true;
    /** 直挖隧道总 tick 上限默认值（20 tick/秒，18000≈15 分钟）：越大本轮允许持续的搭桥/直挖越久 */
    public static final int DEFAULT_TUNNEL_MAX_TICKS = 18000;
    /** 单个缺口搭桥总 tick 预算默认值（含取物/进边/瞄准/确认） */
    public static final int DEFAULT_BRIDGE_MAX_TICKS = 600;
    /** 同一缺口最多发送放置（USE_ITEM_ON）次数默认值 */
    public static final int DEFAULT_MAX_PLACE_TRIES = 10;
    public static final boolean DEFAULT_ALLOW_PARKOUR = true;
    public static final boolean DEFAULT_ALLOW_GLIDE = true;
    /** 单次滑翔边最大水平跨度（格） */
    public static final int DEFAULT_GLIDE_MAX_DISTANCE = 128;
    /** 单次滑翔边预估火箭消耗上限 */
    public static final int DEFAULT_GLIDE_ROCKET_BUDGET = 32;
    public static final int DEFAULT_MAX_FALL_HEIGHT = 3;
    public static final double DEFAULT_ARRIVE_RADIUS = 0.5;
    /** 水中行为优先级默认值：{@code swim}=游泳优先（朝目标游过去）；{@code shore}=上岸优先（先找岸边） */
    public static final String DEFAULT_WATER_PRIORITY = "swim";
    public static final boolean DEFAULT_AUTO_JUMP = true;
    /** 摔落伤害模拟默认开启（仅本地物理结算，用于寻路高度决策与日志；真实伤害由服务器裁定） */
    public static final boolean DEFAULT_FALL_DAMAGE = true;
    /** 击退/爆炸冲量回写默认开启（关闭后忽略服务器的速度修正包） */
    public static final boolean DEFAULT_KNOCKBACK = true;
    /** fly start 时自动把背包鞘翅装到胸槽 */
    public static final boolean DEFAULT_FLY_AUTO_EQUIP = true;
    /** 滑翔中速度低于阈值时自动补烟花 */
    public static final boolean DEFAULT_FLY_AUTO_BOOST = true;
    /** 危险下坠自动起滑救援（移植 MS ReconnectAirRecovery：登录/传送/击退后高处坠亡前自救） */
    public static final boolean DEFAULT_FLY_AUTO_RESCUE = true;
    /** 强制飞行：旁路鞘翅耐久预算门控与迫降判定（创造模式服务器本就自动旁路） */
    public static final boolean DEFAULT_FLY_FORCE_FLIGHT = false;
    /** 耐久将尽时自动从背包更换新鞘翅继续飞 */
    public static final boolean DEFAULT_FLY_AUTO_REPLACE = true;
    /** 自动补弹的水平速度阈值（格/tick） */
    public static final double DEFAULT_FLY_BOOST_MIN_SPEED = 0.3;
    /** 滑翔巡航俯仰角（度；官方 26.1.2 弹道实测 0° 平视静止滑翔距离最远） */
    public static final float DEFAULT_FLY_GLIDE_PITCH = 0.0F;
    /** 无法解析组件时的火箭飞行时长（1~3，对齐 CRAFTABLE_DURATIONS） */
    public static final int DEFAULT_FLY_ROCKET_DURATION = 1;

    /** 首次运行时写出的默认模板（含中文说明，方便用户按需调整）。 */
    private static final String DEFAULT_TEMPLATE = """
            # Running 机器人插件功能配置
            # 修改后需重启机器人 / 重载插件才会生效；删除某行或留空则使用注释标注的默认值。

            # ---- 寻路 (goto / A*) ----
            # 是否允许寻路在平坦同层路径上疾跑（更快、代价更低）。默认 true
            pathfinding.allow-sprint=true
            # 是否允许通过挖掘方块开路（BREAK 移动）。默认 true
            pathfinding.allow-break=true
            # 寻路模式：move=移动优先（能绕则绕，仅当“挖比绕更省”时才挖）；dig=挖掘优先（允许直接挖开方块前进到目的地，隐含允许破块）。默认 move
            pathfinding.mode=move
            # 寻路卡住无法前进时，是否自动从背包取出合适工具朝前进方向挖通开路（需 allow-break 为 true）。默认 true
            pathfinding.stuck-dig-recovery=true
            # 直挖隧道遇到无路可踩的缺口时，是否允许用背包方块放置地板搭桥通过（优先忍者桥/垫脚面）。默认 true
            pathfinding.allow-place=true
            # 直挖隧道总 tick 上限（20 tick=1 秒）：本轮允许持续的直挖/搭桥总时长，超过仍未到达才判不可达（60~360000）。默认 18000（约 15 分钟）
            pathfinding.tunnel-max-ticks=18000
            # 单个缺口搭桥总 tick 预算（含取物/潜行进边/瞄准/确认），超时放弃该缺口后下一 tick 会重新规划继续搭（20~100000）。默认 600（约 30 秒）
            pathfinding.bridge-max-ticks=600
            # 同一缺口最多发送放置（USE_ITEM_ON）的次数，超过先放弃本缺口（1~1000）。默认 10
            pathfinding.max-place-tries=10
            # 是否允许 Parkour：冲刺 / 步行跳跨 1~3 格空隙（最复杂，部分反作弊可能敏感）。默认 true
            pathfinding.allow-parkour=true
            # 是否允许寻路使用鞘翅滑翔（GLIDE 移动：从高台滑飞坑洞/坠落到安全落点；还需胸槽已装鞘翅）。默认 true
            pathfinding.allow-glide=true
            # 单次滑翔边的最大水平跨度（格，16~512）。默认 128
            pathfinding.glide-max-distance=128
            # 单次滑翔边预估的火箭消耗上限（0~256，计入路径代价）。默认 32
            pathfinding.glide-rocket-budget=32
            # 允许的最大无伤坠落高度（格），更大的落差不走（0~64）。默认 3
            pathfinding.max-fall-height=3
            # 到达航点的判定半径（格），越小越精确但越易反复对齐（0.1~2.0）。默认 0.5
            pathfinding.arrive-radius=0.5
            # 水中行为优先级：swim=游泳优先（朝目标直接游过去）；shore=上岸优先（先找岸边再走）。默认 swim
            pathfinding.water-priority=swim

            # ---- 物理移动 ----
            # 是否开启自动跳跃（撞到一格边缘自动起跳）。默认 true
            physics.auto-jump=true
            # 是否模拟摔落伤害（着地时按原版公式结算并记录日志，供寻路高度决策参考；真实伤害由服务器裁定）。默认 true
            physics.fall-damage=true
            # 是否应用服务器下发的击退/爆炸冲量（近战/弓箭/爆炸的速度修正）。默认 true
            physics.knockback=true

            # ---- 鞘翅飞行 (fly / 烟花加速) ----
            # fly start 时若胸甲槽没有鞘翅，是否自动从背包把鞘翅装上去（复用背包搬迁点击链）。默认 true
            fly.auto-equip=true
            # 滑翔中水平速度低于阈值时自动从背包取烟花发射加速（维持平飞）。默认 true
            fly.auto-boost=true
            # 危险下坠自动救援：摔落距离超过阈值且已装鞘翅时自动起滑（不依赖任何移动目标）。默认 true
            fly.auto-rescue=true
            # 强制飞行：忽略鞘翅耐久预算门控与迫降判定（生存下耐久将尽仍继续滑翔，有坠亡风险；创造模式服务器不扣耐久，无需开启）。默认 false
            fly.force-flight=false
            # 鞘翅耐久将尽时自动从背包找一副新鞘翅换上继续飞（换翅期间不中断滑翔）。默认 true
            fly.auto-replace=true
            # 自动补烟花的水平速度阈值（格/tick，0.05~1.0；越高越早补弹）。默认 0.3
            fly.boost-min-speed=0.3
            # 滑翔巡航俯仰角（度，-89~30；负值抬头爬升减速、正值俯冲增速；官方公式实测 0 平视静止滑翔最远）。默认 0
            fly.glide-pitch=0
            # 无法从物品组件解析时的火箭飞行时长（1~3，对应加速窗口 (n+1)×10 tick）。默认 1
            fly.rocket-flight-duration=1
            """;

    private final Path configFile;
    private final boolean allowSprint;
    private final boolean allowBreak;
    private final String pathMode;
    private final boolean stuckDigRecovery;
    private final boolean allowPlace;
    private final int tunnelMaxTicks;
    private final int bridgeMaxTicks;
    private final int maxPlaceTries;
    private final boolean allowParkour;
    private final boolean allowGlide;
    private final int glideMaxDistance;
    private final int glideRocketBudget;
    private final int maxFallHeight;
    private final double arriveRadius;
    private final String waterPriority;
    private final boolean autoJump;
    private final boolean fallDamage;
    private final boolean knockback;
    private final boolean flyAutoEquip;
    private final boolean flyAutoBoost;
    private final boolean flyAutoRescue;
    private final boolean flyForceFlight;
    private final boolean flyAutoReplace;
    private final double flyBoostMinSpeed;
    private final float flyGlidePitch;
    private final int flyRocketDuration;

    private RunningConfig(Path configFile, boolean allowSprint, boolean allowBreak, String pathMode,
                          boolean stuckDigRecovery,
                          boolean allowPlace, int tunnelMaxTicks, int bridgeMaxTicks, int maxPlaceTries,
                          boolean allowParkour, boolean allowGlide, int glideMaxDistance, int glideRocketBudget,
                          int maxFallHeight, double arriveRadius, String waterPriority,
                          boolean autoJump, boolean fallDamage, boolean knockback,
                          boolean flyAutoEquip, boolean flyAutoBoost, boolean flyAutoRescue,
                          boolean flyForceFlight, boolean flyAutoReplace,
                          double flyBoostMinSpeed,
                          float flyGlidePitch, int flyRocketDuration) {
        this.configFile = configFile;
        this.allowSprint = allowSprint;
        this.allowBreak = allowBreak;
        this.pathMode = pathMode;
        this.stuckDigRecovery = stuckDigRecovery;
        this.allowPlace = allowPlace;
        this.tunnelMaxTicks = tunnelMaxTicks;
        this.bridgeMaxTicks = bridgeMaxTicks;
        this.maxPlaceTries = maxPlaceTries;
        this.allowParkour = allowParkour;
        this.allowGlide = allowGlide;
        this.glideMaxDistance = glideMaxDistance;
        this.glideRocketBudget = glideRocketBudget;
        this.maxFallHeight = maxFallHeight;
        this.arriveRadius = arriveRadius;
        this.waterPriority = waterPriority;
        this.autoJump = autoJump;
        this.fallDamage = fallDamage;
        this.knockback = knockback;
        this.flyAutoEquip = flyAutoEquip;
        this.flyAutoBoost = flyAutoBoost;
        this.flyAutoRescue = flyAutoRescue;
        this.flyForceFlight = flyForceFlight;
        this.flyAutoReplace = flyAutoReplace;
        this.flyBoostMinSpeed = flyBoostMinSpeed;
        this.flyGlidePitch = flyGlidePitch;
        this.flyRocketDuration = flyRocketDuration;
    }

    /**
     * 从工作目录下的 {@code Running/config.properties} 加载配置。
     * 目录 / 文件缺失时自动创建并写入默认模板；读取异常时返回全默认配置（不抛出）。
     */
    public static RunningConfig load() {
        Path dir = Paths.get(CONFIG_DIR);
        Path file = dir.resolve(CONFIG_FILE);
        Properties props = new Properties();
        try {
            if (Files.notExists(dir)) {
                Files.createDirectories(dir);
            }
            if (Files.notExists(file)) {
                Files.writeString(file, DEFAULT_TEMPLATE, StandardCharsets.UTF_8);
                log.info("[Running] 已生成默认配置文件：{}（修改后重启生效）。", file.toAbsolutePath());
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
        } catch (IOException e) {
            log.warn("[Running] 创建或读取配置文件失败，本次使用默认配置：{}", e.toString());
        }

        boolean allowSprint = readBool(props, KEY_ALLOW_SPRINT, DEFAULT_ALLOW_SPRINT);
        boolean allowBreak = readBool(props, KEY_ALLOW_BREAK, DEFAULT_ALLOW_BREAK);
        String pathMode = readEnum(props, KEY_PATH_MODE, DEFAULT_PATH_MODE, "move", "dig");
        boolean stuckDigRecovery = readBool(props, KEY_STUCK_DIG, DEFAULT_STUCK_DIG);
        boolean allowPlace = readBool(props, KEY_ALLOW_PLACE, DEFAULT_ALLOW_PLACE);
        int tunnelMaxTicks = readInt(props, KEY_TUNNEL_MAX_TICKS, DEFAULT_TUNNEL_MAX_TICKS, 60, 360000);
        int bridgeMaxTicks = readInt(props, KEY_BRIDGE_MAX_TICKS, DEFAULT_BRIDGE_MAX_TICKS, 20, 100000);
        int maxPlaceTries = readInt(props, KEY_MAX_PLACE_TRIES, DEFAULT_MAX_PLACE_TRIES, 1, 1000);
        boolean allowParkour = readBool(props, KEY_ALLOW_PARKOUR, DEFAULT_ALLOW_PARKOUR);
        boolean allowGlide = readBool(props, KEY_ALLOW_GLIDE, DEFAULT_ALLOW_GLIDE);
        int glideMaxDistance = readInt(props, KEY_GLIDE_MAX_DISTANCE, DEFAULT_GLIDE_MAX_DISTANCE, 16, 512);
        int glideRocketBudget = readInt(props, KEY_GLIDE_ROCKET_BUDGET, DEFAULT_GLIDE_ROCKET_BUDGET, 0, 256);
        int maxFallHeight = readInt(props, KEY_MAX_FALL_HEIGHT, DEFAULT_MAX_FALL_HEIGHT, 0, 64);
        double arriveRadius = readDouble(props, KEY_ARRIVE_RADIUS, DEFAULT_ARRIVE_RADIUS, 0.1, 2.0);
        String waterPriority = readEnum(props, KEY_WATER_PRIORITY, DEFAULT_WATER_PRIORITY, "swim", "shore");
        boolean autoJump = readBool(props, KEY_AUTO_JUMP, DEFAULT_AUTO_JUMP);
        boolean fallDamage = readBool(props, KEY_FALL_DAMAGE, DEFAULT_FALL_DAMAGE);
        boolean knockback = readBool(props, KEY_KNOCKBACK, DEFAULT_KNOCKBACK);
        boolean flyAutoEquip = readBool(props, KEY_FLY_AUTO_EQUIP, DEFAULT_FLY_AUTO_EQUIP);
        boolean flyAutoBoost = readBool(props, KEY_FLY_AUTO_BOOST, DEFAULT_FLY_AUTO_BOOST);
        boolean flyAutoRescue = readBool(props, KEY_FLY_AUTO_RESCUE, DEFAULT_FLY_AUTO_RESCUE);
        boolean flyForceFlight = readBool(props, KEY_FLY_FORCE_FLIGHT, DEFAULT_FLY_FORCE_FLIGHT);
        boolean flyAutoReplace = readBool(props, KEY_FLY_AUTO_REPLACE, DEFAULT_FLY_AUTO_REPLACE);
        double flyBoostMinSpeed = readDouble(props, KEY_FLY_BOOST_MIN_SPEED, DEFAULT_FLY_BOOST_MIN_SPEED, 0.05, 1.0);
        float flyGlidePitch = (float) readDouble(props, KEY_FLY_GLIDE_PITCH, DEFAULT_FLY_GLIDE_PITCH, -89.0, 30.0);
        int flyRocketDuration = readInt(props, KEY_FLY_ROCKET_DURATION, DEFAULT_FLY_ROCKET_DURATION, 1, 3);

        RunningConfig config = new RunningConfig(
                file, allowSprint, allowBreak, pathMode, stuckDigRecovery, allowPlace,
                tunnelMaxTicks, bridgeMaxTicks, maxPlaceTries, allowParkour,
                allowGlide, glideMaxDistance, glideRocketBudget,
                maxFallHeight, arriveRadius, waterPriority,
                autoJump, fallDamage, knockback,
                flyAutoEquip, flyAutoBoost, flyAutoRescue, flyForceFlight, flyAutoReplace,
                flyBoostMinSpeed, flyGlidePitch, flyRocketDuration);
        log.info("[Running] 功能配置：sprint={} break={} mode={} stuckDig={} place={}(tunnelMax={} bridgeMax={} placeTries={}) parkour={} glide={}(maxDist={} rocketBudget={}) maxFall={} arriveRadius={} waterPriority={} autoJump={} fallDamage={} knockback={} fly(autoEquip={} autoBoost={} autoRescue={} boostMinSpeed={} glidePitch={} rocketDuration={})（来源 {}）",
                allowSprint, allowBreak, pathMode, stuckDigRecovery, allowPlace, tunnelMaxTicks, bridgeMaxTicks, maxPlaceTries,
                allowParkour, allowGlide, glideMaxDistance, glideRocketBudget,
                maxFallHeight, arriveRadius, waterPriority, autoJump, fallDamage, knockback,
                flyAutoEquip, flyAutoBoost, flyAutoRescue, flyBoostMinSpeed, flyGlidePitch, flyRocketDuration, file);
        return config;
    }

    /** 配置文件绝对路径（供日志 / 提示用户编辑位置）。 */
    public Path configFile() {
        return configFile;
    }

    /** 是否允许寻路在平坦同层路径上疾跑。 */
    public boolean allowSprint() {
        return allowSprint;
    }

    /** 是否允许破块开路（BREAK 移动）。 */
    public boolean allowBreak() {
        return allowBreak;
    }

    /** 寻路模式：{@code "move"}=移动优先 / {@code "dig"}=挖掘优先。 */
    public String pathMode() {
        return pathMode;
    }

    /** 是否为挖掘优先模式（dig）：允许直接挖开方块前进，挖块代价打折且隐含允许破块。 */
    public boolean digPriority() {
        return "dig".equalsIgnoreCase(pathMode);
    }

    /** 卡住时是否自动从背包取工具挖通开路。 */
    public boolean stuckDigRecovery() {
        return stuckDigRecovery;
    }

    /** 无路可踩的缺口处是否允许放置方块搭桥。 */
    public boolean allowPlace() {
        return allowPlace;
    }

    /** 直挖隧道总 tick 上限（本轮允许持续的直挖/搭桥总时长）。 */
    public int tunnelMaxTicks() {
        return tunnelMaxTicks;
    }

    /** 单个缺口搭桥总 tick 预算。 */
    public int bridgeMaxTicks() {
        return bridgeMaxTicks;
    }

    /** 同一缺口最多发送放置的次数上限。 */
    public int maxPlaceTries() {
        return maxPlaceTries;
    }

    /** 是否允许 Parkour（跳跨空隙）。 */
    public boolean allowParkour() {
        return allowParkour;
    }

    /** 是否允许寻路使用鞘翅滑翔（GLIDE 移动；还需胸槽已装鞘翅）。 */
    public boolean allowGlide() {
        return allowGlide;
    }

    /** 单次滑翔边最大水平跨度（格）。 */
    public int glideMaxDistance() {
        return glideMaxDistance;
    }

    /** 单次滑翔边预估火箭消耗上限。 */
    public int glideRocketBudget() {
        return glideRocketBudget;
    }

    /** 允许的最大无伤坠落高度（格）。 */
    public int maxFallHeight() {
        return maxFallHeight;
    }

    /** 到达航点判定半径（格）。 */
    public double arriveRadius() {
        return arriveRadius;
    }

    /** 水中行为优先级：{@code "swim"}=游泳优先 / {@code "shore"}=上岸优先。 */
    public String waterPriority() {
        return waterPriority;
    }

    /** 是否游泳优先（swim）：水中朝目标方向游过去，而非先找岸边。 */
    public boolean waterSwimPriority() {
        return "swim".equalsIgnoreCase(waterPriority);
    }

    /** 是否开启自动跳跃。 */
    public boolean autoJump() {
        return autoJump;
    }

    /** 是否模拟摔落伤害（本地结算，供寻路高度决策与日志）。 */
    public boolean fallDamage() {
        return fallDamage;
    }

    /** 是否应用服务器下发的击退/爆炸冲量。 */
    public boolean knockback() {
        return knockback;
    }

    /** fly start 时是否自动装备鞘翅到胸槽。 */
    public boolean flyAutoEquip() {
        return flyAutoEquip;
    }

    /** 滑翔中是否自动维持速度（速度低于阈值自动补烟花）。 */
    public boolean flyAutoBoost() {
        return flyAutoBoost;
    }

    /** 危险下坠是否自动起滑救援（需已装鞘翅）。 */
    public boolean flyAutoRescue() {
        return flyAutoRescue;
    }

    /** 是否强制飞行（旁路耐久预算门控；仅对生存/冒险有意义）。 */
    public boolean flyForceFlight() {
        return flyForceFlight;
    }

    /** 耐久将尽时是否自动更换背包里的新鞘翅。 */
    public boolean flyAutoReplace() {
        return flyAutoReplace;
    }

    /** 自动补弹的水平速度阈值（格/tick）。 */
    public double flyBoostMinSpeed() {
        return flyBoostMinSpeed;
    }

    /** 滑翔巡航俯仰角（度；负值抬头）。 */
    public float flyGlidePitch() {
        return flyGlidePitch;
    }

    /** 无法解析组件时的火箭飞行时长（1~3）。 */
    public int flyRocketDuration() {
        return flyRocketDuration;
    }

    // ------------------------------------------------------------------
    // 单项解析：缺失 / 非法一律回退默认并告警
    // ------------------------------------------------------------------

    private static String trimmed(Properties props, String key) {
        String v = props.getProperty(key);
        if (v == null) {
            return null;
        }
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    private static boolean readBool(Properties props, String key, boolean def) {
        String v = trimmed(props, key);
        if (v == null) {
            return def;
        }
        if (v.equalsIgnoreCase("true")) {
            return true;
        }
        if (v.equalsIgnoreCase("false")) {
            return false;
        }
        log.warn("[Running] 配置 {}=\"{}\" 非法（应为 true/false），回退默认 {}。", key, v, def);
        return def;
    }

    /** 枚举型配置：大小写不敏感匹配 {@code allowed} 中的一项；非法则告警回退默认。 */
    private static String readEnum(Properties props, String key, String def, String... allowed) {
        String v = trimmed(props, key);
        if (v == null) {
            return def;
        }
        for (String option : allowed) {
            if (v.equalsIgnoreCase(option)) {
                return option;
            }
        }
        log.warn("[Running] 配置 {}=\"{}\" 非法（应为 {} 之一），回退默认 {}。",
                key, v, String.join("/", allowed), def);
        return def;
    }

    private static int readInt(Properties props, String key, int def, int min, int max) {
        String v = trimmed(props, key);
        if (v == null) {
            return def;
        }
        try {
            int n = Integer.parseInt(v);
            if (n < min || n > max) {
                log.warn("[Running] 配置 {}={} 超出范围 [{}, {}]，回退默认 {}。", key, n, min, max, def);
                return def;
            }
            return n;
        } catch (NumberFormatException e) {
            log.warn("[Running] 配置 {}=\"{}\" 不是整数，回退默认 {}。", key, v, def);
            return def;
        }
    }

    private static double readDouble(Properties props, String key, double def, double min, double max) {
        String v = trimmed(props, key);
        if (v == null) {
            return def;
        }
        try {
            double n = Double.parseDouble(v);
            if (Double.isNaN(n) || n < min || n > max) {
                log.warn("[Running] 配置 {}={} 超出范围 [{}, {}]，回退默认 {}。", key, n, min, max, def);
                return def;
            }
            return n;
        } catch (NumberFormatException e) {
            log.warn("[Running] 配置 {}=\"{}\" 不是数字，回退默认 {}。", key, v, def);
            return def;
        }
    }
}
