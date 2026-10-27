package top.mcocet.running.flight;

import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;

/**
 * 鞘翅耐久飞行预算（建模移植 MovementSync 1.11 的 {@code ElytraDurability}）：
 * 原版每 20 个滑翔 tick 尝试扣 1 点耐久（26.1.2
 * {@code LivingEntity.updateFallFlying}：{@code fallFlyTicks+1} 逢 10 的偶数倍扣 1），
 * 据此把"还能飞多少秒"折算成预算，用于起飞门控（{@link #canStart}）与
 * 巡航中的"该着陆了"判定（{@link #shouldLand}）。
 *
 * <p>保守建模原则（对齐 MS）：不给 Unbreaking 附魔记账（收益是概率性的）；
 * 耐久读不到时按满耐久乐观处理（服务器才是最终裁定者，本预算只用于决策）。
 * {@code damage}/{@code max_damage}/{@code unbreakable} 从胸甲槽物品栈的
 * 数据组件尽力解析。</p>
 *
 * @param equipped   胸槽是否已装备鞘翅
 * @param maxDamage  满耐久上限（鞘翅默认 432，组件 max_damage 可覆盖）
 * @param damage     已损失耐久（组件 damage）
 * @param unbreakable 是否不可破坏
 */
public record ElytraDurability(boolean equipped, int maxDamage, int damage, boolean unbreakable) {

    /** 原版鞘翅满耐久（Items.ELYTRA max_damage=432） */
    public static final int DEFAULT_MAX_DAMAGE = 432;
    /** 着陆预留（秒）：预算必须给最后滑翔/坠地阶段留出的余量 */
    public static final int LANDING_RESERVE_SECONDS = 10;
    /** 经验估速（米/秒）：用于把水平距离折算为预计飞行秒数（偏保守） */
    public static final double ESTIMATED_BLOCKS_PER_SECOND = 20.0;

    public static ElytraDurability notEquipped() {
        return new ElytraDurability(false, DEFAULT_MAX_DAMAGE, 0, false);
    }

    /**
     * 从胸甲槽物品栈尽力解析耐久状态：组件缺失/解析失败按"满耐久、可破坏"
     * 乐观处理（真实损耗由服务器裁定，预算仅用于是否值得起飞的决策）。
     */
    public static ElytraDurability fromElytraStack(ItemStack stack) {
        if (stack == null || stack.getId() <= 0) {
            return notEquipped();
        }
        int max = DEFAULT_MAX_DAMAGE;
        int damaged = 0;
        boolean unbreakable = false;
        try {
            DataComponents patch = stack.getDataComponentsPatch();
            if (patch != null) {
                Integer maxSeen = patch.get(DataComponentTypes.MAX_DAMAGE);
                if (maxSeen != null && maxSeen > 0) {
                    max = maxSeen;
                }
                Integer seenDamage = patch.get(DataComponentTypes.DAMAGE);
                if (seenDamage != null) {
                    damaged = Math.max(0, seenDamage);
                }
                unbreakable = patch.contains(DataComponentTypes.UNBREAKABLE);
            }
        } catch (RuntimeException ignored) {
            // 组件形态异常：保持乐观默认
        }
        return new ElytraDurability(true, max, damaged, unbreakable);
    }

    /** 剩余可用耐久点（官方每 20 个滑翔 tick 扣 1 点，换算见 {@link #remainingFlightSeconds}）。 */
    public int remainingDurability() {
        return equipped ? Math.max(0, maxDamage - damage) : 0;
    }

    /** 是否还能用（未装备或只剩最后 1 点耐久时官方会拒绝，保守判不可用）。 */
    public boolean usable() {
        return equipped && (unbreakable || remainingDurability() > 1);
    }

    /**
     * 预算内还能滑翔的秒数：官方每 20 tick（1 秒）扣 1 点，取整点差留最后 1 点
     * （官方在剩 0 时拒绝继续滑翔）。不可破坏为无穷。
     */
    public double remainingFlightSeconds() {
        if (!usable()) {
            return 0.0;
        }
        return unbreakable ? Double.POSITIVE_INFINITY : remainingDurability() - 1;
    }

    /** 水平距离的预计飞行秒数（上取整 + 着陆预留；距离无效返回无穷=不可行）。 */
    public static double estimateFlightSeconds(double horizontalDistance) {
        if (!Double.isFinite(horizontalDistance) || horizontalDistance < 0) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.ceil(horizontalDistance / ESTIMATED_BLOCKS_PER_SECOND) + LANDING_RESERVE_SECONDS;
    }

    /** 起飞门控：预算必须覆盖"全程 + 着陆预留"。 */
    public boolean canStart(double horizontalDistance) {
        return usable() && Double.isFinite(horizontalDistance) && horizontalDistance >= 0
                && remainingFlightSeconds() > estimateFlightSeconds(horizontalDistance);
    }

    /** 从当前高度着陆所需秒数（按 12 米/秒的下落上限保守估 + 预留）。 */
    public static double landingSeconds(double height) {
        return Math.max(0.0, height) / 12.0 + LANDING_RESERVE_SECONDS;
    }

    /** 巡航门控：剩余预算已不足以从当前高度安全着陆 → 应尽快找落点着陆。 */
    public boolean shouldLand(double heightAboveGround) {
        return remainingFlightSeconds() <= landingSeconds(heightAboveGround);
    }
}
