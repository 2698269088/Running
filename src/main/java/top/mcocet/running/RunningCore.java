package top.mcocet.running;

import org.geysermc.mcprotocollib.network.event.session.SessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.bot.BotState;
import top.mcocet.running.bot.MovementEngine;
import top.mcocet.running.bot.PlayerPhysics;
import top.mcocet.running.listeners.ConnectionListener;
import top.mcocet.running.listeners.EntityListener;
import top.mcocet.running.listeners.InventoryListener;
import top.mcocet.running.listeners.KnockbackListener;
import top.mcocet.running.listeners.PositionListener;
import top.mcocet.running.listeners.RegistryListener;
import top.mcocet.running.listeners.RespawnListener;
import top.mcocet.running.listeners.WorldListener;
import top.mcocet.running.world.BlockNameTable;
import top.mcocet.running.world.EntityTracker;
import top.mcocet.running.world.InventoryState;
import top.mcocet.running.world.RunningWorld;
import top.mcocet.running.world.item.ItemRegistry;
import xin.bbtt.mcbot.Bot;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Running 插件的运行时核心：集中管理插件生命周期与组件装配。
 *
 * <p>生命周期由 {@link Main} 在 xinbot 回调中原样转发：
 * <ol>
 *   <li>{@link #load()} —— 装配：加载方块名称表（可选 blocks.json），
 *       创建 {@link PlayerPhysics} 与 {@link MovementEngine}；</li>
 *   <li>{@link #enable()} —— 激活：注册会话监听器（连接与确认应答 / 实体跟踪 /
 *       位置 / 击退 / 世界 / 自动重生），启动 20 TPS 移动引擎；</li>
 *   <li>{@link #disable()} —— 停用：停止移动引擎并清空世界与实体缓存
 *       （会话监听器由 xinbot 在 disable 时统一移除）；</li>
 *   <li>{@link #unload()} —— 卸载：确保引擎停止（防御性）。</li>
 * </ol></p>
 *
 * <p>组件装配关系：{@link MovementEngine} 驱动 {@link PlayerPhysics}，
 * 后者依赖 {@link BotState}、{@link RunningWorld}、{@link BlockNameTable}
 * 与 {@link EntityTracker}；命令与监听器均通过访问器获取这些单例。
 * 所有生命周期方法可安全重复调用。</p>
 */
public final class RunningCore {
    private static final Logger log = LoggerFactory.getLogger(RunningCore.class.getSimpleName());

    /** 可选的外部方块名称表文件（工作目录下，用于覆盖内置数据） */
    private static final String BLOCK_TABLE_FILE = "blocks.json";

    private final Main plugin;

    private final BotState botState = new BotState();
    private final RunningWorld world = new RunningWorld();
    private final EntityTracker entityTracker = new EntityTracker();
    /** 机器人完整背包与手持槽镜像（网络线程写、引擎/计算线程读） */
    private final InventoryState inventory = new InventoryState();
    /** 从注册表解析的物品工具模型（配置阶段一次性写入，之后只读） */
    private final ItemRegistry itemRegistry = new ItemRegistry();
    private final List<SessionListener> listeners = new ArrayList<>(8);

    private volatile BlockNameTable blockNames = BlockNameTable.empty();
    private volatile PlayerPhysics physics;
    private volatile MovementEngine movementEngine;
    /** 寻路计算专用单线程执行器（所有 A* 计算串行于此，不占用 20TPS 引擎线程） */
    private volatile ExecutorService pathExecutor;

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
        blockNames = BlockNameTable.loadDefault(new File(BLOCK_TABLE_FILE));
        if (!blockNames.isEmpty()) {
            log.info("[Running] 已加载方块名称表，共覆盖 {} 个方块状态。", blockNames.size());
        }
        physics = new PlayerPhysics(botState, world, blockNames, entityTracker);
        movementEngine = new MovementEngine(botState, physics);
        pathExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Running-Path-Compute");
            thread.setDaemon(true);
            return thread;
        });
        loaded = true;
    }

    /** 激活阶段：注册会话监听器并启动移动引擎 */
    public synchronized void enable() {
        if (enabled) return;
        if (!loaded) load();

        listeners.clear();
        listeners.add(new ConnectionListener(botState, world));
        listeners.add(new EntityListener(botState, entityTracker));
        listeners.add(new PositionListener(botState, movementEngine));
        listeners.add(new KnockbackListener(botState, movementEngine));
        listeners.add(new WorldListener(world));
        listeners.add(new RespawnListener(botState));
        listeners.add(new InventoryListener(inventory));
        listeners.add(new RegistryListener(itemRegistry));
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
        ExecutorService executor = pathExecutor;
        if (executor != null) {
            executor.shutdownNow();
        }
        pathExecutor = null;
        // 会话层监听器由 xinbot 在 disable 时统一从会话移除，这里只释放引用
        listeners.clear();
        world.clear();
        entityTracker.clear();
        inventory.clear();
        enabled = false;
    }

    /** 卸载阶段：确保引擎已停止（disable 通常已先被 xinbot 调用） */
    public synchronized void unload() {
        if (enabled) disable();
        ExecutorService executor = pathExecutor;
        if (executor != null) {
            executor.shutdownNow();
            pathExecutor = null;
        }
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

    public EntityTracker entityTracker() {
        return entityTracker;
    }

    /** 机器人完整背包与手持槽镜像 */
    public InventoryState inventory() {
        return inventory;
    }

    /** 物品注册表（挖掘工具模型来源） */
    public ItemRegistry itemRegistry() {
        return itemRegistry;
    }

    /** 寻路计算专用单线程执行器（供 {@code goto} 与 {@code PathingGoal} 提交 A* 计算） */
    public ExecutorService pathExecutor() {
        return pathExecutor;
    }
}
