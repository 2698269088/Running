package top.mcocet.running.world.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.world.BlockNameTable;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 物品数字 ID → 名称，以及工具名 → 挖掘工具模型（{@link ToolModel}）的只读表。
 *
 * <p><b>数据来源是打包的 {@code items.json}</b>（PrismarineJS minecraft-data 的 Java 版物品表，
 * 与 {@code blocks.json} 同版本、同数字 ID 编号空间）。之所以不从网络注册表解析：
 * {@code minecraft:item}/{@code minecraft:block} 是内置注册表（built-in registry），
 * 服务器在配置阶段<b>从不下发</b>，{@code ClientboundRegistryDataPacket} 只承载动态注册表
 * （biome/enchantment/… ）。因此物品数字 ID 只能来自与服务器版本一致的本地数据表。</p>
 *
 * <p>而挖掘<b>速度</b>并不在 minecraft-data 的任何 json 里（{@code items.json} 只有 ID/名称/堆叠），
 * 它是原版代码的 {@code minecraft:tool} 数据组件，定义在
 * {@code net.minecraft.world.item.ToolMaterial} 与各工具类的 {@code createToolProperties()} 中。
 * 本类按工作区反编译源码把这些常量固化为 {@link #TIER_SPEED}，并结合工具名后缀映射采集族，
 * 重建出与原版语义一致的 {@link ToolModel}（对齐 {@code Tool.Rule.minesAndDrops(minesEfficiently, speed)}：
 * 对匹配采集族的方块使用该档速度并视为正确工具，其余方块速度为默认 1.0）。</p>
 *
 * <p>表在插件 {@code load()} 阶段一次性构建、之后只读，故字段为 final 快照。读侧无锁。
 * 未建模的物品（剑/剪刀/徒手等）{@link #toolOfItem(int)} 返回 {@code null}，
 * 由 {@code MiningCalculator} 退化为徒手速度 1.0（保守、安全）。</p>
 */
public final class ItemRegistry {
    private static final Logger log = LoggerFactory.getLogger(ItemRegistry.class.getSimpleName());

    /** 内置数据资源候选路径（打包进 jar；优先命名空间路径，兼容类根目录） */
    private static final String[] EMBEDDED_RESOURCES = {
            "/top/mcocet/running/items.json", "/items.json"};

    /**
     * 工具档次 → 挖掘速度（{@code defaultMiningSpeed} 之外的有效速度）。
     * 取自反编译源码 {@code net.minecraft.world.item.ToolMaterial}（1.21.x / 26.x 一致）：
     * WOOD=2 STONE=4 COPPER=5 IRON=6 DIAMOND=8 GOLD=12 NETHERITE=9。
     * 键为工具名前缀（金镐在 minecraft-data 中命名为 {@code golden}）。
     */
    private static final Map<String, Float> TIER_SPEED = Map.of(
            "wooden", 2.0F,
            "stone", 4.0F,
            "copper", 5.0F,
            "iron", 6.0F,
            "golden", 12.0F,
            "diamond", 8.0F,
            "netherite", 9.0F);

    /** 工具类型后缀 → 方块采集族（{@link BlockNameTable#TOOL_PICKAXE} 等）。 */
    private static final Map<String, Byte> TOOL_TYPE = Map.of(
            "pickaxe", BlockNameTable.TOOL_PICKAXE,
            "axe", BlockNameTable.TOOL_AXE,
            "shovel", BlockNameTable.TOOL_SHOVEL,
            "hoe", BlockNameTable.TOOL_HOE);

    /** item numeric id → 去命名空间名（如 diamond_pickaxe） */
    private final Map<Integer, String> itemNameById;
    /** item 名 → 工具模型（仅建模的挖掘工具入表） */
    private final Map<String, ToolModel> toolByName;
    private final int itemCount;
    private final int toolCount;

    private ItemRegistry(Map<Integer, String> itemNameById, Map<String, ToolModel> toolByName, int itemCount) {
        this.itemNameById = itemNameById;
        this.toolByName = toolByName;
        this.itemCount = itemCount;
        this.toolCount = toolByName.size();
    }

    /** 空表：所有查询退化（无物品映射、所有物品按徒手处理）。 */
    public static ItemRegistry empty() {
        return new ItemRegistry(Map.of(), Map.of(), 0);
    }

    /**
     * 默认加载：优先工作目录外部文件（便于按服务器版本覆盖），
     * 缺失时回退到插件内置资源（jar 内 items.json）。两者皆不可用返回空表。
     */
    public static ItemRegistry loadDefault(File external) {
        if (external != null && external.isFile()) {
            return load(external);
        }
        for (String path : EMBEDDED_RESOURCES) {
            try (InputStream in = ItemRegistry.class.getResourceAsStream(path)) {
                if (in == null) {
                    continue;
                }
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    return parse(JsonParser.parseReader(reader), "内置资源 " + path);
                }
            } catch (Exception e) {
                log.warn("[Running] 解析内置物品表失败: {}", e.toString());
                return empty();
            }
        }
        log.warn("[Running] 未找到内置物品表资源（{}）。挖掘工具将退化为徒手速度。",
                String.join(", ", EMBEDDED_RESOURCES));
        return empty();
    }

    /** 从文件加载；文件不存在或解析失败返回空表。 */
    public static ItemRegistry load(File file) {
        if (file == null || !file.isFile()) {
            return empty();
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            return parse(JsonParser.parseReader(reader), "工作目录 " + file.getName());
        } catch (Exception e) {
            log.warn("[Running] 加载物品表失败: {}", e.toString());
            return empty();
        }
    }

    /** 解析 items.json 根（顶层数组或 {@code {"item": [...]}} 封装）并构建查询表。 */
    private static ItemRegistry parse(JsonElement root, String source) {
        JsonArray items;
        if (root != null && root.isJsonArray()) {
            items = root.getAsJsonArray();
        } else if (root != null && root.isJsonObject()) {
            items = root.getAsJsonObject().getAsJsonArray("item");
            if (items == null) {
                items = root.getAsJsonObject().getAsJsonArray("items");
            }
        } else {
            items = null;
        }
        if (items == null || items.isEmpty()) {
            log.warn("[Running] {} 中缺少 item 数组，已忽略。", source);
            return empty();
        }

        Map<Integer, String> names = new HashMap<>();
        Map<String, ToolModel> tools = new HashMap<>();
        for (JsonElement element : items) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            JsonElement idElement = item.get("id");
            JsonElement nameElement = item.get("name");
            if (idElement == null || nameElement == null || !nameElement.isJsonPrimitive()) {
                continue;
            }
            int id = idElement.getAsInt();
            String name = stripNamespace(nameElement.getAsString());
            names.put(id, name);
            ToolModel model = buildToolModel(name);
            if (model != null) {
                tools.put(name, model);
            }
        }
        log.info("[Running] 物品表已加载：{}（{} 个物品，{} 个建模挖掘工具）。",
                source, names.size(), tools.size());
        return new ItemRegistry(names, tools, names.size());
    }

    /**
     * 由工具名重建 {@link ToolModel}：前缀判档速、后缀判采集族。
     * 非 {@code <tier>_<type>} 形式的挖掘工具（剑/剪刀/方块等）返回 {@code null}。
     */
    private static ToolModel buildToolModel(String name) {
        int underscore = name.lastIndexOf('_');
        if (underscore <= 0 || underscore == name.length() - 1) {
            return null;
        }
        String tier = name.substring(0, underscore);
        String type = name.substring(underscore + 1);
        Float speed = TIER_SPEED.get(tier);
        Byte category = TOOL_TYPE.get(type);
        if (speed == null || category == null) {
            return null;
        }
        // 对齐 Tool.Rule.minesAndDrops(minesEfficiently, speed)：命中该采集族→该档速度且视为正确工具；
        // 其余方块用 defaultMiningSpeed=1.0（原版对 mineable 之外方块即为此语义）。
        ToolModel.Rule rule = new ToolModel.Rule(ToolModel.categoryMatch(category), speed, Boolean.TRUE);
        return new ToolModel(List.of(rule), 1.0F);
    }

    private static String stripNamespace(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 物品 numeric id → 名称；未知返回 null。 */
    public String itemName(int itemId) {
        return itemNameById.get(itemId);
    }

    /** 物品名 → 工具模型；非工具/未建模返回 null（即徒手速度）。 */
    public ToolModel toolOf(String itemName) {
        return itemName == null ? null : toolByName.get(itemName);
    }

    /** 物品 numeric id → 工具模型；非工具/未知返回 null（即徒手速度）。 */
    public ToolModel toolOfItem(int itemId) {
        return toolOf(itemName(itemId));
    }

    /** 物品表是否已加载（用于诊断/降级判定）。 */
    public boolean isReady() {
        return itemCount > 0;
    }

    public int itemCount() {
        return itemCount;
    }

    public int toolCount() {
        return toolCount;
    }
}
