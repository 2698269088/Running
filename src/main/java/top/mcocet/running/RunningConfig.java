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
    private static final String KEY_MAX_FALL_HEIGHT = "pathfinding.max-fall-height";
    private static final String KEY_ARRIVE_RADIUS = "pathfinding.arrive-radius";
    private static final String KEY_AUTO_JUMP = "physics.auto-jump";
    private static final String KEY_FALL_DAMAGE = "physics.fall-damage";
    private static final String KEY_KNOCKBACK = "physics.knockback";

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
    public static final int DEFAULT_MAX_FALL_HEIGHT = 3;
    public static final double DEFAULT_ARRIVE_RADIUS = 0.5;
    public static final boolean DEFAULT_AUTO_JUMP = true;
    /** 摔落伤害模拟默认开启（仅本地物理结算，用于寻路高度决策与日志；真实伤害由服务器裁定） */
    public static final boolean DEFAULT_FALL_DAMAGE = true;
    /** 击退/爆炸冲量回写默认开启（关闭后忽略服务器的速度修正包） */
    public static final boolean DEFAULT_KNOCKBACK = true;

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
            # 允许的最大无伤坠落高度（格），更大的落差不走（0~64）。默认 3
            pathfinding.max-fall-height=3
            # 到达航点的判定半径（格），越小越精确但越易反复对齐（0.1~2.0）。默认 0.5
            pathfinding.arrive-radius=0.5

            # ---- 物理移动 ----
            # 是否开启自动跳跃（撞到一格边缘自动起跳）。默认 true
            physics.auto-jump=true
            # 是否模拟摔落伤害（着地时按原版公式结算并记录日志，供寻路高度决策参考；真实伤害由服务器裁定）。默认 true
            physics.fall-damage=true
            # 是否应用服务器下发的击退/爆炸冲量（近战/弓箭/爆炸的速度修正）。默认 true
            physics.knockback=true
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
    private final int maxFallHeight;
    private final double arriveRadius;
    private final boolean autoJump;
    private final boolean fallDamage;
    private final boolean knockback;

    private RunningConfig(Path configFile, boolean allowSprint, boolean allowBreak, String pathMode,
                          boolean stuckDigRecovery,
                          boolean allowPlace, int tunnelMaxTicks, int bridgeMaxTicks, int maxPlaceTries,
                          boolean allowParkour, int maxFallHeight, double arriveRadius, boolean autoJump,
                          boolean fallDamage, boolean knockback) {
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
        this.maxFallHeight = maxFallHeight;
        this.arriveRadius = arriveRadius;
        this.autoJump = autoJump;
        this.fallDamage = fallDamage;
        this.knockback = knockback;
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
        int maxFallHeight = readInt(props, KEY_MAX_FALL_HEIGHT, DEFAULT_MAX_FALL_HEIGHT, 0, 64);
        double arriveRadius = readDouble(props, KEY_ARRIVE_RADIUS, DEFAULT_ARRIVE_RADIUS, 0.1, 2.0);
        boolean autoJump = readBool(props, KEY_AUTO_JUMP, DEFAULT_AUTO_JUMP);
        boolean fallDamage = readBool(props, KEY_FALL_DAMAGE, DEFAULT_FALL_DAMAGE);
        boolean knockback = readBool(props, KEY_KNOCKBACK, DEFAULT_KNOCKBACK);

        RunningConfig config = new RunningConfig(
                file, allowSprint, allowBreak, pathMode, stuckDigRecovery, allowPlace,
                tunnelMaxTicks, bridgeMaxTicks, maxPlaceTries, allowParkour, maxFallHeight, arriveRadius, autoJump,
                fallDamage, knockback);
        log.info("[Running] 功能配置：sprint={} break={} mode={} stuckDig={} place={}(tunnelMax={} bridgeMax={} placeTries={}) parkour={} maxFall={} arriveRadius={} autoJump={} fallDamage={} knockback={}（来源 {}）",
                allowSprint, allowBreak, pathMode, stuckDigRecovery, allowPlace, tunnelMaxTicks, bridgeMaxTicks, maxPlaceTries, allowParkour, maxFallHeight, arriveRadius, autoJump, fallDamage, knockback, file);
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

    /** 允许的最大无伤坠落高度（格）。 */
    public int maxFallHeight() {
        return maxFallHeight;
    }

    /** 到达航点判定半径（格）。 */
    public double arriveRadius() {
        return arriveRadius;
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
