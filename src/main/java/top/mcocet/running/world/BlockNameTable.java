package top.mcocet.running.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.math.AABB;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 方块状态 ID → 方块名称 / 碰撞属性 / 摩擦系数的查询表。
 *
 * <p>数据来源为 blocks.json（与服务器版本对应的 PrismarineJS
 * minecraft-data 格式）：优先读取工作目录下的同名文件（便于按服务器
 * 版本覆盖），缺失时回退到打包在插件 jar 内的内置资源
 * （{@code top/mcocet/running/blocks.json} 或类根目录
 * {@code blocks.json}）。两者都不可用时，命令将只显示方块状态 ID，
 * 碰撞查询退化为“非空气即完整方块”的默认规则。</p>
 *
 * <p>顶层兼容两种结构：minecraft-data 原格式的数组，或
 * <code>{"blocks": [...]}</code> 封装。</p>
 *
 * <p><b>数据版本必须与机器人协商到的协议版本一致</b>：本表与 {@code collision_shapes.bin}
 * 都是「按协议状态 ID 索引」的数组，而状态 ID 的排布随版本变化（例如火把：
 * 1.21.11 为 3169，26.1.2 为 3370）。错配时排在变化点之前的方块仍然正确，之后的
 * 方块则整体平移，表现为「只有部分方块减速 / 被跳过 / 卡住」——用 26.1.2 表去解
 * 1.21.11 的火把（3169）会得到该版本独有的 {@code oak_shelf}（实体积方块），
 * 火把因而变成一道隐形的墙。</p>
 *
 * <p>注意对齐目标是<b>机器人实际协商到的协议版本</b>，服务端装了 ViaVersion /
 * ViaBackwards 时它并不等于服务端版本（旧协议客户端会被翻译到旧版本的方块编号上）。
 * 启动日志会打印数据状态总数对应的版本名与协商协议，两者对不上就是错配。</p>
 *
 * <p>条目支持两种结构：
 * <pre>
 * {"name": "stone", "minStateId": 1, "maxStateId": 1}
 * {"name": "stone", "minStateId": 1, "states": [ ... ]}
 * </pre></p>
 *
 * <p>碰撞几何的取值优先级（自上而下）：
 * <ol>
 *   <li><b>逐状态权威表 {@code collision_shapes.bin}</b>（{@link CollisionShapes}）：
 *       由原版 {@code BlockStateBase.getCollisionShape()} 在
 *       {@code CollisionContext.empty()} 下按协议状态 ID 逐状态导出，命中即直接采用，
 *       不再做任何形状推断；</li>
 *   <li>下表所述的名称/状态启发式，仅在权威表缺失或状态数与 blocks.json 不匹配时生效。</li>
 * </ol></p>
 *
 * <p>启发式近似（官方为精确 VoxelShape）：
 * <ul>
 *   <li>blocks.json 的 {@code boundingBox} 字段（"block"/"empty"）优先；</li>
 *   <li>名称启发式：明确的非固体方块（空气/流体/植物/火把/铁轨等）
 *       视为无碰撞；blocks.json 的 {@code boundingBox=empty}（原版
 *       {@code .noCollision()} 类方块，如压力板/按钮）同样判为无碰撞；</li>
 *   <li>楼梯、半砖以及压力板/地毯/雪层/活板门/农田/路径/灵魂沙/箱子/床/
 *       漏斗/炼药锅/附魔台/阳光探测器/堆肥桶/切石机/酿造台/蛋糕/花盆/
 *       末地传送门框架/龙蛋/营火/按钮/竹子/铁砧/栅栏/墙/大垂滴叶在
 *       blocks.json 提供状态属性时按官方 VoxelShape 精确建模（含
 *       facing/open/half/layers/north-east-south-west 等变体位），
 *       否则退化为近似；</li>
 *   <li>其余非空气方块按完整立方体近似，但门/栅栏门/玻璃板与铁栏杆/
 *       中继器与比较器/潮涌核心/海铃菜/讲台/饰纹陶罐/幽匿感测体按官方
 *       VoxelShape 精确建模。</li>
 * </ul></p>
 */
public final class BlockNameTable {
    private static final Logger log = LoggerFactory.getLogger(BlockNameTable.class.getSimpleName());

    /** 方块默认摩擦系数（Blocks.DEFAULT_ friction = 0.6F） */
    public static final double DEFAULT_FRICTION = 0.6;
    /** 碰撞高度：完整立方体 */
    public static final double HEIGHT_FULL = 1.0;
    /** 碰撞高度：半砖近似 */
    public static final double HEIGHT_SLAB = 0.5;
    /** 碰撞高度：栅栏/墙/栅栏门近似（官方形状高 1.5） */
    public static final double HEIGHT_FENCE = 1.5;

    /** 楼梯状态数据缺失标记 */
    private static final byte STAIR_NONE = (byte) 0xFF;
    /** 半砖状态数据缺失标记 */
    private static final byte SLAB_NONE = (byte) 0xFF;

    /** 内置数据资源候选路径（打包进 jar；优先命名空间路径，兼容类根目录） */
    private static final String[] EMBEDDED_RESOURCES = {
            "/top/mcocet/running/blocks.json", "/blocks.json"};

    /** 无流体 */
    public static final int FLUID_NONE = 0;
    /** 水流体（含 waterlogged 方块与含水植物） */
    public static final int FLUID_WATER = 1;
    /** 岩浆流体 */
    public static final int FLUID_LAVA = 2;

    /** 采集工具族：无特定工具（徒手即可，或 blocks.json 未标注 material） */
    public static final byte TOOL_NONE = 0;
    /** mineable/pickaxe */
    public static final byte TOOL_PICKAXE = 1;
    /** mineable/axe */
    public static final byte TOOL_AXE = 2;
    /** mineable/shovel */
    public static final byte TOOL_SHOVEL = 3;
    /** mineable/hoe */
    public static final byte TOOL_HOE = 4;

    /** 面编码：-Z 面（与楼梯 facing 编码一致） */
    public static final int FACE_NORTH = 0;
    /** 面编码：+Z 面 */
    public static final int FACE_SOUTH = 1;
    /** 面编码：-X 面 */
    public static final int FACE_WEST = 2;
    /** 面编码：+X 面 */
    public static final int FACE_EAST = 3;
    /** 面编码：-Y 面 */
    public static final int FACE_DOWN = 4;
    /** 面编码：+Y 面 */
    public static final int FACE_UP = 5;

    /** FACE_* → 轴编号（0=X, 1=Y, 2=Z） */
    private static final int[] FACE_AXIS = {2, 2, 0, 0, 1, 1};
    /** FACE_* → 是否为坐标正方向的面 */
    private static final boolean[] FACE_POSITIVE = {false, true, false, true, false, true};

    /** 楼梯形状编码（stairData 的 bit3-5，对齐 StairsShape 枚举顺序） */
    private static final int STAIR_SHAPE_STRAIGHT = 0;
    private static final int STAIR_SHAPE_INNER_LEFT = 1;
    private static final int STAIR_SHAPE_INNER_RIGHT = 2;
    private static final int STAIR_SHAPE_OUTER_LEFT = 3;
    private static final int STAIR_SHAPE_OUTER_RIGHT = 4;

    /** 半砖类型编码（slabData 值） */
    private static final byte SLAB_BOTTOM = 0;
    private static final byte SLAB_TOP = 1;
    private static final byte SLAB_DOUBLE = 2;

    /** 精确形状族编码（shapeKindByStateId 值）；非特殊形状方块为 {@link #SHAPE_NONE} */
    private static final byte SHAPE_NONE = 0;
    /** *_pressure_plate：data bit0=powered */
    private static final byte SHAPE_PLATE = 1;
    /** *_carpet：data bit0=无碰撞（pale_moss_carpet bottom=false 时为 1，其余恒 0） */
    private static final byte SHAPE_CARPET = 2;
    /** snow：data bit0-3=layers(1..8) */
    private static final byte SHAPE_SNOW = 3;
    /** *_trapdoor：data bit0=open, bit1=top, bit2-3=facing(north/south/west/east) */
    private static final byte SHAPE_TRAPDOOR = 4;
    /** farmland/dirt_path */
    private static final byte SHAPE_LOW = 5;
    /** soul_sand */
    private static final byte SHAPE_SOUL_SAND = 6;
    /** chest/*_chest */
    private static final byte SHAPE_CHEST = 7;
    /** *_bed：data bit0-1=腿所在端朝向（head?facing:opposite(facing)） */
    private static final byte SHAPE_BED = 8;
    /** hopper：data bit0-2=facing(0=down,1=north,2=south,3=west,4=east) */
    private static final byte SHAPE_HOPPER = 9;
    /** *cauldron */
    private static final byte SHAPE_CAULDRON = 10;
    /** enchanting_table */
    private static final byte SHAPE_ENCHANTING = 11;
    /** daylight_detector */
    private static final byte SHAPE_DAYLIGHT = 12;
    /** composter：data bit0-3=level(0..8) */
    private static final byte SHAPE_COMPOSTER = 13;
    /** stonecutter */
    private static final byte SHAPE_STONECUTTER = 14;
    /** brewing_stand */
    private static final byte SHAPE_BREWING = 15;
    /** cake/candle_cake：data bit0-2=bites, bit3=插蜡烛 */
    private static final byte SHAPE_CAKE = 16;
    /** flower_pot/potted_* */
    private static final byte SHAPE_FLOWER_POT = 17;
    /** end_portal_frame：data bit0=eye */
    private static final byte SHAPE_END_FRAME = 18;
    /** dragon_egg */
    private static final byte SHAPE_DRAGON_EGG = 19;
    /** campfire/soul_campfire */
    private static final byte SHAPE_CAMPFIRE = 20;
    /** *_button：data bit0-1=facing, bit2-3=face(0=floor,1=wall,2=ceiling) */
    private static final byte SHAPE_BUTTON = 21;
    /** bamboo */
    private static final byte SHAPE_BAMBOO = 22;
    /** anvil/chipped_anvil/damaged_anvil：data bit0=朝 X 轴 */
    private static final byte SHAPE_ANVIL = 23;
    /** chain：data bit0-1=轴(0=x, 1=y, 2=z) */
    private static final byte SHAPE_CHAIN = 24;
    /** end_rod/lightning_rod：data bit0-1=轴(0=x, 1=y, 2=z) */
    private static final byte SHAPE_ROD = 25;
    /** lantern/soul_lantern：data bit0=hanging */
    private static final byte SHAPE_LANTERN = 26;
    /** scaffolding */
    private static final byte SHAPE_SCAFFOLDING = 27;
    /** turtle_egg：data bit0-2=eggs(1..4) */
    private static final byte SHAPE_TURTLE_EGG = 28;
    /** candle/*_candle：data bit0-2=candles(1..4) */
    private static final byte SHAPE_CANDLE = 29;
    /** *_door：data bit0-1=facing, bit2=open, bit3=hinge(right) */
    private static final byte SHAPE_DOOR = 30;
    /** *_fence_gate：data bit0-1=facing, bit2=open */
    private static final byte SHAPE_FENCE_GATE = 31;
    /** *_pane/_bars：data bit0=north, bit1=east, bit2=south, bit3=west（CrossCollisionBlock 连接） */
    private static final byte SHAPE_PANE = 32;
    /** repeater/comparator（DiodeBlock） */
    private static final byte SHAPE_DIODE = 33;
    /** conduit */
    private static final byte SHAPE_CONDUIT = 34;
    /** sea_pickle：data=1..4 株 */
    private static final byte SHAPE_SEA_PICKLE = 35;
    /** lectern */
    private static final byte SHAPE_LECTERN = 36;
    /** decorated_pot */
    private static final byte SHAPE_DECORATED_POT = 37;
    /** sculk_sensor / calibrated_sculk_sensor */
    private static final byte SHAPE_SCULK_SENSOR = 38;
    /** *_fence：data bit0=north, bit1=east, bit2=south, bit3=west（CrossCollisionBlock 连接） */
    private static final byte SHAPE_FENCE = 39;
    /** *_wall：data bit0-3=north/east/south/west 有墙臂（low/tall 碰撞等价）, bit4=up 中心柱 */
    private static final byte SHAPE_WALL = 40;
    /** big_dripleaf：data bit0-1=tilt（0=none, 1=unstable, 2=partial, 3=full） */
    private static final byte SHAPE_BIG_DRIPLEAF = 41;

    private static final byte BOX_UNKNOWN = 0;
    private static final byte BOX_BLOCK = 1;
    private static final byte BOX_EMPTY = 2;

    /**
     * 明确的非固体方块（无碰撞盒）名称——启发式规则，可能不完整；
     * 仅在 {@link CollisionShapes} 权威表不可用时参与判定。
     */
    private static final Set<String> NON_SOLID_NAMES = Set.of(
            "air", "cave_air", "void_air",
            "water", "lava", "bubble_column",
            "short_grass", "tall_grass", "fern", "large_fern", "dead_bush",
            "seagrass", "tall_seagrass", "sugar_cane", "kelp", "kelp_plant",
            "sweet_berry_bush", "nether_wart", "crimson_roots", "warped_roots",
            "nether_sprouts", "warped_fungus", "crimson_fungus", "brown_mushroom", "red_mushroom",
            "chorus_plant", "cobweb", "ladder", "tripwire", "tripwire_hook",
            "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet",
            "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy",
            "cornflower", "lily_of_the_valley", "wither_rose",
            "sunflower", "lilac", "rose_bush", "peony", "pink_petals",
            "torchflower", "pitcher_plant", "spore_blossom",
            "torch", "soul_torch", "redstone_torch", "redstone_wire", "lever",
            "fire", "soul_fire", "vine", "glow_lichen", "sculk_vein",
            "rail", "powered_rail", "detector_rail", "activator_rail", "lily_pad",
            "wheat", "carrots", "potatoes", "beetroots",
            "melon_stem", "pumpkin_stem", "attached_melon_stem", "attached_pumpkin_stem",
            "torchflower_crop", "pitcher_crop",
            "cave_vines", "cave_vines_plant", "weeping_vines", "weeping_vines_plant",
            "twisting_vines", "twisting_vines_plant", "hanging_roots",
            "bamboo_sapling", "nether_portal", "end_portal", "end_gateway",
            "light", "structure_void",
            // 从 26.1.2 内核取证的 noCollision 方块（getShape 仅为渲染形状）；
            // 其中部分名称为 26.1.x 独有，在其它版本的 blocks.json 下永不匹配，列出无害
            "bush", "short_dry_grass", "tall_dry_grass", "golden_dandelion", "cactus_flower",
            "resin_clump", "wildflowers", "leaf_litter", "pale_hanging_moss", "firefly_bush",
            "open_eyeblossom", "closed_eyeblossom", "powder_snow", "frogspawn",
            "small_dripleaf", "big_dripleaf_stem",
            // blocks.json 中 boundingBox=empty（无碰撞）但名称启发式漏判的方块
            "mangrove_propagule", "moving_piston");

    /** 非固体后缀规则（覆盖前缀变体，如 16 色旗帜、各种树苗、告示牌） */
    private static final String[] NON_SOLID_SUFFIXES = {
            "_sapling", "_sign", "_banner", "_torch", "_coral", "_coral_fan", "_wall_fan"};

    /** 含水方块（官方 getFluidState 恒返回水源）：气泡柱与水下植物 */
    private static final Set<String> WATER_SOURCE_NAMES = Set.of(
            "bubble_column", "seagrass", "tall_seagrass", "kelp", "kelp_plant");

    /** 楼梯基础形状（bottom half、键=NORTH 方位的官方静态形状：SHAPE_OUTER 的底部半砖 + 西北柱） */
    private static final double[][] STAIR_OUTER_BOXES = {
            {0.0, 0.0, 0.0, 1.0, 0.5, 1.0},
            {0.0, 0.5, 0.0, 0.5, 1.0, 0.5}};
    /** 楼梯基础形状：SHAPE_STRAIGHT = OUTER ∪ rotY90(OUTER)（底半砖 + 北半整条） */
    private static final double[][] STAIR_STRAIGHT_BOXES = {
            {0.0, 0.0, 0.0, 1.0, 0.5, 1.0},
            {0.0, 0.5, 0.0, 1.0, 1.0, 0.5}};
    /** 楼梯基础形状：SHAPE_INNER = STRAIGHT ∪ rotY90(STRAIGHT)（底半砖 + 北半条 + 东半条） */
    private static final double[][] STAIR_INNER_BOXES = {
            {0.0, 0.0, 0.0, 1.0, 0.5, 1.0},
            {0.0, 0.5, 0.0, 1.0, 1.0, 0.5},
            {0.5, 0.5, 0.0, 1.0, 1.0, 1.0}};

    private final String[] namesByStateId;
    /** 来自 blocks.json 的 boundingBox 类型（可能为 UNKNOWN） */
    private final byte[] boxTypesByStateId;
    /** 楼梯状态编码（facing | half&lt;&lt;2 | shape&lt;&lt;3）；非楼梯为 {@link #STAIR_NONE} */
    private final byte[] stairDataByStateId;
    /** 半砖类型（0=bottom, 1=top, 2=double）；非半砖为 {@link #SLAB_NONE} */
    private final byte[] slabDataByStateId;
    /** 流体族（{@link #FLUID_NONE}/{@link #FLUID_WATER}/{@link #FLUID_LAVA}） */
    private final byte[] fluidTypeByStateId;
    /** 流体量（1..8，对齐 FluidState.getAmount）；无流体为 0 */
    private final byte[] fluidAmountByStateId;
    /** 流体是否下落（对齐 FALLING 属性，用于 Fluid.getFlow 的下落加速） */
    private final byte[] fluidFallingByStateId;
    /** 精确形状族（{@link #SHAPE_NONE} 表示非特殊形状） */
    private final byte[] shapeKindByStateId;
    /** 精确形状族的状态数据（位域含义见各 SHAPE_* 常量注释） */
    private final byte[] shapeDataByStateId;
    /** 方块硬度（对齐 blocks.json 的 hardness；-1 表示不可破坏如基岩；未覆盖为 NaN） */
    private final float[] hardnessByStateId;
    /** 是否可挖掘（对齐 blocks.json 的 diggable） */
    private final byte[] diggableByStateId;
    /** 采集工具族（TOOL_*，来自 material 的 mineable/x；无对应为 {@link #TOOL_NONE}） */
    private final byte[] toolCategoryByStateId;
    /** 是否需要正确工具才有掉落（blocks.json 的 harvestTools 非空即视为 true） */
    private final byte[] requiresToolByStateId;
    /** 表外状态 ID 告警的一次性标记（避免逐格查询反复刷日志） */
    private final AtomicBoolean outOfRangeWarned = new AtomicBoolean();
    /** “放置后可作为站立面”（有碰撞、非流体）的方块名集合；首次查询时惰性构建 */
    private volatile Set<String> standableBlockNames;

    private BlockNameTable(String[] namesByStateId, byte[] boxTypesByStateId, byte[] stairDataByStateId,
                           byte[] slabDataByStateId, byte[] fluidTypeByStateId, byte[] fluidAmountByStateId,
                           byte[] fluidFallingByStateId, byte[] shapeKindByStateId, byte[] shapeDataByStateId,
                           float[] hardnessByStateId, byte[] diggableByStateId,
                           byte[] toolCategoryByStateId, byte[] requiresToolByStateId) {
        this.namesByStateId = namesByStateId;
        this.boxTypesByStateId = boxTypesByStateId;
        this.stairDataByStateId = stairDataByStateId;
        this.slabDataByStateId = slabDataByStateId;
        this.fluidTypeByStateId = fluidTypeByStateId;
        this.fluidAmountByStateId = fluidAmountByStateId;
        this.fluidFallingByStateId = fluidFallingByStateId;
        this.shapeKindByStateId = shapeKindByStateId;
        this.shapeDataByStateId = shapeDataByStateId;
        this.hardnessByStateId = hardnessByStateId;
        this.diggableByStateId = diggableByStateId;
        this.toolCategoryByStateId = toolCategoryByStateId;
        this.requiresToolByStateId = requiresToolByStateId;
    }

    /** 空表：名称查询返回 null，碰撞查询退化到默认规则 */
    public static BlockNameTable empty() {
        return new BlockNameTable(new String[0], new byte[0], new byte[0],
                new byte[0], new byte[0], new byte[0], new byte[0],
                new byte[0], new byte[0], new float[0], new byte[0],
                new byte[0], new byte[0]);
    }

    /**
     * 默认加载：优先工作目录外部文件（便于按服务器版本覆盖），
     * 缺失时回退到插件内置资源（jar 内 blocks.json）。
     */
    public static BlockNameTable loadDefault(File file) {
        if (file != null && file.isFile()) {
            return load(file);
        }
        return loadEmbedded();
    }

    /** 从插件内置资源加载；资源缺失或解析失败时返回空表 */
    public static BlockNameTable loadEmbedded() {
        for (String path : EMBEDDED_RESOURCES) {
            try (InputStream in = BlockNameTable.class.getResourceAsStream(path)) {
                if (in == null) {
                    continue;
                }
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    return parse(JsonParser.parseReader(reader), "内置资源 " + path);
                }
            } catch (Exception e) {
                log.warn("[Running] 解析内置方块名称表失败: {}", e.toString());
                return empty();
            }
        }
        log.warn("[Running] 未找到内置方块名称表资源（{}）。", String.join(", ", EMBEDDED_RESOURCES));
        return empty();
    }

    /** 从文件加载；文件不存在或解析失败时返回空表 */
    public static BlockNameTable load(File file) {
        if (file == null || !file.isFile()) return empty();

        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            return parse(JsonParser.parseReader(reader), "工作目录 " + file.getName());
        } catch (Exception e) {
            log.warn("[Running] 加载方块名称表失败: {}", e.toString());
            return empty();
        }
    }

    /** 解析 blocks.json 根（顶层数组或 {@code {"blocks": [...]}} 封装）并构建查询表；结构不符时返回空表 */
    private static BlockNameTable parse(JsonElement root, String source) {
        JsonArray blocks;
        if (root != null && root.isJsonArray()) {
            blocks = root.getAsJsonArray();
        } else if (root != null && root.isJsonObject()) {
            blocks = root.getAsJsonObject().getAsJsonArray("blocks");
        } else {
            blocks = null;
        }
        if (blocks == null || blocks.isEmpty()) {
            log.warn("[Running] {} 中缺少 blocks 数组，已忽略。", source);
            return empty();
        }

        // 第一遍：确定状态 ID 上界
        int maxStateId = -1;
        for (JsonElement element : blocks) {
            JsonObject block = element.getAsJsonObject();
            int min = block.get("minStateId").getAsInt();
            maxStateId = Math.max(maxStateId, min + stateCount(block) - 1);
        }
        if (maxStateId < 0) return empty();
        int stateTotal = maxStateId + 1;
        log.info("[Running] 方块数据源：{}，协议状态总数 {}（对应 {}）。",
                source, stateTotal, versionHint(stateTotal));
        // 逐状态权威碰撞表：状态数必须与 blocks.json 一致，否则整表作废并退化为启发式
        CollisionShapes.loadDefault(stateTotal);

        // 第二遍：填充名称、碰撞类型、楼梯/半砖状态、形状族与流体数据
        String[] names = new String[maxStateId + 1];
        byte[] boxTypes = new byte[maxStateId + 1];
        byte[] stairData = new byte[maxStateId + 1];
        byte[] slabData = new byte[maxStateId + 1];
        byte[] fluidTypes = new byte[maxStateId + 1];
        byte[] fluidAmounts = new byte[maxStateId + 1];
        byte[] fluidFallings = new byte[maxStateId + 1];
        byte[] shapeKinds = new byte[maxStateId + 1];
        byte[] shapeDatas = new byte[maxStateId + 1];
        float[] hardness = new float[maxStateId + 1];
        byte[] diggable = new byte[maxStateId + 1];
        byte[] toolCat = new byte[maxStateId + 1];
        byte[] reqTool = new byte[maxStateId + 1];
        Arrays.fill(stairData, STAIR_NONE);
        Arrays.fill(slabData, SLAB_NONE);
        Arrays.fill(hardness, Float.NaN);
        for (JsonElement element : blocks) {
            JsonObject block = element.getAsJsonObject();
            String name = block.get("name").getAsString();
            int min = block.get("minStateId").getAsInt();
            int count = stateCount(block);
            int limit = Math.min(min + count, names.length);
            byte boxType = readBoxType(block);
            for (int i = min; i < limit; i++) {
                names[i] = name;
                boxTypes[i] = boxType;
            }
            StateDims dims = parseDims(block, count);
            if (dims != null) {
                parseStairData(name, dims, count, min, limit, stairData);
                parseSlabData(name, dims, count, min, limit, slabData);
            }
            parseShapeKind(name, dims, count, min, limit, shapeKinds, shapeDatas);
            parseFluidData(name, dims, count, min, limit, fluidTypes, fluidAmounts, fluidFallings);
            parseMiningData(block, min, limit, hardness, diggable, toolCat, reqTool);
        }
        return new BlockNameTable(names, boxTypes, stairData, slabData,
                fluidTypes, fluidAmounts, fluidFallings, shapeKinds, shapeDatas,
                hardness, diggable, toolCat, reqTool);
    }

    /**
     * 解析方块挖掘属性（硬度 / 可挖 / 采集工具族 / 是否需正确工具）。
     *
     * <p>字段均来自 minecraft-data 的 blocks.json：{@code hardness}（-1 为不可破坏）、
     * {@code diggable}、{@code material}（形如 {@code mineable/pickaxe}）、
     * {@code harvestTools}（非空即需要正确工具才有掉落）。缺失字段保持默认（硬度 NaN、
     * 不可挖、无工具族、不需工具），调用方需处理 NaN。</p>
     */
    private static void parseMiningData(JsonObject block, int min, int limit,
                                        float[] hardness, byte[] diggable,
                                        byte[] toolCat, byte[] reqTool) {
        float hard = Float.NaN;
        JsonElement hardElement = block.get("hardness");
        if (hardElement != null && hardElement.isJsonPrimitive()) {
            hard = hardElement.getAsFloat();
        }
        byte dig = 0;
        JsonElement diggableElement = block.get("diggable");
        if (diggableElement != null && diggableElement.isJsonPrimitive()) {
            dig = (byte) (diggableElement.getAsBoolean() ? 1 : 0);
        }
        byte tool = TOOL_NONE;
        JsonElement materialElement = block.get("material");
        if (materialElement != null && materialElement.isJsonPrimitive()) {
            tool = toolCategoryFromMaterial(materialElement.getAsString());
        }
        byte req = 0;
        JsonElement harvestElement = block.get("harvestTools");
        if (harvestElement != null && harvestElement.isJsonObject()
                && !harvestElement.getAsJsonObject().isEmpty()) {
            req = 1;
        }
        for (int i = min; i < limit; i++) {
            hardness[i] = hard;
            diggable[i] = dig;
            toolCat[i] = tool;
            reqTool[i] = req;
        }
    }

    /** material 字符串 → TOOL_*（形如 {@code mineable/pickaxe}，取斜杠后的工具名） */
    private static byte toolCategoryFromMaterial(String material) {
        if (material == null) {
            return TOOL_NONE;
        }
        int slash = material.lastIndexOf('/');
        String tail = slash >= 0 ? material.substring(slash + 1) : material;
        return switch (tail) {
            case "pickaxe" -> TOOL_PICKAXE;
            case "axe" -> TOOL_AXE;
            case "shovel" -> TOOL_SHOVEL;
            case "hoe" -> TOOL_HOE;
            default -> TOOL_NONE;
        };
    }

    /**
     * 已知的「协议状态总数 → Minecraft 版本」对照。
     *
     * <p>blocks.json 本身不带版本字段，状态总数是唯一的版本指纹；
     * 不在表内说明数据来自其它版本，需要重新获取与该服务端协议一致的
     * blocks.json 与 collision_shapes.bin。</p>
     */
    private static String versionHint(int stateCount) {
        return switch (stateCount) {
            case 29671 -> "Minecraft 1.21.11";
            case 29873 -> "Minecraft 26.1.2";
            default -> "未知版本，请与服务端协议核对";
        };
    }

    /**
     * 解析 blocks.json 的状态属性维度定义（states 数组）。
     *
     * <p>数组顺序即游戏内属性注册顺序（最后一个维度变化最快）；枚举用 values
     * 数组顺序、bool 固定为 [true, false]（对齐 BooleanProperty 的
     * TRUE_INDEX=0/FALSE_INDEX=1）、int 用维度内序号（从 0 递增）。组合总数与
     * 状态数不符或含未知属性类型时返回 null。</p>
     */
    private static StateDims parseDims(JsonObject block, int count) {
        JsonElement statesElement = block.get("states");
        if (statesElement == null || !statesElement.isJsonArray()) {
            return null;
        }
        JsonArray states = statesElement.getAsJsonArray();
        int dimCount = states.size();
        int[] sizes = new int[dimCount];
        String[] propertyNames = new String[dimCount];
        String[][] valueNames = new String[dimCount][];
        int total = 1;
        for (int d = 0; d < dimCount; d++) {
            JsonObject state = states.get(d).getAsJsonObject();
            propertyNames[d] = state.get("name").getAsString();
            JsonElement valuesElement = state.get("values");
            if (valuesElement != null && valuesElement.isJsonArray()) {
                JsonArray values = valuesElement.getAsJsonArray();
                sizes[d] = values.size();
                valueNames[d] = new String[sizes[d]];
                for (int v = 0; v < sizes[d]; v++) {
                    valueNames[d][v] = values.get(v).getAsString();
                }
            } else if (state.has("num_values") && state.has("type")) {
                String type = state.get("type").getAsString();
                sizes[d] = state.get("num_values").getAsInt();
                if ("bool".equals(type) && sizes[d] == 2) {
                    valueNames[d] = new String[]{"true", "false"};
                } else if (!"int".equals(type)) {
                    return null;
                }
            } else {
                return null;
            }
            total *= sizes[d];
        }
        return total == count ? new StateDims(sizes, propertyNames, valueNames) : null;
    }

    /** 状态属性维度表：把状态偏移分解为逐维序号/值（对齐 vanilla StateDefinition 的枚举顺序） */
    private static final class StateDims {
        final int[] sizes;
        final String[] propertyNames;
        final String[][] valueNames;
        final int[] strides;

        StateDims(int[] sizes, String[] propertyNames, String[][] valueNames) {
            this.sizes = sizes;
            this.propertyNames = propertyNames;
            this.valueNames = valueNames;
            this.strides = new int[sizes.length];
            int stride = 1;
            for (int d = sizes.length - 1; d >= 0; d--) {
                this.strides[d] = stride;
                stride *= sizes[d];
            }
        }

        /** 某属性在状态偏移下的维度内序号 */
        int index(int dim, int offset) {
            return offset / strides[dim] % sizes[dim];
        }

        /** 某属性在状态偏移下的值名；int 属性（缺值名）返回 null */
        String value(int dim, int offset) {
            String[] names = valueNames[dim];
            return names == null ? null : names[index(dim, offset)];
        }

        /** 某 int 属性的数值（有值名时解析值名，否则序号即值）；解析失败返回 -1 */
        int intValue(int dim, int offset) {
            String name = value(dim, offset);
            if (name == null) {
                return index(dim, offset);
            }
            try {
                return Integer.parseInt(name);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        /** 查询属性维度序号；不存在或尺寸不符返回 -1 */
        int find(String propertyName, int expectedSize) {
            for (int d = 0; d < sizes.length; d++) {
                if (sizes[d] == expectedSize && propertyNames[d].equals(propertyName)) {
                    return d;
                }
            }
            return -1;
        }
    }

    /**
     * 解析楼梯的 facing/half/shape（任一值无法识别则整体放弃，退化为单盒近似）。
     * 编码：facing(bit0-1) | half(bit2) | shape(bit3-5)。
     */
    private static void parseStairData(String name, StateDims dims, int count, int min, int limit, byte[] out) {
        if (!name.endsWith("_stairs")) {
            return;
        }
        int facingDim = dims.find("facing", 4);
        int halfDim = dims.find("half", 2);
        int shapeDim = dims.find("shape", 5);
        if (facingDim < 0 || halfDim < 0 || shapeDim < 0) {
            return;
        }
        byte[] tmp = new byte[count];
        for (int i = 0; i < count; i++) {
            int facing = facingCode(dims.value(facingDim, i));
            String halfName = dims.value(halfDim, i);
            int shape = stairShapeCode(dims.value(shapeDim, i));
            if (facing < 0 || shape < 0 || !("top".equals(halfName) || "bottom".equals(halfName))) {
                return;
            }
            int half = "top".equals(halfName) ? 1 : 0;
            tmp[i] = (byte) (facing | (half << 2) | (shape << 3));
        }
        for (int i = 0; i < count && min + i < limit; i++) {
            out[min + i] = tmp[i];
        }
    }

    /** 解析半砖 type（bottom/top/double）；识别失败整体放弃 */
    private static void parseSlabData(String name, StateDims dims, int count, int min, int limit, byte[] out) {
        if (!name.endsWith("_slab")) {
            return;
        }
        int typeDim = dims.find("type", 3);
        if (typeDim < 0) {
            return;
        }
        byte[] tmp = new byte[count];
        for (int i = 0; i < count; i++) {
            String type = dims.value(typeDim, i);
            byte code;
            if ("bottom".equals(type)) {
                code = SLAB_BOTTOM;
            } else if ("top".equals(type)) {
                code = SLAB_TOP;
            } else if ("double".equals(type)) {
                code = SLAB_DOUBLE;
            } else {
                return;
            }
            tmp[i] = code;
        }
        for (int i = 0; i < count && min + i < limit; i++) {
            out[min + i] = tmp[i];
        }
    }

    /**
     * 解析特殊碰撞形状族的名称与状态数据。
     *
     * <p>名称匹配无条件进行（不受 states 完整性影响）；状态数据需要
     * {@link StateDims}，缺失时仅记录族、数据保持 0（位域含义见各
     * SHAPE_* 常量注释）。</p>
     */
    private static void parseShapeKind(String name, StateDims dims, int count, int min, int limit,
                                       byte[] kinds, byte[] datas) {
        byte kind;
        if (name.endsWith("_pressure_plate")) {
            kind = SHAPE_PLATE;
        } else if (name.endsWith("_carpet")) {
            kind = SHAPE_CARPET;
        } else if ("snow".equals(name)) {
            kind = SHAPE_SNOW;
        } else if (name.endsWith("_trapdoor")) {
            kind = SHAPE_TRAPDOOR;
        } else if ("farmland".equals(name) || "dirt_path".equals(name)) {
            kind = SHAPE_LOW;
        } else if ("soul_sand".equals(name)) {
            kind = SHAPE_SOUL_SAND;
        } else if ("chest".equals(name) || name.endsWith("_chest")) {
            kind = SHAPE_CHEST;
        } else if (name.endsWith("_bed")) {
            kind = SHAPE_BED;
        } else if ("hopper".equals(name)) {
            kind = SHAPE_HOPPER;
        } else if ("cauldron".equals(name) || name.endsWith("_cauldron")) {
            kind = SHAPE_CAULDRON;
        } else if ("enchanting_table".equals(name)) {
            kind = SHAPE_ENCHANTING;
        } else if ("daylight_detector".equals(name)) {
            kind = SHAPE_DAYLIGHT;
        } else if ("composter".equals(name)) {
            kind = SHAPE_COMPOSTER;
        } else if ("stonecutter".equals(name)) {
            kind = SHAPE_STONECUTTER;
        } else if ("brewing_stand".equals(name)) {
            kind = SHAPE_BREWING;
        } else if ("cake".equals(name) || name.endsWith("candle_cake")) {
            kind = SHAPE_CAKE;
        } else if ("flower_pot".equals(name) || name.startsWith("potted_")) {
            kind = SHAPE_FLOWER_POT;
        } else if ("end_portal_frame".equals(name)) {
            kind = SHAPE_END_FRAME;
        } else if ("dragon_egg".equals(name)) {
            kind = SHAPE_DRAGON_EGG;
        } else if (name.endsWith("campfire")) {
            kind = SHAPE_CAMPFIRE;
        } else if (name.endsWith("_button")) {
            kind = SHAPE_BUTTON;
        } else if ("bamboo".equals(name)) {
            kind = SHAPE_BAMBOO;
        } else if ("anvil".equals(name) || name.endsWith("_anvil")) {
            kind = SHAPE_ANVIL;
        } else if ("chain".equals(name)) {
            kind = SHAPE_CHAIN;
        } else if (name.endsWith("_rod")) {
            kind = SHAPE_ROD;
        } else if ("lantern".equals(name) || "soul_lantern".equals(name)) {
            kind = SHAPE_LANTERN;
        } else if ("scaffolding".equals(name)) {
            kind = SHAPE_SCAFFOLDING;
        } else if ("turtle_egg".equals(name)) {
            kind = SHAPE_TURTLE_EGG;
        } else if ("candle".equals(name) || name.endsWith("_candle")) {
            kind = SHAPE_CANDLE;
        } else if (name.endsWith("_door")) {
            kind = SHAPE_DOOR;
        } else if (name.endsWith("_fence_gate")) {
            kind = SHAPE_FENCE_GATE;
        } else if (name.endsWith("_pane") || name.endsWith("_bars")) {
            kind = SHAPE_PANE;
        } else if (name.endsWith("_fence")) {
            kind = SHAPE_FENCE;
        } else if (name.endsWith("_wall")) {
            kind = SHAPE_WALL;
        } else if ("big_dripleaf".equals(name)) {
            kind = SHAPE_BIG_DRIPLEAF;
        } else if ("repeater".equals(name) || "comparator".equals(name)) {
            kind = SHAPE_DIODE;
        } else if ("conduit".equals(name)) {
            kind = SHAPE_CONDUIT;
        } else if ("sea_pickle".equals(name)) {
            kind = SHAPE_SEA_PICKLE;
        } else if ("lectern".equals(name)) {
            kind = SHAPE_LECTERN;
        } else if ("decorated_pot".equals(name)) {
            kind = SHAPE_DECORATED_POT;
        } else if ("sculk_sensor".equals(name) || "calibrated_sculk_sensor".equals(name)) {
            kind = SHAPE_SCULK_SENSOR;
        } else {
            return;
        }
        for (int i = min; i < limit; i++) {
            kinds[i] = kind;
        }
        if (dims == null) {
            // 无状态维度信息时给出保守默认：墙按独立中心柱建模（data 0 会退化为无碰撞）
            if (kind == SHAPE_WALL) {
                for (int i = min; i < limit; i++) {
                    datas[i] = 1 << 4;
                }
            }
            return;
        }
        switch (kind) {
            case SHAPE_PLATE -> {
                int poweredDim = dims.find("powered", 2);
                if (poweredDim >= 0) {
                    for (int i = 0; i < count && min + i < limit; i++) {
                        if (dims.index(poweredDim, i) == 0) {
                            datas[min + i] = 1;
                        }
                    }
                } else {
                    // 测重压力板（light/heavy）：power(0..15) > 0 即按下（高度 0.5px）
                    int powerDim = dims.find("power", 16);
                    if (powerDim >= 0) {
                        for (int i = 0; i < count && min + i < limit; i++) {
                            if (dims.intValue(powerDim, i) > 0) {
                                datas[min + i] = 1;
                            }
                        }
                    }
                }
            }
            case SHAPE_CARPET -> {
                // 普通地毯无 bottom 属性；pale_moss_carpet bottom=false → 无碰撞
                int bottomDim = dims.find("bottom", 2);
                if (bottomDim >= 0) {
                    for (int i = 0; i < count && min + i < limit; i++) {
                        if (dims.index(bottomDim, i) != 0) {
                            datas[min + i] = 1;
                        }
                    }
                }
            }
            case SHAPE_SNOW -> {
                int layersDim = dims.find("layers", 8);
                for (int i = 0; i < count && min + i < limit; i++) {
                    int layers = layersDim >= 0 ? dims.intValue(layersDim, i) : 1;
                    if (layers < 1) {
                        layers = 1;
                    } else if (layers > 8) {
                        layers = 8;
                    }
                    datas[min + i] = (byte) layers;
                }
            }
            case SHAPE_TRAPDOOR -> {
                int openDim = dims.find("open", 2);
                int halfDim = dims.find("half", 2);
                int facingDim = dims.find("facing", 4);
                for (int i = 0; i < count && min + i < limit; i++) {
                    int data = openDim >= 0 && dims.index(openDim, i) == 0 ? 1 : 0;
                    if (halfDim >= 0 && "top".equals(dims.value(halfDim, i))) {
                        data |= 2;
                    }
                    int facing = facingDim >= 0 ? facingCode(dims.value(facingDim, i)) : -1;
                    if (facing >= 0) {
                        data |= facing << 2;
                    }
                    datas[min + i] = (byte) data;
                }
            }
            case SHAPE_BED -> {
                int facingDim = dims.find("facing", 4);
                int partDim = dims.find("part", 2);
                if (facingDim >= 0 && partDim >= 0) {
                    for (int i = 0; i < count && min + i < limit; i++) {
                        int facing = facingCode(dims.value(facingDim, i));
                        if (facing < 0) {
                            continue;
                        }
                        // 形状 key = getConnectedDirection(state).getOpposite() = head ? facing : opposite(facing)
                        boolean head = "head".equals(dims.value(partDim, i));
                        datas[min + i] = (byte) (head ? facing : oppositeFacing(facing));
                    }
                }
            }
            case SHAPE_HOPPER -> {
                int facingDim = dims.find("facing", 5);
                if (facingDim >= 0) {
                    for (int i = 0; i < count && min + i < limit; i++) {
                        int code = switch (dims.value(facingDim, i)) {
                            case "down" -> 0;
                            case "north" -> 1;
                            case "south" -> 2;
                            case "west" -> 3;
                            case "east" -> 4;
                            default -> -1;
                        };
                        if (code >= 0) {
                            datas[min + i] = (byte) code;
                        }
                    }
                }
            }
            case SHAPE_COMPOSTER -> {
                int levelDim = dims.find("level", 9);
                for (int i = 0; i < count && min + i < limit; i++) {
                    int level = levelDim >= 0 ? dims.intValue(levelDim, i) : 0;
                    if (level < 0) {
                        level = 0;
                    } else if (level > 8) {
                        level = 8;
                    }
                    datas[min + i] = (byte) level;
                }
            }
            case SHAPE_CAKE -> {
                int bitesDim = dims.find("bites", 7);
                boolean candle = name.endsWith("candle_cake");
                for (int i = 0; i < count && min + i < limit; i++) {
                    int bites = bitesDim >= 0 ? dims.intValue(bitesDim, i) : 0;
                    if (bites < 0) {
                        bites = 0;
                    } else if (bites > 6) {
                        bites = 6;
                    }
                    datas[min + i] = (byte) (bites | (candle ? 8 : 0));
                }
            }
            case SHAPE_END_FRAME -> {
                int eyeDim = dims.find("eye", 2);
                if (eyeDim >= 0) {
                    for (int i = 0; i < count && min + i < limit; i++) {
                        if (dims.index(eyeDim, i) == 0) {
                            datas[min + i] = 1;
                        }
                    }
                }
            }
            case SHAPE_BUTTON -> {
                int facingDim = dims.find("facing", 4);
                int faceDim = dims.find("face", 3);
                for (int i = 0; i < count && min + i < limit; i++) {
                    int facing = facingDim >= 0 ? facingCode(dims.value(facingDim, i)) : -1;
                    int data = facing >= 0 ? facing : 0;
                    // AttachFace 枚举序 floor=0/wall=1/ceiling=2；缺属性时按原版默认 wall
                    int face = 1;
                    if (faceDim >= 0) {
                        String faceName = dims.value(faceDim, i);
                        face = "floor".equals(faceName) ? 0 : "ceiling".equals(faceName) ? 2 : 1;
                    }
                    data |= face << 2;
                    datas[min + i] = (byte) data;
                }
            }
            case SHAPE_ANVIL -> {
                int facingDim = dims.find("facing", 4);
                if (facingDim >= 0) {
                    for (int i = 0; i < count && min + i < limit; i++) {
                        String facingName = dims.value(facingDim, i);
                        if ("east".equals(facingName) || "west".equals(facingName)) {
                            datas[min + i] = 1;
                        }
                    }
                }
            }
            case SHAPE_CHAIN, SHAPE_ROD -> {
                int axisDim = dims.find("axis", 3);
                for (int i = 0; i < count && min + i < limit; i++) {
                    // 缺属性按原版默认 y 轴
                    int axis = 1;
                    if (axisDim >= 0) {
                        String axisName = dims.value(axisDim, i);
                        axis = "x".equals(axisName) ? 0 : "z".equals(axisName) ? 2 : 1;
                    }
                    datas[min + i] = (byte) axis;
                }
            }
            case SHAPE_LANTERN -> {
                int hangingDim = dims.find("hanging", 2);
                if (hangingDim >= 0) {
                    for (int i = 0; i < count && min + i < limit; i++) {
                        if (dims.index(hangingDim, i) == 0) {
                            datas[min + i] = 1;
                        }
                    }
                }
            }
            case SHAPE_TURTLE_EGG -> {
                int eggsDim = dims.find("eggs", 4);
                for (int i = 0; i < count && min + i < limit; i++) {
                    int eggs = eggsDim >= 0 ? dims.intValue(eggsDim, i) : 1;
                    if (eggs < 1) {
                        eggs = 1;
                    } else if (eggs > 4) {
                        eggs = 4;
                    }
                    datas[min + i] = (byte) eggs;
                }
            }
            case SHAPE_CANDLE -> {
                int candlesDim = dims.find("candles", 4);
                for (int i = 0; i < count && min + i < limit; i++) {
                    int candles = candlesDim >= 0 ? dims.intValue(candlesDim, i) : 1;
                    if (candles < 1) {
                        candles = 1;
                    } else if (candles > 4) {
                        candles = 4;
                    }
                    datas[min + i] = (byte) candles;
                }
            }
            case SHAPE_DOOR -> {
                int facingDim = dims.find("facing", 4);
                int openDim = dims.find("open", 2);
                int hingeDim = dims.find("hinge", 2);
                for (int i = 0; i < count && min + i < limit; i++) {
                    int facing = facingDim >= 0 ? facingCode(dims.value(facingDim, i)) : 0;
                    int doorData = facing < 0 ? 0 : facing;
                    if (openDim >= 0 && dims.index(openDim, i) == 0) {
                        doorData |= 1 << 2;
                    }
                    if (hingeDim >= 0 && "right".equals(dims.value(hingeDim, i))) {
                        doorData |= 1 << 3;
                    }
                    datas[min + i] = (byte) doorData;
                }
            }
            case SHAPE_FENCE_GATE -> {
                int facingDim = dims.find("facing", 4);
                int openDim = dims.find("open", 2);
                for (int i = 0; i < count && min + i < limit; i++) {
                    int facing = facingDim >= 0 ? facingCode(dims.value(facingDim, i)) : 0;
                    int gateData = facing < 0 ? 0 : facing;
                    if (openDim >= 0 && dims.index(openDim, i) == 0) {
                        gateData |= 1 << 2;
                    }
                    datas[min + i] = (byte) gateData;
                }
            }
            case SHAPE_PANE, SHAPE_FENCE -> {
                // CrossCollisionBlock 家族（玻璃板/铁栏杆/栅栏）：北东南西布尔连接，
                // BooleanProperty 值序 [true, false] → index==0 即连接
                int northDim = dims.find("north", 2);
                int eastDim = dims.find("east", 2);
                int southDim = dims.find("south", 2);
                int westDim = dims.find("west", 2);
                for (int i = 0; i < count && min + i < limit; i++) {
                    int connData = 0;
                    if (northDim >= 0 && dims.index(northDim, i) == 0) {
                        connData |= 1;
                    }
                    if (eastDim >= 0 && dims.index(eastDim, i) == 0) {
                        connData |= 1 << 1;
                    }
                    if (southDim >= 0 && dims.index(southDim, i) == 0) {
                        connData |= 1 << 2;
                    }
                    if (westDim >= 0 && dims.index(westDim, i) == 0) {
                        connData |= 1 << 3;
                    }
                    datas[min + i] = (byte) connData;
                }
            }
            case SHAPE_WALL -> {
                // WallBlock：四侧枚举值序 [none, low, tall] → index!=0 即有墙臂；up 布尔取柱
                int northDim = dims.find("north", 3);
                int eastDim = dims.find("east", 3);
                int southDim = dims.find("south", 3);
                int westDim = dims.find("west", 3);
                int upDim = dims.find("up", 2);
                for (int i = 0; i < count && min + i < limit; i++) {
                    int wallData = 0;
                    if (northDim >= 0 && dims.index(northDim, i) != 0) {
                        wallData |= 1;
                    }
                    if (eastDim >= 0 && dims.index(eastDim, i) != 0) {
                        wallData |= 1 << 1;
                    }
                    if (southDim >= 0 && dims.index(southDim, i) != 0) {
                        wallData |= 1 << 2;
                    }
                    if (westDim >= 0 && dims.index(westDim, i) != 0) {
                        wallData |= 1 << 3;
                    }
                    if (upDim >= 0 && dims.index(upDim, i) == 0) {
                        wallData |= 1 << 4;
                    }
                    datas[min + i] = (byte) wallData;
                }
            }
            case SHAPE_BIG_DRIPLEAF -> {
                int tiltDim = dims.find("tilt", 4);
                for (int i = 0; i < count && min + i < limit; i++) {
                    datas[min + i] = (byte) (tiltDim >= 0 ? dims.index(tiltDim, i) : 0);
                }
            }
            case SHAPE_SEA_PICKLE -> {
                int picklesDim = dims.find("pickles", 4);
                for (int i = 0; i < count && min + i < limit; i++) {
                    int pickles = picklesDim >= 0 ? dims.intValue(picklesDim, i) : 1;
                    if (pickles < 1) {
                        pickles = 1;
                    } else if (pickles > 4) {
                        pickles = 4;
                    }
                    datas[min + i] = (byte) pickles;
                }
            }
            default -> {
            }
        }
    }

    /**
     * 解析流体数据（对齐官方 getFluidState 语义）：
     * <ul>
     *   <li>water/lava 方块：LEVEL 属性经 LiquidBlock.stateCache 映射——
     *       0=source(量 8)、1..7=流动量 8-level、≥8=下落(量 8)；</li>
     *   <li>waterlogged=true 方块：水源（对齐 SimpleWaterloggedBlock）；</li>
     *   <li>气泡柱与水下植物：恒水源（对齐 BubbleColumnBlock/SeagrassBlock 等）。</li>
     * </ul>
     */
    private static void parseFluidData(String name, StateDims dims, int count, int min, int limit,
                                       byte[] types, byte[] amounts, byte[] fallings) {
        if (WATER_SOURCE_NAMES.contains(name)) {
            for (int i = min; i < limit; i++) {
                types[i] = (byte) FLUID_WATER;
                amounts[i] = 8;
            }
            return;
        }
        byte type;
        if ("water".equals(name)) {
            type = (byte) FLUID_WATER;
        } else if ("lava".equals(name)) {
            type = (byte) FLUID_LAVA;
        } else {
            // 其余方块的 waterlogged=true（BooleanProperty 值顺序 true=0）→ 水源
            if (dims == null) {
                return;
            }
            int waterloggedDim = dims.find("waterlogged", 2);
            if (waterloggedDim < 0) {
                return;
            }
            for (int i = 0; i < count && min + i < limit; i++) {
                if (dims.index(waterloggedDim, i) == 0) {
                    types[min + i] = (byte) FLUID_WATER;
                    amounts[min + i] = 8;
                }
            }
            return;
        }
        // water/lava 方块：level → stateCache 语义
        int levelDim = dims != null ? dims.find("level", 16) : -1;
        if (levelDim < 0) {
            // 无属性数据：保守地把默认（首个）状态视为源
            if (min < limit) {
                types[min] = type;
                amounts[min] = 8;
            }
            return;
        }
        for (int i = 0; i < count && min + i < limit; i++) {
            int level = dims.intValue(levelDim, i);
            if (level < 0) {
                continue;
            }
            byte amount;
            byte falling;
            if (level == 0) {
                amount = 8;
                falling = 0;
            } else if (level <= 7) {
                amount = (byte) (8 - level);
                falling = 0;
            } else {
                amount = 8;
                falling = 1;
            }
            types[min + i] = type;
            amounts[min + i] = amount;
            fallings[min + i] = falling;
        }
    }

    /** 水平朝向名称 → 编码（0=north, 1=south, 2=west, 3=east，对齐 Direction 枚举序） */
    private static int facingCode(String name) {
        if (name == null) {
            return -1;
        }
        return switch (name) {
            case "north" -> 0;
            case "south" -> 1;
            case "west" -> 2;
            case "east" -> 3;
            default -> -1;
        };
    }

    /** 水平朝向取反（north↔south, west↔east） */
    private static int oppositeFacing(int code) {
        return switch (code) {
            case 0 -> 1;
            case 1 -> 0;
            case 2 -> 3;
            case 3 -> 2;
            default -> code;
        };
    }

    /** 楼梯形状名称 → 编码（对齐 StairsShape 枚举序） */
    private static int stairShapeCode(String name) {
        if (name == null) {
            return -1;
        }
        return switch (name) {
            case "straight" -> STAIR_SHAPE_STRAIGHT;
            case "inner_left" -> STAIR_SHAPE_INNER_LEFT;
            case "inner_right" -> STAIR_SHAPE_INNER_RIGHT;
            case "outer_left" -> STAIR_SHAPE_OUTER_LEFT;
            case "outer_right" -> STAIR_SHAPE_OUTER_RIGHT;
            default -> -1;
        };
    }

    /** 读取 blocks.json 的 boundingBox 字段（可选，"block"/"empty"） */
    private static byte readBoxType(JsonObject block) {
        JsonElement box = block.get("boundingBox");
        if (box == null || !box.isJsonPrimitive()) {
            return BOX_UNKNOWN;
        }
        String value = box.getAsString();
        if ("empty".equals(value)) {
            return BOX_EMPTY;
        }
        if ("block".equals(value)) {
            return BOX_BLOCK;
        }
        return BOX_UNKNOWN;
    }

    private static int stateCount(JsonObject block) {
        if (block.has("maxStateId")) {
            return Math.max(1, block.get("maxStateId").getAsInt() - block.get("minStateId").getAsInt() + 1);
        }
        if (block.has("states") && block.get("states").isJsonArray()) {
            return Math.max(1, block.getAsJsonArray("states").size());
        }
        return 1;
    }

    public boolean isEmpty() {
        return namesByStateId.length == 0;
    }

    /** 表覆盖的状态 ID 总数（即最大状态 ID + 1） */
    public int size() {
        return namesByStateId.length;
    }

    /**
     * 查询状态 ID 对应的方块名称。
     *
     * @return 方块名称；表为空或状态 ID 超出覆盖范围时返回 null
     */
    public String nameOf(int stateId) {
        if (stateId < 0) {
            return null;
        }
        if (stateId >= namesByStateId.length) {
            warnOutOfRange(stateId);
            return null;
        }
        return namesByStateId[stateId];
    }

    /**
     * 该方块名是否存在“放到此处可作为站立面”的状态（有碰撞且非流体）。用于判断
     * 背包物品能否用作缺口搭桥的放置方块（方块类物品的物品名与方块名通常一致，
     * 见 {@code ItemRegistry}）。
     */
    public boolean isStandableBlockName(String name) {
        if (name == null) {
            return false;
        }
        Set<String> snapshot = standableBlockNames;
        if (snapshot == null) {
            Set<String> built = new HashSet<>();
            for (int id = 0; id < namesByStateId.length; id++) {
                String n = namesByStateId[id];
                if (n != null && fluidTypeByStateId[id] == FLUID_NONE && hasCollision(id)) {
                    built.add(n);
                }
            }
            standableBlockNames = built;
            snapshot = built;
        }
        return snapshot.contains(name);
    }

    /**
     * 表外状态 ID 的一次性告警。
     *
     * <p>出现本地表覆盖不到的 ID，说明机器人协商到的协议版本状态总数大于本地数据
     * （数据版本偏旧）。继续静默查询会把所有新方块当成“非空气即整方块”，
     * 表现为莫名的卡住与减速，必须显式提示。</p>
     */
    private void warnOutOfRange(int stateId) {
        if (namesByStateId.length == 0 || !outOfRangeWarned.compareAndSet(false, true)) {
            return;
        }
        log.warn("[Running] 收到超出数据表范围的状态 ID {}（本地数据覆盖 0..{}，共 {} 个状态）。"
                + "机器人协商到的协议版本比本地 blocks.json / collision_shapes.bin 更新，"
                + "请替换为同版本的一对数据。", stateId, namesByStateId.length - 1, namesByStateId.length);
    }

    // ------------------------------------------------------------------
    // 挖掘属性查询（供破块代价模型使用）
    // ------------------------------------------------------------------

    /**
     * 查询方块硬度（对齐 blocks.json 的 {@code hardness}）。
     *
     * @return 硬度；{@code -1} 表示不可破坏（基岩/末传送门等）；
     *         表为空或超出覆盖范围时返回 {@link Float#NaN}（调用方应视为不可破坏）
     */
    public float hardness(int stateId) {
        if (stateId < 0 || stateId >= hardnessByStateId.length) {
            return Float.NaN;
        }
        return hardnessByStateId[stateId];
    }

    /** 是否可挖掘（{@code diggable} 且硬度不为 -1；硬度未知时保守判不可挖） */
    public boolean isDiggable(int stateId) {
        if (stateId < 0 || stateId >= diggableByStateId.length) {
            return false;
        }
        float hard = hardnessByStateId[stateId];
        if (Float.isNaN(hard) || hard < 0) {
            return false;
        }
        return diggableByStateId[stateId] != 0;
    }

    /** 该方块需要哪类采集工具（TOOL_PICKAXE 等）；无特定工具返回 {@link #TOOL_NONE} */
    public byte toolCategory(int stateId) {
        if (stateId < 0 || stateId >= toolCategoryByStateId.length) {
            return TOOL_NONE;
        }
        return toolCategoryByStateId[stateId];
    }

    /** 是否需要正确工具才能获得掉落（{@code harvestTools} 非空）；影响破块速度除数 */
    public boolean requiresCorrectTool(int stateId) {
        if (stateId < 0 || stateId >= requiresToolByStateId.length) {
            return false;
        }
        return requiresToolByStateId[stateId] != 0;
    }

    // ------------------------------------------------------------------
    // 碰撞与摩擦查询（供 PlayerPhysics 使用）
    // ------------------------------------------------------------------

    /**
     * 查询方块碰撞盒高度（相对方块底部的偏移量）。
     *
     * <p>简化模型：0 = 无碰撞（可穿过）；0.5 = 半砖近似；1.0 = 完整立方体。
     * 有状态数据时半砖按 bottom/top=0.5、double=1.0 精确建模；{@link #isEmptyCollision}
     * 命中的方块（名称启发式非固体、或 blocks.json {@code boundingBox=empty}
     * 即原版 {@code .noCollision()}）直接返回 0；特殊形状族
     * （{@link #SHAPE_NONE} 之外的 kind，如雪层 (layers-1)/8、栅栏/墙 1.5 等）
     * 按 {@link #shapeHeight} 精确返回顶面高度；楼梯的实际形状由
     * {@link #addCollisionBoxes} 精确生成，此处返回最大高度 1.0；
     * 栅栏门按 1.5 近似；状态 ID 0 始终视为空气。</p>
     *
     * <p>优先取 {@link CollisionShapes} 权威表的形状最大顶面高度；仅当权威表不可用时
     * 才退化为下列启发式。</p>
     *
     * @return 碰撞高度；无碰撞返回 0
     */
    public double collisionHeight(int stateId) {
        CollisionShapes shapes = CollisionShapes.get();
        if (shapes != null && shapes.covers(stateId)) {
            return shapes.maxHeight(stateId);
        }
        if (stateId == 0) {
            return 0.0;
        }

        String name = nameOf(stateId);
        if (name != null) {
            if (isEmptyCollision(stateId)) {
                return 0.0;
            }
            byte kind = shapeKind(stateId);
            if (kind != SHAPE_NONE) {
                return shapeHeight(kind, shapeData(stateId));
            }
            if (name.endsWith("_slab")) {
                return slabData(stateId) == SLAB_DOUBLE ? HEIGHT_FULL : HEIGHT_SLAB;
            }
            // 楼梯：有状态数据时实际形状由 addCollisionBoxes 生成，此处返回最大高度
            return HEIGHT_FULL;
        }

        // 表为空或状态 ID 超出覆盖范围：优先 boundingBox 字段，否则非空气即完整方块
        byte boxType = boxTypeOf(stateId);
        if (boxType == BOX_EMPTY) {
            return 0.0;
        }
        return HEIGHT_FULL;
    }

    /**
     * 精确形状族的顶面高度（相对方块底部，对齐原版 getCollisionShape 的最大 Y）。
     *
     * <p>与 {@link #addShapedCollisionBoxes} 的盒数据保持一致；占用整块高度的族
     * （漏斗/炼药锅/堆肥桶/龙蛋/竹子/铁砧）返回 {@link #HEIGHT_FULL}。</p>
     */
    private static double shapeHeight(byte kind, byte data) {
        return switch (kind) {
            // 原版 BasePressurePlateBlock / ButtonBlock 的 properties 均带 .noCollision()
            case SHAPE_PLATE, SHAPE_BUTTON -> 0.0;
            case SHAPE_CARPET -> (data & 1) != 0 ? 0.0 : 1.0 / 16.0;
            case SHAPE_SNOW -> ((data & 0xF) - 1) * 2.0 / 16.0;
            case SHAPE_TRAPDOOR -> {
                // 关闭且 bottom：3px；其余（关闭 top / 打开竖板）顶面均为整高
                double height = (data & 1) == 0 && (data & 2) == 0 ? 3.0 : 16.0;
                yield height / 16.0;
            }
            case SHAPE_LOW -> 15.0 / 16.0;
            case SHAPE_SOUL_SAND, SHAPE_CHEST, SHAPE_BREWING -> 14.0 / 16.0;
            case SHAPE_BED -> 9.0 / 16.0;
            case SHAPE_ENCHANTING -> 12.0 / 16.0;
            case SHAPE_DAYLIGHT -> 6.0 / 16.0;
            case SHAPE_STONECUTTER -> 9.0 / 16.0;
            case SHAPE_CAKE -> (data & 8) != 0 ? 14.0 / 16.0 : 8.0 / 16.0;
            case SHAPE_FLOWER_POT -> 6.0 / 16.0;
            case SHAPE_END_FRAME -> (data & 1) != 0 ? 1.0 : 13.0 / 16.0;
            case SHAPE_CAMPFIRE -> 7.0 / 16.0;
            case SHAPE_LANTERN -> (data & 1) != 0 ? 10.0 / 16.0 : 9.0 / 16.0;
            case SHAPE_TURTLE_EGG -> 7.0 / 16.0;
            case SHAPE_CANDLE -> 6.0 / 16.0;
            case SHAPE_DOOR -> HEIGHT_FULL;
            case SHAPE_FENCE_GATE -> (data & 4) != 0 ? 0.0 : HEIGHT_FENCE;
            case SHAPE_PANE -> HEIGHT_FULL;
            // 栅栏：无连接时仅 1.0 高的中心柱，有连接时上部横臂顶到 1.5
            // 【仅兜底路径，且已被权威表证伪】原版 oak_fence 全状态盒均为 y 0..24（不存在 maxY=1.0 的盒）；
            // 正确几何由 CollisionShapes 提供，本分支只在 collision_shapes.bin 缺失时生效
            case SHAPE_FENCE -> (data & 0xF) == 0 ? HEIGHT_FULL : HEIGHT_FENCE;
            // 墙：up 中心柱或任一墙臂都顶到 1.5；两者皆无则确实无碰撞
            case SHAPE_WALL -> (data & 0x1F) == 0 ? 0.0 : HEIGHT_FENCE;
            // 大垂滴叶叶片板：none/unstable 顶 15px、partial 顶 13px、full 无碰撞
            case SHAPE_BIG_DRIPLEAF -> switch (data & 3) {
                case 2 -> 13.0 / 16.0;
                case 3 -> 0.0;
                default -> 15.0 / 16.0;
            };
            case SHAPE_DIODE -> 2.0 / 16.0;
            case SHAPE_CONDUIT -> 11.0 / 16.0;
            case SHAPE_SEA_PICKLE -> (data >= 4 ? 7.0 : 6.0) / 16.0;
            case SHAPE_LECTERN -> 14.0 / 16.0;
            case SHAPE_DECORATED_POT -> HEIGHT_FULL;
            case SHAPE_SCULK_SENSOR -> 8.0 / 16.0;
            default -> HEIGHT_FULL;
        };
    }

    /** 是否存在碰撞盒（collisionHeight &gt; 0 的便捷判断） */
    public boolean hasCollision(int stateId) {
        return collisionHeight(stateId) > 0.0;
    }

    /**
     * 查询方块底面摩擦系数（对齐 Blocks 的 getFriction，默认 0.6）。
     *
     * <p>仅识别影响移动手感的主要方块：冰类 0.98、蓝冰 0.989、粘液块 0.8。</p>
     */
    public double friction(int stateId) {
        String name = nameOf(stateId);
        if (name != null) {
            switch (name) {
                case "ice", "packed_ice", "frosted_ice" -> {
                    return 0.98;
                }
                case "blue_ice" -> {
                    return 0.989;
                }
                case "slime_block" -> {
                    return 0.8;
                }
                default -> {
                    return DEFAULT_FRICTION;
                }
            }
        }
        return DEFAULT_FRICTION;
    }

    /**
     * 查询方块速度因子（对齐 {@code BlockBehaviour.getSpeedFactor}，默认 1.0）。
     * 仅识别影响移动手感的方块：灵魂沙与蜂蜜块为 0.4。
     */
    public float speedFactor(int stateId) {
        String name = nameOf(stateId);
        if ("soul_sand".equals(name) || "honey_block".equals(name)) {
            return 0.4F;
        }
        return 1.0F;
    }

    /**
     * 查询方块跳跃因子（对齐 {@code BlockBehaviour.getJumpFactor}，默认 1.0）。
     * 仅识别蜂蜜块（0.5）。
     */
    public float jumpFactor(int stateId) {
        return "honey_block".equals(nameOf(stateId)) ? 0.5F : 1.0F;
    }

    // ------------------------------------------------------------------
    // 流体查询（供 PlayerPhysics 复刻 FlowingFluid / EntityFluidInteraction）
    // ------------------------------------------------------------------

    /** 查询流体族（{@link #FLUID_NONE}/{@link #FLUID_WATER}/{@link #FLUID_LAVA}）；表外返回 NONE */
    public int fluidType(int stateId) {
        if (stateId < 0 || stateId >= fluidTypeByStateId.length) return FLUID_NONE;
        return fluidTypeByStateId[stateId];
    }

    /** 查询流体量（1..8，对齐 FluidState.getAmount）；无流体为 0 */
    public int fluidAmount(int stateId) {
        if (stateId < 0 || stateId >= fluidAmountByStateId.length) return 0;
        return fluidAmountByStateId[stateId];
    }

    /** 查询流体是否下落（FALLING 属性；仅用于 Fluid.getFlow 的下落加速判定） */
    public boolean fluidFalling(int stateId) {
        if (stateId < 0 || stateId >= fluidFallingByStateId.length) return false;
        return fluidFallingByStateId[stateId] != 0;
    }

    /** 流体自身高度 amount/9（对齐 FlowingFluid.getOwnHeight） */
    public float fluidOwnHeight(int stateId) {
        return fluidAmount(stateId) / 9.0F;
    }

    /** 是否为冰类方块（冰/霜冰；对齐 FlowingFluid.isSolidFace 的 IceBlock 特判） */
    public boolean isIce(int stateId) {
        String name = nameOf(stateId);
        return "ice".equals(name) || "frosted_ice".equals(name);
    }

    // ------------------------------------------------------------------
    // 形状支撑面查询（供 Fluid.getFlow 复刻）
    // ------------------------------------------------------------------

    /**
     * 是否阻挡运动（对齐 {@code BlockState.blocksMotion()}）：
     * 排除蜘蛛网/竹笋，且 {@code calculateSolid()} 成立——碰撞形状非空，
     * 包围盒平均尺寸 ≥ 0.729166667 或高度 ≥ 1.0。
     */
    public boolean blocksMotion(int stateId) {
        String name = nameOf(stateId);
        if ("cobweb".equals(name) || "bamboo_sapling".equals(name)) {
            return false;
        }
        List<AABB> boxes = new ArrayList<>(4);
        addCollisionBoxes(stateId, 0, 0, 0, boxes);
        if (boxes.isEmpty()) {
            return false;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (AABB box : boxes) {
            minX = Math.min(minX, box.minX);
            minY = Math.min(minY, box.minY);
            minZ = Math.min(minZ, box.minZ);
            maxX = Math.max(maxX, box.maxX);
            maxY = Math.max(maxY, box.maxY);
            maxZ = Math.max(maxZ, box.maxZ);
        }
        double size = ((maxX - minX) + (maxY - minY) + (maxZ - minZ)) / 3.0;
        return size >= 0.7291666666666666 || (maxY - minY) >= 1.0;
    }

    /**
     * 查询方块在给定面上的完整度（对齐 {@code SupportType.FULL} 的
     * {@code Block.isFaceFull}）：把碰撞形状投影到该面，检查该面一侧
     * 最外层 16×16 切片是否铺满整格。
     *
     * <p>复刻细节：形状在面轴向上恰好覆盖 [0,1]（坐标集为 {0,1}）时，
     * 官方 {@code VoxelShape.getFaceShape} 直接返回整体形状并检查
     * “是否满立方体”；否则取外层切片。冰类特判（不视为实体面）
     * 由调用方按 {@code FlowingFluid.isSolidFace} 处理。</p>
     *
     * @param face {@link #FACE_NORTH} 等面编码
     */
    public boolean isFaceSturdy(int stateId, int face) {
        List<AABB> boxes = new ArrayList<>(4);
        addCollisionBoxes(stateId, 0, 0, 0, boxes);
        if (boxes.isEmpty()) {
            return false;
        }
        int axis = FACE_AXIS[face];
        if (spansUnit(boxes, axis)) {
            return coversUnitCube(boxes);
        }
        boolean positive = FACE_POSITIVE[face];
        double layerMin = positive ? 15.0 / 16.0 : 0.0;
        double layerMax = positive ? 1.0 : 1.0 / 16.0;
        int uAxis = (axis + 1) % 3;
        int vAxis = (axis + 2) % 3;
        for (AABB box : boxes) {
            // 切片成为“满格”要求二维投影坐标恰为 [0,1]：任一盒在交叉轴上越界即不满足
            if (!withinUnit(box, uAxis) || !withinUnit(box, vAxis)) {
                return false;
            }
        }
        for (int u = 0; u < 16; u++) {
            for (int v = 0; v < 16; v++) {
                double uMin = u / 16.0;
                double vMin = v / 16.0;
                boolean covered = false;
                for (AABB box : boxes) {
                    if (coversOnAxis(box, axis, layerMin, layerMax)
                            && coversOnAxis(box, uAxis, uMin, uMin + 1.0 / 16.0)
                            && coversOnAxis(box, vAxis, vMin, vMin + 1.0 / 16.0)) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 所有盒在给定轴上都恰好覆盖 [0,1]（对齐 VoxelShape.isCubeLikeAlong 的坐标集判断） */
    private static boolean spansUnit(List<AABB> boxes, int axis) {
        for (AABB box : boxes) {
            if (!coversOnAxis(box, axis, 0.0, 1.0)) {
                return false;
            }
        }
        return true;
    }

    /** 盒的并集是否恰为整格立方体（对齐 isShapeFullBlock：不越界且 16³ 体素全覆盖） */
    private static boolean coversUnitCube(List<AABB> boxes) {
        if (boxes.size() == 1) {
            AABB box = boxes.get(0);
            return withinUnit(box, 0) && withinUnit(box, 1) && withinUnit(box, 2)
                    && coversOnAxis(box, 0, 0.0, 1.0) && coversOnAxis(box, 1, 0.0, 1.0)
                    && coversOnAxis(box, 2, 0.0, 1.0);
        }
        for (AABB box : boxes) {
            if (!withinUnit(box, 0) || !withinUnit(box, 1) || !withinUnit(box, 2)) {
                return false;
            }
        }
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    double xMin = x / 16.0;
                    double yMin = y / 16.0;
                    double zMin = z / 16.0;
                    boolean covered = false;
                    for (AABB box : boxes) {
                        if (coversOnAxis(box, 0, xMin, xMin + 1.0 / 16.0)
                                && coversOnAxis(box, 1, yMin, yMin + 1.0 / 16.0)
                                && coversOnAxis(box, 2, zMin, zMin + 1.0 / 16.0)) {
                            covered = true;
                            break;
                        }
                    }
                    if (!covered) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** 盒在 axis 轴上是否覆盖 [a, b] 区间（1e-7 容差） */
    private static boolean coversOnAxis(AABB box, int axis, double a, double b) {
        double min = axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ;
        double max = axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ;
        return min <= a + 1.0E-7 && max >= b - 1.0E-7;
    }

    /** 盒在 axis 轴上是否处于 [0,1] 内（1e-7 容差） */
    private static boolean withinUnit(AABB box, int axis) {
        double min = axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ;
        double max = axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ;
        return min >= -1.0E-7 && max <= 1.0 + 1.0E-7;
    }

    /**
     * 是否窒息方块（对齐官方 {@code isSuffocating}：{@code blocksMotion && isCollisionShapeFullBlock}；
     * 玻璃/树叶/红树根等完整立方体为 true，楼梯/半砖/栅栏/箱子/床等非完整形状为 false），
     * 用于玩家的碰撞脱困推力判定。
     */
    public boolean suffocates(int stateId) {
        CollisionShapes shapes = CollisionShapes.get();
        if (shapes != null && shapes.covers(stateId)) {
            // 对齐 isSuffocating = blocksMotion && isCollisionShapeFullBlock；完整立方体必然 blocksMotion
            return shapes.isFullCube(stateId);
        }
        String name = nameOf(stateId);
        if (name == null || isEmptyCollision(stateId)) {
            return false;
        }
        // 半砖：double 为完整立方体，bottom/top 非完整
        if (name.endsWith("_slab")) {
            return slabData(stateId) == SLAB_DOUBLE;
        }
        byte kind = shapeKind(stateId);
        // 堆肥桶 level=8：内腔满高，为完整立方体
        if (kind == SHAPE_COMPOSTER && (shapeData(stateId) & 0xF) >= 8) {
            return true;
        }
        // 楼梯与特殊形状族均非完整立方体（含 ceiling 按钮：虽占满高度但底面不接地）
        if (name.endsWith("_stairs") || kind != SHAPE_NONE || stairData(stateId) != STAIR_NONE) {
            return false;
        }
        // 玻璃板/铁栏杆/移动活塞：碰撞高度按整格近似但形状非完整立方体
        if (name.endsWith("_pane") || name.endsWith("_bars") || "moving_piston".equals(name)) {
            return false;
        }
        return collisionHeight(stateId) == HEIGHT_FULL;
    }

    /**
     * 生成方块碰撞盒（0 至 N 个），供碰撞解算收集。
     *
     * <p>拥有状态数据时按官方 VoxelShape 精确生成：半砖为整格半高单盒
     * （bottom/top/double），楼梯为底部半砖 + 台阶半砖的精确形状（含
     * shape × facing × half 的旋转映射，对齐 {@code StairBlock.getShape}），
     * 特殊形状族（压力板/地毯/雪层/活板门/床/漏斗等）按
     * {@link #addShapedCollisionBoxes} 生成精确多盒。
     * 其余方块按 {@link #collisionHeight} 的单盒启发式。</p>
     */
    public void addCollisionBoxes(int stateId, int bx, int by, int bz, List<AABB> out) {
        // 权威逐状态表：命中即完全采用，下面的启发式分支仅在其不可用时参与碰撞解算
        CollisionShapes shapes = CollisionShapes.get();
        if (shapes != null && shapes.covers(stateId)) {
            shapes.addBoxes(stateId, bx, by, bz, out);
            return;
        }
        // 零碰撞方块统一短路（与 collisionHeight 保持一致，避免两者对同一状态结论矛盾）
        if (isEmptyCollision(stateId)) {
            return;
        }
        byte slab = slabData(stateId);
        if (slab != SLAB_NONE) {
            switch (slab) {
                case SLAB_DOUBLE -> out.add(new AABB(bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0));
                case SLAB_TOP -> out.add(new AABB(bx, by + 0.5, bz, bx + 1.0, by + 1.0, bz + 1.0));
                default -> out.add(new AABB(bx, by, bz, bx + 1.0, by + 0.5, bz + 1.0));
            }
            return;
        }
        byte stair = stairData(stateId);
        if (stair != STAIR_NONE) {
            addStairCollisionBoxes(stair, bx, by, bz, out);
            return;
        }
        byte kind = shapeKind(stateId);
        if (kind != SHAPE_NONE) {
            addShapedCollisionBoxes(kind, shapeData(stateId), bx, by, bz, out);
            return;
        }
        double height = collisionHeight(stateId);
        if (height > 0.0) {
            out.add(new AABB(bx, by, bz, bx + 1.0, by + height, bz + 1.0));
        }
    }

    /**
     * 生成楼梯的官方精确形状（2 至 3 个盒）。
     *
     * <p>基础盒组（{@link #STAIR_OUTER_BOXES}/{@link #STAIR_STRAIGHT_BOXES}/
     * {@link #STAIR_INNER_BOXES}）以 bottom half、NORTH 键定义；形状键选择对齐
     * {@code StairBlock.getShape}：STRAIGHT/OUTER_LEFT/INNER_RIGHT 用 facing，
     * INNER_LEFT 用 {@code facing.getCounterClockWise()}，OUTER_RIGHT 用
     * {@code facing.getClockWise()}；再按 {@code Shapes.rotateHorizontal} 的
     * 4 个 y 轴旋转映射盒坐标（围绕方块中心），top half 额外做 y 翻转
     * （{@code OctahedralGroup.INVERT_Y}）。</p>
     */
    private static void addStairCollisionBoxes(byte stair, int bx, int by, int bz, List<AABB> out) {
        int facing = stair & 0x3;
        boolean top = ((stair >> 2) & 0x1) != 0;
        int shape = (stair >> 3) & 0x7;
        int key;
        double[][] boxes;
        switch (shape) {
            case STAIR_SHAPE_INNER_LEFT -> {
                key = counterClockWise(facing);
                boxes = STAIR_INNER_BOXES;
            }
            case STAIR_SHAPE_OUTER_RIGHT -> {
                key = clockWise(facing);
                boxes = STAIR_OUTER_BOXES;
            }
            case STAIR_SHAPE_OUTER_LEFT -> {
                key = facing;
                boxes = STAIR_OUTER_BOXES;
            }
            case STAIR_SHAPE_INNER_RIGHT -> {
                key = facing;
                boxes = STAIR_INNER_BOXES;
            }
            default -> {
                key = facing;
                boxes = STAIR_STRAIGHT_BOXES;
            }
        }
        for (double[] box : boxes) {
            double x0 = box[0];
            double y0 = box[1];
            double z0 = box[2];
            double x1 = box[3];
            double y1 = box[4];
            double z1 = box[5];
            double rx0;
            double rz0;
            double rx1;
            double rz1;
            switch (key) {
                case FACE_EAST -> {      // rotY90，(x,z)→(1-z,x)
                    rx0 = 1.0 - z1;
                    rz0 = x0;
                    rx1 = 1.0 - z0;
                    rz1 = x1;
                }
                case FACE_SOUTH -> {     // rotY180，(x,z)→(1-x,1-z)
                    rx0 = 1.0 - x1;
                    rz0 = 1.0 - z1;
                    rx1 = 1.0 - x0;
                    rz1 = 1.0 - z0;
                }
                case FACE_WEST -> {      // rotY270，(x,z)→(z,1-x)
                    rx0 = z0;
                    rz0 = 1.0 - x1;
                    rx1 = z1;
                    rz1 = 1.0 - x0;
                }
                default -> {             // FACE_NORTH：原形
                    rx0 = x0;
                    rz0 = z0;
                    rx1 = x1;
                    rz1 = z1;
                }
            }
            double ry0 = top ? 1.0 - y1 : y0;
            double ry1 = top ? 1.0 - y0 : y1;
            out.add(new AABB(bx + rx0, by + ry0, bz + rz0, bx + rx1, by + ry1, bz + rz1));
        }
    }

    /**
     * 生成特殊形状族的精确碰撞盒（px 单位，与 {@link #shapeHeight} 保持一致）。
     *
     * <p>盒数据均从原版 getCollisionShape 逐族核实（26.1.2 反编译源码）。</p>
     */
    private static void addShapedCollisionBoxes(byte kind, byte data, int bx, int by, int bz, List<AABB> out) {
        switch (kind) {
            case SHAPE_PLATE, SHAPE_BUTTON -> {
                // 原版 BasePressurePlateBlock / ButtonBlock 均为 .noCollision()：无碰撞盒
            }
            case SHAPE_CARPET -> {
                // CarpetBlock/WoolCarpetBlock：box(0,0,0,16,1,16)；pale_moss_carpet bottom=false 无碰撞
                if ((data & 1) == 0) {
                    addShapedBox(out, bx, by, bz, 0, 0, 0, 16, 1, 16);
                }
            }
            case SHAPE_SNOW -> {
                // SnowLayerBlock：SHAPES[layers-1]=box(0,0,0,16,(layers-1)*2,16)
                double height = ((data & 0xF) - 1) * 2.0;
                if (height > 0) {
                    addShapedBox(out, bx, by, bz, 0, 0, 0, 16, height, 16);
                }
            }
            case SHAPE_TRAPDOOR -> {
                // TrapDoorBlock：关 bottom=(0,0,0,16,3,16)；关 top=(0,13,0,16,16,16)；开=三面竖板
                if ((data & 1) != 0) {
                    switch ((data >> 2) & 3) {
                        case FACE_NORTH -> addShapedBox(out, bx, by, bz, 0, 0, 13, 16, 16, 16);
                        case FACE_SOUTH -> addShapedBox(out, bx, by, bz, 0, 0, 0, 16, 16, 3);
                        case FACE_WEST -> addShapedBox(out, bx, by, bz, 13, 0, 0, 16, 16, 16);
                        default -> addShapedBox(out, bx, by, bz, 0, 0, 0, 3, 16, 16);
                    }
                } else if ((data & 2) != 0) {
                    addShapedBox(out, bx, by, bz, 0, 13, 0, 16, 16, 16);
                } else {
                    addShapedBox(out, bx, by, bz, 0, 0, 0, 16, 3, 16);
                }
            }
            case SHAPE_LOW -> addShapedBox(out, bx, by, bz, 0, 0, 0, 16, 15, 16);
            case SHAPE_SOUL_SAND -> addShapedBox(out, bx, by, bz, 0, 0, 0, 16, 14, 16);
            case SHAPE_CHEST -> addShapedBox(out, bx, by, bz, 1, 0, 1, 15, 14, 15);
            case SHAPE_BED -> {
                // BedBlock：底座 column(16,3,9) + 北端两腿；key=腿所在端朝向
                int key = data & 3;
                addRotatedShapedBox(out, bx, by, bz, key, 0, 3, 0, 16, 9, 16);
                addRotatedShapedBox(out, bx, by, bz, key, 0, 0, 0, 3, 3, 3);
                addRotatedShapedBox(out, bx, by, bz, key, 13, 0, 0, 16, 3, 3);
            }
            case SHAPE_HOPPER -> {
                // HopperBlock：外壁（底板+四环壁）+ 喇叭口；出料口按 facing 旋转
                addShapedBox(out, bx, by, bz, 0, 10, 0, 16, 11, 16);
                addShapedBox(out, bx, by, bz, 0, 11, 0, 16, 16, 2);
                addShapedBox(out, bx, by, bz, 0, 11, 14, 16, 16, 16);
                addShapedBox(out, bx, by, bz, 0, 11, 2, 2, 16, 14);
                addShapedBox(out, bx, by, bz, 14, 11, 2, 16, 16, 14);
                addShapedBox(out, bx, by, bz, 4, 4, 4, 12, 10, 12);
                switch (data & 0x7) {
                    case 0 -> addShapedBox(out, bx, by, bz, 6, 0, 6, 10, 6, 10);
                    case 1 -> addShapedBox(out, bx, by, bz, 6, 4, 0, 10, 8, 8);
                    case 2 -> addShapedBox(out, bx, by, bz, 6, 4, 8, 10, 8, 16);
                    case 3 -> addShapedBox(out, bx, by, bz, 0, 4, 6, 8, 8, 10);
                    default -> addShapedBox(out, bx, by, bz, 8, 4, 6, 16, 8, 10);
                }
            }
            case SHAPE_CAULDRON -> {
                // AbstractCauldronBlock：block 减四壁投影 = 四角 L 形 2px 腿 + 地板 + 四面墙
                addShapedBox(out, bx, by, bz, 0, 0, 0, 4, 3, 2);
                addShapedBox(out, bx, by, bz, 0, 0, 0, 2, 3, 4);
                addShapedBox(out, bx, by, bz, 12, 0, 0, 16, 3, 2);
                addShapedBox(out, bx, by, bz, 14, 0, 0, 16, 3, 4);
                addShapedBox(out, bx, by, bz, 0, 0, 14, 4, 3, 16);
                addShapedBox(out, bx, by, bz, 0, 0, 12, 2, 3, 16);
                addShapedBox(out, bx, by, bz, 12, 0, 14, 16, 3, 16);
                addShapedBox(out, bx, by, bz, 14, 0, 12, 16, 3, 16);
                addShapedBox(out, bx, by, bz, 0, 3, 0, 16, 4, 16);
                addShapedBox(out, bx, by, bz, 0, 4, 0, 16, 16, 2);
                addShapedBox(out, bx, by, bz, 0, 4, 14, 16, 16, 16);
                addShapedBox(out, bx, by, bz, 0, 4, 2, 2, 16, 14);
                addShapedBox(out, bx, by, bz, 14, 4, 2, 16, 16, 14);
            }
            case SHAPE_ENCHANTING -> addShapedBox(out, bx, by, bz, 0, 0, 0, 16, 12, 16);
            case SHAPE_DAYLIGHT -> addShapedBox(out, bx, by, bz, 0, 0, 0, 16, 6, 16);
            case SHAPE_COMPOSTER -> {
                // ComposterBlock：block 减 column(12, clamp(1+level*2,2,16), 16)；level=8 时内腔满高（完整立方体）
                double w = Math.min(Math.max(1 + (data & 0xF) * 2, 2), 16);
                if (w >= 16) {
                    addShapedBox(out, bx, by, bz, 0, 0, 0, 16, 16, 16);
                } else {
                    addShapedBox(out, bx, by, bz, 0, 0, 0, 16, w, 16);
                    addShapedBox(out, bx, by, bz, 0, w, 0, 16, 16, 2);
                    addShapedBox(out, bx, by, bz, 0, w, 14, 16, 16, 16);
                    addShapedBox(out, bx, by, bz, 0, w, 2, 2, 16, 14);
                    addShapedBox(out, bx, by, bz, 14, w, 2, 16, 16, 14);
                }
            }
            case SHAPE_STONECUTTER -> addShapedBox(out, bx, by, bz, 0, 0, 0, 16, 9, 16);
            case SHAPE_BREWING -> {
                addShapedBox(out, bx, by, bz, 7, 2, 7, 9, 14, 9);
                addShapedBox(out, bx, by, bz, 1, 0, 1, 15, 2, 15);
            }
            case SHAPE_CAKE -> {
                // CakeBlock：SHAPES[bites]=box(1+bites*2,0,1,15,8,15)；candle_cake 加蜡烛桩
                int x = 1 + (data & 7) * 2;
                addShapedBox(out, bx, by, bz, x, 0, 1, 15, 8, 15);
                if ((data & 8) != 0) {
                    addShapedBox(out, bx, by, bz, 7, 8, 7, 9, 14, 9);
                }
            }
            case SHAPE_FLOWER_POT -> addShapedBox(out, bx, by, bz, 5, 0, 5, 11, 6, 11);
            case SHAPE_END_FRAME -> {
                // EndPortalFrameBlock：SHAPE=box(0,0,0,16,13,16)；有眼 + box(4,13,4,12,16,12)
                addShapedBox(out, bx, by, bz, 0, 0, 0, 16, 13, 16);
                if ((data & 1) != 0) {
                    addShapedBox(out, bx, by, bz, 4, 13, 4, 12, 16, 12);
                }
            }
            case SHAPE_DRAGON_EGG -> addShapedBox(out, bx, by, bz, 1, 0, 1, 15, 16, 15);
            case SHAPE_CAMPFIRE -> addShapedBox(out, bx, by, bz, 0, 0, 0, 16, 7, 16);
            case SHAPE_BIG_DRIPLEAF -> {
                // BigDripleafBlock.getCollisionShape = SHAPE_LEAF[tilt]：
                // none/unstable = column(16,11,15)；partial = column(16,11,13)；full 无碰撞
                double leafTop = switch (data & 3) {
                    case 2 -> 13.0;
                    case 3 -> 0.0;
                    default -> 15.0;
                };
                if (leafTop > 0.0) {
                    addShapedBox(out, bx, by, bz, 0, 11, 0, 16, leafTop, 16);
                }
            }
            case SHAPE_FENCE -> {
                // FenceBlock = CrossCollisionBlock(4,16,4,16,24)：中心柱 box(6,0,6,10,16,10)
                // + 每个连接方向上部横臂 box(6,16,0,10,24,8)（高 1.0..1.5）
                addShapedBox(out, bx, by, bz, 6, 0, 6, 10, 16, 10);
                if ((data & 1) != 0) {
                    addRotatedShapedBox(out, bx, by, bz, FACE_NORTH, 6, 16, 0, 10, 24, 8);
                }
                if ((data & 2) != 0) {
                    addRotatedShapedBox(out, bx, by, bz, FACE_EAST, 6, 16, 0, 10, 24, 8);
                }
                if ((data & 4) != 0) {
                    addRotatedShapedBox(out, bx, by, bz, FACE_SOUTH, 6, 16, 0, 10, 24, 8);
                }
                if ((data & 8) != 0) {
                    addRotatedShapedBox(out, bx, by, bz, FACE_WEST, 6, 16, 0, 10, 24, 8);
                }
            }
            case SHAPE_WALL -> {
                // WallBlock 碰撞形状 makeShapes(24,24)：up 时中心柱 box(4,0,4,12,24,12)
                // + 每侧（low/tall 碰撞等价）墙臂 box(5,0,0,11,24,11)；恒高 1.5
                if ((data & 0x10) != 0) {
                    addShapedBox(out, bx, by, bz, 4, 0, 4, 12, 24, 12);
                }
                if ((data & 1) != 0) {
                    addRotatedShapedBox(out, bx, by, bz, FACE_NORTH, 5, 0, 0, 11, 24, 11);
                }
                if ((data & 2) != 0) {
                    addRotatedShapedBox(out, bx, by, bz, FACE_EAST, 5, 0, 0, 11, 24, 11);
                }
                if ((data & 4) != 0) {
                    addRotatedShapedBox(out, bx, by, bz, FACE_SOUTH, 5, 0, 0, 11, 24, 11);
                }
                if ((data & 8) != 0) {
                    addRotatedShapedBox(out, bx, by, bz, FACE_WEST, 5, 0, 0, 11, 24, 11);
                }
            }
            case SHAPE_BAMBOO -> addShapedBox(out, bx, by, bz, 6.5, 0, 6.5, 9.5, 16, 9.5);
            case SHAPE_ANVIL -> {
                // AnvilBlock：rotateHorizontalAxis(or(column(12,0,4), column(8,10,4,5), column(4,8,5,10), column(10,16,10,16)))
                if ((data & 1) != 0) {
                    addShapedBox(out, bx, by, bz, 2, 0, 2, 14, 4, 14);
                    addShapedBox(out, bx, by, bz, 3, 4, 4, 13, 5, 12);
                    addShapedBox(out, bx, by, bz, 4, 5, 6, 12, 10, 10);
                    addShapedBox(out, bx, by, bz, 0, 10, 3, 16, 16, 13);
                } else {
                    addShapedBox(out, bx, by, bz, 2, 0, 2, 14, 4, 14);
                    addShapedBox(out, bx, by, bz, 4, 4, 3, 12, 5, 13);
                    addShapedBox(out, bx, by, bz, 6, 5, 4, 10, 10, 12);
                    addShapedBox(out, bx, by, bz, 3, 10, 0, 13, 16, 16);
                }
            }
            case SHAPE_CHAIN -> {
                // ChainBlock：cube(3,3,16) 按轴摆放（data bit0-1：0=x, 1=y, 2=z）
                switch (data & 3) {
                    case 0 -> addShapedBox(out, bx, by, bz, 0, 6.5, 6.5, 16, 9.5, 9.5);
                    case 2 -> addShapedBox(out, bx, by, bz, 6.5, 6.5, 0, 9.5, 9.5, 16);
                    default -> addShapedBox(out, bx, by, bz, 6.5, 0, 6.5, 9.5, 16, 9.5);
                }
            }
            case SHAPE_ROD -> {
                // RodBlock（end_rod/lightning_rod）：cube(4,4,16) 按轴摆放（data bit0-1：0=x, 1=y, 2=z）
                switch (data & 3) {
                    case 0 -> addShapedBox(out, bx, by, bz, 0, 6, 6, 16, 10, 10);
                    case 2 -> addShapedBox(out, bx, by, bz, 6, 6, 0, 10, 10, 16);
                    default -> addShapedBox(out, bx, by, bz, 6, 0, 6, 10, 16, 10);
                }
            }
            case SHAPE_LANTERN -> {
                // LanternBlock：standing=column(4,7,9)+column(6,0,7)；hanging 整体上移 1px
                double lanternDy = (data & 1) != 0 ? 1.0 : 0.0;
                addShapedBox(out, bx, by, bz, 5, lanternDy, 5, 11, 7 + lanternDy, 11);
                addShapedBox(out, bx, by, bz, 6, 7 + lanternDy, 6, 10, 9 + lanternDy, 10);
            }
            case SHAPE_SCAFFOLDING -> {
                // ScaffoldingBlock：近似官方 SHAPE_STABLE（玩家位于上方时的碰撞形状）——
                // 顶板 column(16,14,16) + 四角柱 box(0,0,0,2,16,2) 旋转四份
                addShapedBox(out, bx, by, bz, 0, 14, 0, 16, 16, 16);
                addShapedBox(out, bx, by, bz, 0, 0, 0, 2, 16, 2);
                addShapedBox(out, bx, by, bz, 14, 0, 0, 16, 16, 2);
                addShapedBox(out, bx, by, bz, 0, 0, 14, 2, 16, 16);
                addShapedBox(out, bx, by, bz, 14, 0, 14, 16, 16, 16);
            }
            case SHAPE_TURTLE_EGG -> {
                // TurtleEggBlock：eggs=1 → box(3,0,3,12,7,12)；eggs≥2 → column(14,0,7)
                if ((data & 7) <= 1) {
                    addShapedBox(out, bx, by, bz, 3, 0, 3, 12, 7, 12);
                } else {
                    addShapedBox(out, bx, by, bz, 1, 0, 1, 15, 7, 15);
                }
            }
            case SHAPE_CANDLE -> {
                // CandleBlock：SHAPES[candles-1]（1/2/3/4 支，data bit0-2）
                switch (data & 7) {
                    case 2 -> addShapedBox(out, bx, by, bz, 5, 0, 6, 11, 6, 9);
                    case 3 -> addShapedBox(out, bx, by, bz, 5, 0, 6, 10, 6, 11);
                    case 4 -> addShapedBox(out, bx, by, bz, 5, 0, 5, 11, 6, 10);
                    default -> addShapedBox(out, bx, by, bz, 7, 0, 7, 9, 6, 9);
                }
            }
            case SHAPE_DOOR -> {
                // DoorBlock：门板恒为竖直 3px（doorDirection = open ? 铰链旋转(facing) : facing）
                int facing = data & 3;
                boolean open = (data & 4) != 0;
                boolean rightHinge = (data & 8) != 0;
                int dir = open ? (rightHinge ? counterClockWise(facing) : clockWise(facing)) : facing;
                addDoorPanel(out, bx, by, bz, dir);
            }
            case SHAPE_FENCE_GATE -> {
                // FenceGateBlock：开启无碰撞；关闭为 4px 厚、高 1.5 的板（按 facing 轴取向）
                if ((data & 4) == 0) {
                    int facing = data & 3;
                    if (facing == FACE_NORTH || facing == FACE_SOUTH) {
                        addShapedBox(out, bx, by, bz, 0, 0, 6, 16, 24, 10);
                    } else {
                        addShapedBox(out, bx, by, bz, 6, 0, 0, 10, 24, 16);
                    }
                }
            }
            case SHAPE_PANE -> {
                // CrossCollisionBlock(2,16,2,16,16)：中心柱 + 各连接方向的横臂（高 16px）
                addShapedBox(out, bx, by, bz, 7, 0, 7, 9, 16, 9);
                if ((data & 1) != 0) {
                    addShapedBox(out, bx, by, bz, 7, 0, 0, 9, 16, 8);
                }
                if ((data & 2) != 0) {
                    addShapedBox(out, bx, by, bz, 8, 0, 7, 16, 16, 9);
                }
                if ((data & 4) != 0) {
                    addShapedBox(out, bx, by, bz, 7, 0, 8, 9, 16, 16);
                }
                if ((data & 8) != 0) {
                    addShapedBox(out, bx, by, bz, 0, 0, 7, 8, 16, 9);
                }
            }
            case SHAPE_DIODE -> addShapedBox(out, bx, by, bz, 0, 0, 0, 16, 2, 16);
            case SHAPE_CONDUIT -> addShapedBox(out, bx, by, bz, 5, 5, 5, 11, 11, 11);
            case SHAPE_SEA_PICKLE -> {
                switch (data < 1 ? 1 : data) {
                    case 2 -> addShapedBox(out, bx, by, bz, 3, 0, 3, 13, 6, 13);
                    case 3 -> addShapedBox(out, bx, by, bz, 2, 0, 2, 14, 6, 14);
                    case 4 -> addShapedBox(out, bx, by, bz, 2, 0, 2, 14, 7, 14);
                    default -> addShapedBox(out, bx, by, bz, 6, 0, 6, 10, 6, 10);
                }
            }
            case SHAPE_LECTERN -> {
                addShapedBox(out, bx, by, bz, 0, 0, 0, 16, 2, 16);
                addShapedBox(out, bx, by, bz, 4, 2, 4, 12, 14, 12);
            }
            case SHAPE_DECORATED_POT -> addShapedBox(out, bx, by, bz, 1, 0, 1, 15, 16, 14);
            case SHAPE_SCULK_SENSOR -> addShapedBox(out, bx, by, bz, 0, 0, 0, 16, 8, 16);
            default -> {
            }
        }
    }

    /**
     * 竖直 3px 门板（px 单位）：对应当方 boxZ(16,13,16) 绕方块中心水平旋转后的盒，
     * 面板位于 dir 的对侧（dir 编码同 {@link #FACE_NORTH} 等，与活板门开启态一致）。
     */
    private static void addDoorPanel(List<AABB> out, int bx, int by, int bz, int dir) {
        switch (dir) {
            case FACE_NORTH -> addShapedBox(out, bx, by, bz, 0, 0, 13, 16, 16, 16);
            case FACE_SOUTH -> addShapedBox(out, bx, by, bz, 0, 0, 0, 16, 16, 3);
            case FACE_WEST -> addShapedBox(out, bx, by, bz, 13, 0, 0, 16, 16, 16);
            default -> addShapedBox(out, bx, by, bz, 0, 0, 0, 3, 16, 16);
        }
    }

    /** 把 px 单位的盒（相对方块原点）加入碰撞盒列表 */
    private static void addShapedBox(List<AABB> out, int bx, int by, int bz,
                                     double x0, double y0, double z0, double x1, double y1, double z1) {
        out.add(new AABB(bx + x0 / 16.0, by + y0 / 16.0, bz + z0 / 16.0,
                bx + x1 / 16.0, by + y1 / 16.0, bz + z1 / 16.0));
    }

    /**
     * 按 key（0=north, 1=south, 2=west, 3=east）绕方块中心水平旋转后加入碰撞盒（px 单位）。
     *
     * <p>旋转映射对齐 {@code Shapes.rotateHorizontal}：rotY90 (x,z)→(1-z,x)、
     * rotY180 (x,z)→(1-x,1-z)、rotY270 (x,z)→(z,1-x)。</p>
     */
    private static void addRotatedShapedBox(List<AABB> out, int bx, int by, int bz, int key,
                                            double x0, double y0, double z0, double x1, double y1, double z1) {
        double nx0;
        double nz0;
        double nx1;
        double nz1;
        switch (key) {
            case FACE_SOUTH -> {
                nx0 = 16.0 - x1;
                nz0 = 16.0 - z1;
                nx1 = 16.0 - x0;
                nz1 = 16.0 - z0;
            }
            case FACE_WEST -> {
                nx0 = z0;
                nz0 = 16.0 - x1;
                nx1 = z1;
                nz1 = 16.0 - x0;
            }
            case FACE_EAST -> {
                nx0 = 16.0 - z1;
                nz0 = x0;
                nx1 = 16.0 - z0;
                nz1 = x1;
            }
            default -> {
                nx0 = x0;
                nz0 = z0;
                nx1 = x1;
                nz1 = z1;
            }
        }
        addShapedBox(out, bx, by, bz, nx0, y0, nz0, nx1, y1, nz1);
    }

    /** 查询精确形状族；表外或非特殊形状返回 {@link #SHAPE_NONE} */
    private byte shapeKind(int stateId) {
        if (stateId < 0 || stateId >= shapeKindByStateId.length) return SHAPE_NONE;
        return shapeKindByStateId[stateId];
    }

    /** 查询精确形状族的状态数据；表外返回 0 */
    private byte shapeData(int stateId) {
        if (stateId < 0 || stateId >= shapeDataByStateId.length) return 0;
        return shapeDataByStateId[stateId];
    }

    /** 水平朝向的顺时针（对齐 Direction.getClockWise：N→E→S→W→N） */
    private static int clockWise(int facing) {
        return switch (facing) {
            case FACE_NORTH -> FACE_EAST;
            case FACE_EAST -> FACE_SOUTH;
            case FACE_SOUTH -> FACE_WEST;
            default -> FACE_NORTH;
        };
    }

    /** 水平朝向的逆时针（对齐 Direction.getCounterClockWise：N→W→S→E→N） */
    private static int counterClockWise(int facing) {
        return switch (facing) {
            case FACE_NORTH -> FACE_WEST;
            case FACE_WEST -> FACE_SOUTH;
            case FACE_SOUTH -> FACE_EAST;
            default -> FACE_NORTH;
        };
    }

    /** 查询半砖类型（bottom/top/double）；非半砖或表外返回 {@link #SLAB_NONE} */
    private byte slabData(int stateId) {
        if (stateId < 0 || stateId >= slabDataByStateId.length) return SLAB_NONE;
        return slabDataByStateId[stateId];
    }

    /** 查询楼梯状态编码；非楼梯或表外返回 {@link #STAIR_NONE} */
    private byte stairData(int stateId) {
        if (stateId < 0 || stateId >= stairDataByStateId.length) return STAIR_NONE;
        return stairDataByStateId[stateId];
    }

    private byte boxTypeOf(int stateId) {
        if (stateId < 0 || stateId >= boxTypesByStateId.length) return BOX_UNKNOWN;
        return boxTypesByStateId[stateId];
    }

    /**
     * 是否完全无碰撞形状（对齐原版 {@code getCollisionShape()} 返回 {@code Shapes.empty()}）。
     *
     * <p>两路信号：名称启发式非固体；blocks.json 的 {@code boundingBox=empty}
     * ——该字段反映方块默认状态的碰撞形状是否空，恰好对应原版 {@code Properties.noCollision()}
     * （压力板/按钮等）。注意它只能表达“有无碰撞”，不能区分整格立方体与薄形状
     * （stone_slab、glass_pane 同样标 block），因此仅用作零碰撞的短路信号。</p>
     *
     * <p>snow 例外：其默认 1 层无碰撞，但 2..8 层有实际高度，必须走 {@link #SHAPE_SNOW} 分支。</p>
     *
     * <p>权威表可用时直接以其盒数为准（下列启发式仅作为兼容兜底）。</p>
     */
    private boolean isEmptyCollision(int stateId) {
        CollisionShapes shapes = CollisionShapes.get();
        if (shapes != null && shapes.covers(stateId)) {
            return shapes.isEmpty(stateId);
        }
        if (stateId == 0) {
            return true;
        }
        String name = nameOf(stateId);
        if (name == null) {
            return boxTypeOf(stateId) == BOX_EMPTY;
        }
        if (isNonSolidName(name)) {
            return true;
        }
        return boxTypeOf(stateId) == BOX_EMPTY && shapeKind(stateId) != SHAPE_SNOW;
    }

    /** 名称启发式：是否明确为无碰撞方块 */
    private static boolean isNonSolidName(String name) {
        // 花盆变体（potted_*）拥有花盆碰撞，不能被 _sapling 等后缀误判为无碰撞
        if (name.startsWith("potted_")) {
            return false;
        }
        if (NON_SOLID_NAMES.contains(name)) {
            return true;
        }
        for (String suffix : NON_SOLID_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
