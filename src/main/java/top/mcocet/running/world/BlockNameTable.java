package top.mcocet.running.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.math.AABB;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 方块状态 ID → 方块名称 / 碰撞属性 / 摩擦系数的查询表。
 *
 * <p>数据来源为可选的 blocks.json（与服务器版本对应的
 * PrismarineJS minecraft-data 格式，放置于工作目录下）。
 * 未提供该文件时，命令将只显示方块状态 ID，碰撞查询退化为
 * “非空气即完整方块”的默认规则。</p>
 *
 * <p>支持两种条目结构：
 * <pre>
 * {"name": "stone", "minStateId": 1, "maxStateId": 1}
 * {"name": "stone", "minStateId": 1, "states": [ ... ]}
 * </pre></p>
 *
 * <p>碰撞模型为简化近似（官方为精确 VoxelShape）：
 * <ul>
 *   <li>blocks.json 的 {@code boundingBox} 字段（"block"/"empty"）优先；</li>
 *   <li>名称启发式：明确的非固体方块（空气/流体/植物/火把/铁轨等）
 *       视为无碰撞；{@code _slab} 半砖按 0.5 高、栅栏/墙/栅栏门按 1.5 高近似；</li>
 *   <li>楼梯在 blocks.json 提供状态属性时按官方双盒建模
 *       （底部半砖 + facing 方向台阶半砖），否则退化为完整立方体；</li>
 *   <li>其余非空气方块按完整立方体近似。</li>
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

    private static final byte BOX_UNKNOWN = 0;
    private static final byte BOX_BLOCK = 1;
    private static final byte BOX_EMPTY = 2;

    /** 明确的非固体方块（无碰撞盒）名称——启发式规则，可能不完整 */
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
            "fire", "soul_fire", "snow", "vine", "glow_lichen", "sculk_vein",
            "rail", "powered_rail", "detector_rail", "activator_rail", "lily_pad",
            "wheat", "carrots", "potatoes", "beetroots",
            "melon_stem", "pumpkin_stem", "attached_melon_stem", "attached_pumpkin_stem",
            "torchflower_crop", "pitcher_crop",
            "cave_vines", "cave_vines_plant", "weeping_vines", "weeping_vines_plant",
            "twisting_vines", "twisting_vines_plant", "hanging_roots",
            "moss_carpet", "light", "structure_void", "barrier", "moving_piston");

    /** 非固体后缀规则（覆盖前缀变体，如 16 色旗帜、各种树苗） */
    private static final String[] NON_SOLID_SUFFIXES = {
            "_sapling", "_button", "_pressure_plate", "_carpet", "_banner",
            "_torch", "_coral_fan", "_wall_fan", "_trapdoor"};

    private final String[] namesByStateId;
    /** 来自 blocks.json 的 boundingBox 类型（可能为 UNKNOWN） */
    private final byte[] boxTypesByStateId;
    /** 楼梯状态编码（facing | half&lt;&lt;2 | shape&lt;&lt;3）；非楼梯为 {@link #STAIR_NONE} */
    private final byte[] stairDataByStateId;

    private BlockNameTable(String[] namesByStateId, byte[] boxTypesByStateId, byte[] stairDataByStateId) {
        this.namesByStateId = namesByStateId;
        this.boxTypesByStateId = boxTypesByStateId;
        this.stairDataByStateId = stairDataByStateId;
    }

    /** 空表：名称查询返回 null，碰撞查询退化到默认规则 */
    public static BlockNameTable empty() {
        return new BlockNameTable(new String[0], new byte[0], new byte[0]);
    }

    /** 从文件加载；文件不存在或解析失败时返回空表 */
    public static BlockNameTable load(File file) {
        if (file == null || !file.isFile()) return empty();

        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray blocks = root.getAsJsonArray("blocks");
            if (blocks == null || blocks.isEmpty()) {
                log.warn("[Running] {} 中缺少 blocks 数组，已忽略。", file.getName());
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

            // 第二遍：填充名称、碰撞类型与楼梯状态
            String[] names = new String[maxStateId + 1];
            byte[] boxTypes = new byte[maxStateId + 1];
            byte[] stairData = new byte[maxStateId + 1];
            Arrays.fill(stairData, STAIR_NONE);
            for (JsonElement element : blocks) {
                JsonObject block = element.getAsJsonObject();
                String name = block.get("name").getAsString();
                int min = block.get("minStateId").getAsInt();
                int count = stateCount(block);
                byte boxType = readBoxType(block);
                byte[] stairs = parseStairData(name, block, count);
                for (int i = 0; i < count && min + i < names.length; i++) {
                    names[min + i] = name;
                    boxTypes[min + i] = boxType;
                    if (stairs != null) {
                        stairData[min + i] = stairs[i];
                    }
                }
            }
            return new BlockNameTable(names, boxTypes, stairData);
        } catch (Exception e) {
            log.warn("[Running] 加载方块名称表失败: {}", e.toString());
            return empty();
        }
    }

    /**
     * 解析楼梯方块的 facing/half/shape 状态（来自 blocks.json 的 states 属性定义）。
     *
     * <p>状态 ID 相对 minStateId 的偏移按属性笛卡尔积分解（数组顺序即游戏内
     * 定义顺序，最后一个属性变化最快）。任一校验失败（缺少属性/维数不符/
     * 组合数不匹配）返回 null，对应方块退化为单盒近似。</p>
     *
     * @return 每个子状态的编码（facing | half&lt;&lt;2 | shape&lt;&lt;3），或 null
     */
    private static byte[] parseStairData(String name, JsonObject block, int count) {
        if (!name.endsWith("_stairs")) {
            return null;
        }
        JsonElement statesElement = block.get("states");
        if (statesElement == null || !statesElement.isJsonArray()) {
            return null;
        }

        JsonArray states = statesElement.getAsJsonArray();
        int dimCount = states.size();
        int[] sizes = new int[dimCount];
        String[][] valueNames = new String[dimCount][];
        int facingDim = -1;
        int halfDim = -1;
        int shapeDim = -1;
        for (int d = 0; d < dimCount; d++) {
            JsonObject state = states.get(d).getAsJsonObject();
            JsonElement valuesElement = state.get("values");
            if (valuesElement == null || !valuesElement.isJsonArray()) {
                return null;
            }
            JsonArray values = valuesElement.getAsJsonArray();
            sizes[d] = values.size();
            valueNames[d] = new String[sizes[d]];
            for (int v = 0; v < sizes[d]; v++) {
                valueNames[d][v] = values.get(v).getAsString();
            }
            String stateName = state.get("name").getAsString();
            if ("facing".equals(stateName) && sizes[d] == 4) {
                facingDim = d;
            } else if ("half".equals(stateName) && sizes[d] == 2) {
                halfDim = d;
            } else if ("shape".equals(stateName) && sizes[d] == 5) {
                shapeDim = d;
            }
        }
        if (facingDim < 0 || halfDim < 0 || shapeDim < 0) {
            return null;
        }

        int[] strides = new int[dimCount];
        int stride = 1;
        for (int d = dimCount - 1; d >= 0; d--) {
            strides[d] = stride;
            stride *= sizes[d];
        }
        if (stride != count) {
            return null;
        }

        byte[] result = new byte[count];
        for (int i = 0; i < count; i++) {
            int facing = facingCode(valueNames[facingDim][i / strides[facingDim] % sizes[facingDim]]);
            if (facing < 0) {
                return null;
            }
            int half = "top".equals(valueNames[halfDim][i / strides[halfDim] % sizes[halfDim]]) ? 1 : 0;
            int shape = "straight".equals(valueNames[shapeDim][i / strides[shapeDim] % sizes[shapeDim]]) ? 0 : 1;
            result[i] = (byte) (facing | (half << 2) | (shape << 3));
        }
        return result;
    }

    /** 水平朝向名称 → 编码（0=north, 1=south, 2=west, 3=east） */
    private static int facingCode(String name) {
        return switch (name) {
            case "north" -> 0;
            case "south" -> 1;
            case "west" -> 2;
            case "east" -> 3;
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
        if (stateId < 0 || stateId >= namesByStateId.length) return null;
        return namesByStateId[stateId];
    }

    // ------------------------------------------------------------------
    // 碰撞与摩擦查询（供 PlayerPhysics 使用）
    // ------------------------------------------------------------------

    /**
     * 查询方块碰撞盒高度（相对方块底部的偏移量）。
     *
     * <p>简化模型：0 = 无碰撞（可穿过）；0.5 = 半砖近似；1.0 = 完整立方体。
     * 楼梯/栅栏/墙等复杂形状均按 1.0 近似；状态 ID 0 始终视为空气。</p>
     *
     * @return 碰撞高度；无碰撞返回 0
     */
    public double collisionHeight(int stateId) {
        if (stateId == 0) {
            return 0.0;
        }

        String name = nameOf(stateId);
        if (name != null) {
            if (isNonSolidName(name)) {
                return 0.0;
            }
            if (name.endsWith("_slab") || name.endsWith("_cake")) {
                return HEIGHT_SLAB;
            }
            if (name.endsWith("_fence") || name.endsWith("_fence_gate") || name.endsWith("_wall")) {
                return HEIGHT_FENCE;
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

    /**
     * 是否窒息方块（近似官方 {@code isSuffocating}：碰撞形状为完整立方体
     * 且非玻璃/树叶等特例），用于玩家的碰撞脱困推力判定。
     */
    public boolean suffocates(int stateId) {
        String name = nameOf(stateId);
        if (name == null || stateId == 0 || isNonSolidName(name)) {
            return false;
        }
        if (name.endsWith("_stairs") || name.endsWith("glass") || name.endsWith("_leaves")
                || "mangrove_roots".equals(name) || "moving_piston".equals(name)) {
            return false;
        }
        return collisionHeight(stateId) == HEIGHT_FULL;
    }

    /**
     * 生成方块碰撞盒（0 至 2 个），供碰撞解算收集。
     *
     * <p>楼梯在拥有状态数据时按官方双盒生成：底部半砖（整格）+ 台阶半砖
     * （facing 方向的半格）；内角/外角变体按直形近似。其余方块按
     * {@link #collisionHeight} 的单盒启发式。</p>
     */
    public void addCollisionBoxes(int stateId, int bx, int by, int bz, List<AABB> out) {
        byte stair = stairData(stateId);
        if (stair != STAIR_NONE) {
            boolean top = ((stair >> 2) & 0x1) != 0;
            double baseY = top ? 0.5 : 0.0;
            double stepY = top ? 0.0 : 0.5;
            out.add(new AABB(bx, by + baseY, bz, bx + 1.0, by + baseY + 0.5, bz + 1.0));
            double x0 = bx;
            double x1 = bx + 1.0;
            double z0 = bz;
            double z1 = bz + 1.0;
            switch (stair & 0x3) {
                case 0 -> z1 = bz + 0.5;   // facing=north：台阶在北半格
                case 1 -> z0 = bz + 0.5;   // facing=south
                case 2 -> x1 = bx + 0.5;   // facing=west
                case 3 -> x0 = bx + 0.5;   // facing=east
                default -> { }
            }
            out.add(new AABB(x0, by + stepY, z0, x1, by + stepY + 0.5, z1));
            return;
        }
        double height = collisionHeight(stateId);
        if (height > 0.0) {
            out.add(new AABB(bx, by, bz, bx + 1.0, by + height, bz + 1.0));
        }
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

    /** 名称启发式：是否明确为无碰撞方块 */
    private static boolean isNonSolidName(String name) {
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
