package top.mcocet.running;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.bot.BotState;
import top.mcocet.running.bot.MovementEngine;
import top.mcocet.running.bot.PlayerPhysics;
import top.mcocet.running.world.BlockNameTable;
import top.mcocet.running.world.EntityTracker;
import top.mcocet.running.world.InventoryState;
import top.mcocet.running.world.PlayerList;
import top.mcocet.running.world.RunningWorld;
import top.mcocet.running.world.item.ItemRegistry;
import xin.bbtt.mcbot.plugin.Plugin;

import java.util.concurrent.ExecutorService;

/**
 * Running —— xinbot 移动引擎插件（入口主类，对应 plugin.yml 中的 main）。
 *
 * <p>职责限于机器人移动控制（物理模拟、位置同步、世界缓存）；服务器连接的
 * 建立与地址提供不在本插件职责范围内。</p>
 *
 * <p>插件生命周期与运行时组件（机器人状态、世界缓存、物理模拟、移动引擎、
 * 会话监听器）全部由 {@link RunningCore} 集中管理，本类只做薄转发，
 * 并保留命令系统依赖的实例访问器。</p>
 *
 * <p>当前阶段实现了移动引擎的基础设施：
 * <ul>
 *   <li>对齐官方客户端的玩家物理模拟（{@link PlayerPhysics}）；</li>
 *   <li>官方位置包发送策略与 20 TPS 移动引擎（{@link MovementEngine}，
 *       移动目标 MovementGoal 可拓展）；</li>
 *   <li>机器人位置 / 朝向状态跟踪（含服务器传送应答）；</li>
 *   <li>死亡后自动请求重生（对齐官方死亡屏的"重生"操作）；</li>
 *   <li>区块数据缓存与脚下方块查询；</li>
 *   <li>控制台命令：pos（坐标查看）、block（方块识别）、move（移动控制）。</li>
 * </ul></p>
 */
public final class Main implements Plugin {
    private static final Logger log = LoggerFactory.getLogger(Main.class.getSimpleName());

    /** 插件实例（供命令执行器访问） */
    public static volatile Main INSTANCE;

    /** 运行时核心：生命周期与组件装配统一入口 */
    private final RunningCore core = new RunningCore(this);

    @Override
    public void onLoad() {
        INSTANCE = this;
        core.load();
        log.info("[Running] 移动引擎插件已载入。");
    }

    @Override
    public void onEnable() {
        core.enable();
        log.info("[Running] 移动引擎已启动，输入 pos / look / block 查看机器人状态与视角。");
    }

    @Override
    public void onDisable() {
        core.disable();
    }

    @Override
    public void onUnload() {
        core.unload();
        INSTANCE = null;
    }

    public BotState botState() {
        return core.botState();
    }

    public RunningWorld world() {
        return core.world();
    }

    public MovementEngine movementEngine() {
        return core.movementEngine();
    }

    public BlockNameTable blockNames() {
        return core.blockNames();
    }

    /** 功能配置（{@code Running/config.properties}） */
    public RunningConfig config() {
        return core.config();
    }

    public PlayerPhysics physics() {
        return core.physics();
    }

    public ExecutorService pathExecutor() {
        return core.pathExecutor();
    }

    /** 机器人完整背包与手持槽镜像 */
    public InventoryState inventory() {
        return core.inventory();
    }

    /** 附近实体跟踪表（供按玩家名解析位置） */
    public EntityTracker entityTracker() {
        return core.entityTracker();
    }

    /** 玩家列表镜像（UUID ↔ 用户名） */
    public PlayerList playerList() {
        return core.playerList();
    }

    /** 物品注册表（挖掘工具模型来源） */
    public ItemRegistry itemRegistry() {
        return core.itemRegistry();
    }
}
