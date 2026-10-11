package top.mcocet.running;

import org.geysermc.mcprotocollib.network.event.session.SessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.bot.BotState;
import top.mcocet.running.bot.MovementEngine;
import top.mcocet.running.bot.PlayerPhysics;
import top.mcocet.running.listeners.ConnectionListener;
import top.mcocet.running.listeners.PositionListener;
import top.mcocet.running.listeners.RespawnListener;
import top.mcocet.running.listeners.WorldListener;
import top.mcocet.running.world.BlockNameTable;
import top.mcocet.running.world.RunningWorld;
import xin.bbtt.mcbot.Bot;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Running 插件的运行时核心：集中管理插件生命周期与组件装配。
 *
 * <p>生命周期由 {@link Main} 在 xinbot 回调中原样转发：
 * <ol>
 *   <li>{@link #load()} —— 装配：加载方块名称表（可选 blocks.json），
 *       创建 {@link PlayerPhysics} 与 {@link MovementEngine}；</li>
 *   <li>{@link #enable()} —— 激活：注册会话监听器（连接与确认应答 / 位置 / 世界 / 自动重生），
 *       启动 20 TPS 移动引擎；</li>
 *   <li>{@link #disable()} —— 停用：停止移动引擎并清空世界缓存
 *       （会话监听器由 xinbot 在 disable 时统一移除）；</li>
 *   <li>{@link #unload()} —— 卸载：确保引擎停止（防御性）。</li>
 * </ol></p>
 *
 * <p>组件装配关系：{@link MovementEngine} 驱动 {@link PlayerPhysics}，
 * 后者依赖 {@link BotState}、{@link RunningWorld} 与 {@link BlockNameTable}；
 * 命令与监听器均通过访问器获取这些单例。所有生命周期方法可安全重复调用。</p>
 */
public final class RunningCore {
    private static final Logger log = LoggerFactory.getLogger(RunningCore.class.getSimpleName());

    /** 可选的方块名称表文件（工作目录下，PrismarineJS minecraft-data blocks.json 格式） */
    private static final String BLOCK_TABLE_FILE = "blocks.json";

    private final Main plugin;

    private final BotState botState = new BotState();
    private final RunningWorld world = new RunningWorld();
    private final List<SessionListener> listeners = new ArrayList<>(4);

    private volatile BlockNameTable blockNames = BlockNameTable.empty();
    private volatile PlayerPhysics physics;
    private volatile MovementEngine movementEngine;

    private boolean loaded;
    private boolean enabled;

    public RunningCore(Main plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 装配阶段：加载方块名称表，创建物理与移动引擎（在会话建立之前调用） */
    public synchronized void load() {
        if (loaded) return;
        blockNames = BlockNameTable.load(new File(BLOCK_TABLE_FILE));
        if (!blockNames.isEmpty()) {
            log.info("[Running] 已加载方块名称表，共覆盖 {} 个方块状态。", blockNames.size());
        }
        physics = new PlayerPhysics(botState, world, blockNames);
        movementEngine = new MovementEngine(botState, physics);
        loaded = true;
    }

    /** 激活阶段：注册会话监听器并启动移动引擎 */
    public synchronized void enable() {
        if (enabled) return;
        if (!loaded) load();

        listeners.clear();
        listeners.add(new ConnectionListener(botState, world));
        listeners.add(new PositionListener(botState, movementEngine));
        listeners.add(new WorldListener(world));
        listeners.add(new RespawnListener(botState));
        for (SessionListener listener : listeners) {
            Bot.INSTANCE.addPacketListener(listener, plugin);
        }

        movementEngine.start();
        enabled = true;
    }

    /** 停用阶段：停止移动引擎并清空世界缓存 */
    public synchronized void disable() {
        if (!enabled) return;
        movementEngine.stop();
        // 会话层监听器由 xinbot 在 disable 时统一从会话移除，这里只释放引用
        listeners.clear();
        world.clear();
        enabled = false;
    }

    /** 卸载阶段：确保引擎已停止（disable 通常已先被 xinbot 调用） */
    public synchronized void unload() {
        if (enabled) disable();
        loaded = false;
    }

    // ------------------------------------------------------------------
    // 组件访问器
    // ------------------------------------------------------------------

    public BotState botState() {
        return botState;
    }

    public RunningWorld world() {
        return world;
    }

    public BlockNameTable blockNames() {
        return blockNames;
    }

    public PlayerPhysics physics() {
        return physics;
    }

    public MovementEngine movementEngine() {
        return movementEngine;
    }
}
