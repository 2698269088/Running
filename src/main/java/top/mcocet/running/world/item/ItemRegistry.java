package top.mcocet.running.world.item;

import net.kyori.adventure.key.Key;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.world.BlockNameTable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从配置阶段的 {@code minecraft:item} / {@code minecraft:block} 注册表解析出的物品工具模型。
 *
 * <p>网络注册的 numeric id 即条目在列表中的下标；{@link RegistryEntry} 携带资源名与元素
 * NBT（内含 {@code components -> minecraft:tool}）。本类把工具的 rules/default_mining_speed
 * 解析成 {@link ToolModel}，供 {@code MiningCalculator} 忠实计算挖掘速度。</p>
 *
 * <p>注册表在配置阶段一次性下发、之后只读，故解析完成后整体替换一个不可变的快照引用；
 * 计算线程读、网络线程写用 volatile 保证可见性与原子发布。</p>
 */
public final class ItemRegistry {
    private static final Logger log = LoggerFactory.getLogger(ItemRegistry.class.getSimpleName());

    private static final String REGISTRY_ITEM = "item";
    private static final String REGISTRY_BLOCK = "block";
    private static final String COMPONENTS = "components";
    private static final String TOOL_COMPONENT = "minecraft:tool";

    /** item numeric id → 去命名空间名（如 diamond_pickaxe） */
    private volatile Map<Integer, String> itemNameById = Map.of();
    /** block numeric id → 去命名空间名（解析工具规则内联方块 ID 列表用） */
    private volatile Map<Integer, String> blockNameById = Map.of();
    /** item 名 → 工具模型（无 tool 组件的物品不入表） */
    private volatile Map<String, ToolModel> toolByName = Map.of();
    /** 解析统计（诊断用） */
    private volatile int itemCount;
    private volatile int toolCount;

    /** 处理一个注册表数据包；非 item/block 注册表忽略。 */
    public synchronized void apply(ClientboundRegistryDataPacket packet) {
        Key registry = packet.getRegistry();
        if (registry == null) {
            return;
        }
        String type = registry.value();
        if (REGISTRY_ITEM.equals(type)) {
            parseItemRegistry(packet.getEntries());
        } else if (REGISTRY_BLOCK.equals(type)) {
            parseBlockRegistry(packet.getEntries());
        }
    }

    private void parseItemRegistry(List<RegistryEntry> entries) {
        Map<Integer, String> names = new HashMap<>();
        Map<String, ToolModel> tools = new HashMap<>();
        for (int id = 0; id < entries.size(); id++) {
            RegistryEntry entry = entries.get(id);
            if (entry.getId() == null) {
                continue;
            }
            String name = stripNamespace(entry.getId().toString());
            names.put(id, name);
            NbtMap data = entry.getData();
            if (data == null) {
                continue;
            }
            try {
                ToolModel tool = parseTool(data, blockNameById);
                if (tool != null) {
                    tools.put(name, tool);
                }
            } catch (Exception e) {
                log.debug("[Running] 解析物品 {} 的 tool 组件失败: {}", name, e.toString());
            }
        }
        this.itemNameById = names;
        this.toolByName = tools;
        this.itemCount = names.size();
        this.toolCount = tools.size();
        log.info("[Running] 物品注册表已解析：{} 个物品，{} 个带工具组件。", itemCount, toolCount);
    }

    private void parseBlockRegistry(List<RegistryEntry> entries) {
        Map<Integer, String> names = new HashMap<>();
        for (int id = 0; id < entries.size(); id++) {
            RegistryEntry entry = entries.get(id);
            if (entry.getId() != null) {
                names.put(id, stripNamespace(entry.getId().toString()));
            }
        }
        this.blockNameById = names;
    }

    /** 由物品元素 NBT 解析 {@code minecraft:tool} 组件；无该组件返回 null。 */
    private static ToolModel parseTool(NbtMap itemElement, Map<Integer, String> blockNames) {
        if (!itemElement.containsKey(COMPONENTS)) {
            return null;
        }
        NbtMap components = itemElement.getCompound(COMPONENTS);
        if (components == null || !components.containsKey(TOOL_COMPONENT)) {
            return null;
        }
        NbtMap tool = components.getCompound(TOOL_COMPONENT);
        List<ToolModel.Rule> rules = new ArrayList<>();
        if (tool.containsKey("rules")) {
            List<NbtMap> rulesList = tool.getList("rules", NbtType.COMPOUND);
            for (NbtMap ruleMap : rulesList) {
                ToolModel.Rule rule = parseRule(ruleMap, blockNames);
                if (rule != null) {
                    rules.add(rule);
                }
            }
        }
        float defaultSpeed = tool.containsKey("default_mining_speed") ? tool.getFloat("default_mining_speed") : 1.0F;
        return new ToolModel(rules, defaultSpeed);
    }

    private static ToolModel.Rule parseRule(NbtMap ruleMap, Map<Integer, String> blockNames) {
        ToolModel.Match match = parseHolderSet(ruleMap.get("blocks"), blockNames);
        Float speed = ruleMap.containsKey("speed") ? ruleMap.getFloat("speed") : null;
        Boolean correct = ruleMap.containsKey("correct_for_drops")
                ? ruleMap.getByte("correct_for_drops") != 0 : null;
        return new ToolModel.Rule(match, speed, correct);
    }

    /** 解析 HolderSet：标签（{@code #...}）→ 采集族匹配；内联列表/单名 → 名称集合匹配。 */
    private static ToolModel.Match parseHolderSet(Object blocks, Map<Integer, String> blockNames) {
        if (blocks == null) {
            return ToolModel.never();
        }
        if (blocks instanceof String s) {
            return fromSingleReference(s, blockNames);
        }
        if (blocks instanceof List<?> list) {
            Set<String> names = new HashSet<>();
            byte category = BlockNameTable.TOOL_NONE;
            boolean isTagOnly = false;
            for (Object o : list) {
                if (o instanceof String ref) {
                    String r = ref;
                    if (r.startsWith("#")) {
                        byte cat = mineableCategory(r);
                        if (cat != BlockNameTable.TOOL_NONE) {
                            category = cat;
                            isTagOnly = true;
                        }
                    } else {
                        names.add(stripNamespace(r));
                    }
                } else if (o instanceof Number n) {
                    String name = blockNames.get(n.intValue());
                    if (name != null) {
                        names.add(name);
                    }
                }
            }
            if (isTagOnly && names.isEmpty()) {
                return ToolModel.categoryMatch(category);
            }
            return names.isEmpty() ? ToolModel.never() : ToolModel.namesMatch(names);
        }
        if (blocks instanceof Number n) {
            String name = blockNames.get(n.intValue());
            return name == null ? ToolModel.never() : ToolModel.namesMatch(Set.of(name));
        }
        return ToolModel.never();
    }

    private static ToolModel.Match fromSingleReference(String ref, Map<Integer, String> blockNames) {
        if (ref.startsWith("#")) {
            byte cat = mineableCategory(ref);
            return cat == BlockNameTable.TOOL_NONE ? ToolModel.never() : ToolModel.categoryMatch(cat);
        }
        return ToolModel.namesMatch(Set.of(stripNamespace(ref)));
    }

    /** {@code #minecraft:mineable/<x>} → {@code BlockNameTable.TOOL_*}；非 mineable 标签返回 NONE。 */
    private static byte mineableCategory(String tag) {
        int idx = tag.indexOf("mineable/");
        if (idx < 0) {
            return BlockNameTable.TOOL_NONE;
        }
        return switch (tag.substring(idx + "mineable/".length())) {
            case "pickaxe" -> BlockNameTable.TOOL_PICKAXE;
            case "axe" -> BlockNameTable.TOOL_AXE;
            case "shovel" -> BlockNameTable.TOOL_SHOVEL;
            case "hoe" -> BlockNameTable.TOOL_HOE;
            default -> BlockNameTable.TOOL_NONE;
        };
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

    /** 物品名 → 工具模型；非工具返回 null。 */
    public ToolModel toolOf(String itemName) {
        return itemName == null ? null : toolByName.get(itemName);
    }

    /** 物品 numeric id → 工具模型；非工具/未知返回 null（即徒手速度）。 */
    public ToolModel toolOfItem(int itemId) {
        return toolOf(itemName(itemId));
    }

    /** 注册表是否已解析出物品（用于诊断/降级判定）。 */
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
