package top.mcocet.running.world.item;

import top.mcocet.running.world.BlockNameTable;

import java.util.List;

/**
 * 物品 {@code minecraft:tool} 数据组件的模型，忠实复刻原版
 * {@code net.minecraft.world.item.component.Tool}（1.21.4）的挖掘速度与正确工具判定。
 *
 * <p>原版语义（见 {@code Tool.getMiningSpeed}/{@code isCorrectForDrops}）：按规则顺序，
 * 第一条「命中目标方块且对应字段存在」的规则生效；都不命中则用
 * {@code defaultMiningSpeed}。规则命中依赖 {@code HolderSet}（标签或方块名集合）。</p>
 *
 * <p>本实现把 {@code HolderSet} 解析为 {@link Match}：形如
 * {@code #minecraft:mineable/pickaxe} 的标签用方块采集族（{@link BlockNameTable#TOOL_PICKAXE}
 * 等）判定（与 blocks.json 的 {@code material} 等价），内联方块名/方块 ID 列表用名称集合判定。
 * 判定所需的“目标方块族/名称”由调用方（{@code MiningCalculator}）从方块状态直接提供，无需标签包。</p>
 */
public final class ToolModel {

    /** 规则的目标方块匹配器 */
    public interface Match {
        /**
         * @param blockName     目标方块名（如 {@code stone}）
         * @param blockCategory 目标方块采集族（{@code BlockNameTable.TOOL_*}）
         */
        boolean test(String blockName, byte blockCategory);
    }

    /** 空匹配：任何方块都不命中（用于无法解析的 HolderSet，安全退化到默认速度） */
    private static final Match NEVER = (name, category) -> false;

    /** 采集族标签匹配（{@code #minecraft:mineable/<x>}） */
    private record CategoryMatch(byte category) implements Match {
        @Override
        public boolean test(String blockName, byte blockCategory) {
            return category != BlockNameTable.TOOL_NONE && blockCategory == category;
        }
    }

    /** 方块名集合匹配（内联列表） */
    private record NamesMatch(java.util.Set<String> names) implements Match {
        @Override
        public boolean test(String blockName, byte blockCategory) {
            return blockName != null && names.contains(blockName);
        }
    }

    /** 恒真匹配（用于没有 blocks 约束、直接对全部方块生效的规则；原版不会出现，防御用） */
    private static final Match ALWAYS = (name, category) -> true;

    /** 单条挖掘规则（对齐 {@code Tool.Rule}） */
    public record Rule(Match match, Float speed, Boolean correctForDrops) {
    }

    private final List<Rule> rules;
    private final float defaultMiningSpeed;

    public ToolModel(List<Rule> rules, float defaultMiningSpeed) {
        this.rules = rules;
        this.defaultMiningSpeed = defaultMiningSpeed;
    }

    /**
     * 对目标方块的有效挖掘速度（对齐 {@code Tool.getMiningSpeed}）：
     * 首条「speed 存在且匹配」的规则速度；否则 {@code defaultMiningSpeed}。
     */
    public float getMiningSpeed(String blockName, byte blockCategory) {
        for (Rule rule : rules) {
            if (rule.speed() != null && rule.match().test(blockName, blockCategory)) {
                return rule.speed();
            }
        }
        return defaultMiningSpeed;
    }

    /**
     * 是否为该方块的正确掉落工具（对齐 {@code Tool.isCorrectForDrops}）：
     * 首条「correctForDrops 存在且匹配」的规则值；否则 false。
     */
    public boolean isCorrectForDrops(String blockName, byte blockCategory) {
        for (Rule rule : rules) {
            if (rule.correctForDrops() != null && rule.match().test(blockName, blockCategory)) {
                return rule.correctForDrops();
            }
        }
        return false;
    }

    // ---- 匹配器工厂（供注册表解析使用） ----

    static Match categoryMatch(byte category) {
        return new CategoryMatch(category);
    }

    static Match namesMatch(java.util.Set<String> names) {
        return new NamesMatch(names);
    }

    static Match always() {
        return ALWAYS;
    }

    static Match never() {
        return NEVER;
    }
}
