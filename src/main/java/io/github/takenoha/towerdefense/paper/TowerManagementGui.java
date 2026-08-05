package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.domain.TowerTargetPriority;
import io.github.takenoha.towerdefense.persistence.BattleBoost;
import io.github.takenoha.towerdefense.persistence.BattleBoostKind;
import io.github.takenoha.towerdefense.persistence.TowerRecord;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Builds the tower-management screen for inspection, targeting, and retrieval. */
public final class TowerManagementGui {
    public static final int SIZE = 27;
    public static final int PRIORITY_START_SLOT = 9;
    public static final int BOOST_POWER_SLOT = 1;
    public static final int BOOST_SPEED_SLOT = 2;
    public static final int BOOST_RANGE_SLOT = 3;
    public static final int REPAIR_SLOT = 5;
    public static final int UPGRADE_SLOT = 18;
    public static final int REMOVE_SLOT = 20;
    public static final int HELP_SLOT = 22;
    public static final int CLOSE_SLOT = 26;

    private static final List<TowerTargetPriority> PRIORITIES =
            List.of(TowerTargetPriority.values());

    private TowerManagementGui() {
    }

    public static Inventory create(
            TowerRecord tower,
            boolean canRemove,
            String removalReason) {
        return create(tower, canRemove, removalReason, tower.individualLevel(), 0, 0);
    }

    public static Inventory create(
            TowerRecord tower,
            boolean canRemove,
            String removalReason,
            int researchLevel,
            int shardCost,
            int enhancementCoreCost) {
        return create(
                tower,
                canRemove,
                removalReason,
                researchLevel,
                shardCost,
                enhancementCoreCost,
                false,
                0L,
                Map.of(),
                0,
                0,
                0,
                false,
                0L,
                0L,
                0);
    }

    public static Inventory create(
            TowerRecord tower,
            boolean canRemove,
            String removalReason,
            int researchLevel,
            int shardCost,
            int enhancementCoreCost,
            boolean canBuyBoost,
            long battleFunds,
        Map<BattleBoostKind, BattleBoost> boosts,
            int powerCost,
            int speedCost,
            int rangeCost,
            boolean canRepair,
            long currentHitPoints,
            long maximumHitPoints,
            int repairCost) {
        Objects.requireNonNull(tower, "tower");
        Objects.requireNonNull(removalReason, "removalReason");
        Objects.requireNonNull(boosts, "boosts");
        if (researchLevel <= 0 || shardCost < 0 || enhancementCoreCost < 0) {
            throw new IllegalArgumentException("tower management values are invalid");
        }
        TowerManagementInventoryHolder holder = new TowerManagementInventoryHolder(tower.id());
        Inventory inventory = Bukkit.createInventory(
                holder,
                SIZE,
                Component.text("タワー管理", NamedTextColor.GREEN));
        holder.attach(inventory);

        inventory.setItem(4, item(
                TowerItemTagger.materialFor(tower.type()),
                tower.type().displayName() + "タワー",
                List.of(
                        "個体Lv: " + tower.individualLevel(),
                        "研究上限: " + researchLevel,
                        "対象優先: " + tower.targetPriority().displayName(),
                        "座標: " + tower.blockX() + ", " + tower.blockY()
                                + ", " + tower.blockZ(),
                        "右クリックしたタワーの操作画面"),
                NamedTextColor.AQUA));
        setBoostItem(
                inventory,
                BOOST_POWER_SLOT,
                BattleBoostKind.POWER,
                boosts,
                powerCost,
                canBuyBoost,
                battleFunds);
        setBoostItem(
                inventory,
                BOOST_SPEED_SLOT,
                BattleBoostKind.SPEED,
                boosts,
                speedCost,
                canBuyBoost,
                battleFunds);
        setBoostItem(
                inventory,
                BOOST_RANGE_SLOT,
                BattleBoostKind.RANGE,
                boosts,
                rangeCost,
                canBuyBoost,
                battleFunds);
        boolean repairAvailable = canRepair
                && repairCost > 0
                && currentHitPoints < maximumHitPoints;
        inventory.setItem(REPAIR_SLOT, item(
                repairAvailable ? Material.ANVIL : Material.GRAY_DYE,
                repairAvailable ? "タワーを修理" : "タワー修理（現在不可）",
                repairAvailable
                        ? List.of(
                                "HP: " + currentHitPoints + " / " + maximumHitPoints,
                                "戦闘資金: " + battleFunds,
                                "費用: " + repairCost,
                                "クリックでHPを回復")
                        : List.of(
                                "HP: " + currentHitPoints + " / " + maximumHitPoints,
                                canRepair
                                        ? "修理できるHPがありません。"
                                        : "準備時間・ウェーブ間のみ修理できます。"),
                repairAvailable ? NamedTextColor.YELLOW : NamedTextColor.GRAY));
        for (int index = 0; index < PRIORITIES.size(); index++) {
            TowerTargetPriority priority = PRIORITIES.get(index);
            boolean selected = priority == tower.targetPriority();
            inventory.setItem(
                    PRIORITY_START_SLOT + index,
                    item(
                            priorityMaterial(priority),
                            (selected ? "▶ " : "") + priority.displayName(),
                            selected
                                    ? List.of("現在の対象優先", "クリックで変更できます")
                                    : List.of("クリックで対象優先を変更"),
                            selected ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        }
        boolean canUpgrade = shardCost > 0
                && enhancementCoreCost > 0
                && tower.individualLevel() < researchLevel;
        inventory.setItem(UPGRADE_SLOT, item(
                canUpgrade ? Material.NETHER_STAR : Material.GRAY_DYE,
                canUpgrade ? "個体Lvを強化" : "個体Lv強化（現在不可）",
                canUpgrade
                        ? List.of(
                                "次の個体Lv: " + (tower.individualLevel() + 1),
                                "防衛の欠片: " + shardCost,
                                "強化コア: " + enhancementCoreCost,
                                "クリックで素材を消費して強化")
                        : tower.individualLevel() >= researchLevel
                                ? List.of("チーム研究Lvが上限です。")
                                : List.of("個体Lv強化は現在利用できません。"),
                canUpgrade ? NamedTextColor.AQUA : NamedTextColor.GRAY));
        inventory.setItem(REMOVE_SLOT, item(
                canRemove ? Material.EMERALD : Material.GRAY_DYE,
                canRemove ? "回収・移設" : "回収・移設（現在不可）",
                canRemove
                        ? List.of(
                                "タワーアイテムを返却します。",
                                "返却したアイテムを別の場所へ設置できます。",
                                "防衛戦開始後は実行できません。")
                        : List.of(removalReason),
                canRemove ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        inventory.setItem(CLOSE_SLOT, item(
                Material.BARRIER,
                "閉じる",
                List.of(),
                NamedTextColor.RED));
        inventory.setItem(HELP_SLOT, item(
                Material.BOOK,
                "操作方法",
                List.of(
                        "回収したアイテムは個体Lvを保持します。",
                        "対象優先は回収・再設置後も保持します。",
                        "プレイヤー採掘・爆発・ピストンでは移動しません。"),
                NamedTextColor.YELLOW));
        return inventory;
    }

    private static void setBoostItem(
            Inventory inventory,
            int slot,
            BattleBoostKind kind,
            Map<BattleBoostKind, BattleBoost> boosts,
            int cost,
            boolean canBuyBoost,
            long battleFunds) {
        BattleBoost boost = boosts.get(kind);
        int level = boost == null ? 0 : boost.level();
        boolean available = canBuyBoost && cost > 0;
        String name = switch (kind) {
            case POWER -> "戦闘ブースト: 威力";
            case SPEED -> "戦闘ブースト: 攻撃速度";
            case RANGE -> "戦闘ブースト: 射程";
        };
        inventory.setItem(slot, item(
                available ? Material.NETHER_STAR : Material.GRAY_DYE,
                name + " Lv" + level,
                available
                        ? List.of(
                                "戦闘資金: " + battleFunds,
                                "費用: " + cost,
                                "クリックでこのタワーへ一段付与")
                        : List.of(
                                "戦闘資金: " + battleFunds,
                                canBuyBoost
                                        ? "現在購入できません。"
                                        : "準備時間・ウェーブ間のみ購入できます。"),
                available ? NamedTextColor.GOLD : NamedTextColor.GRAY));
    }

    public static Optional<TowerTargetPriority> priorityAt(int slot) {
        int index = slot - PRIORITY_START_SLOT;
        if (index < 0 || index >= PRIORITIES.size()) {
            return Optional.empty();
        }
        return Optional.of(PRIORITIES.get(index));
    }

    private static Material priorityMaterial(TowerTargetPriority priority) {
        return switch (priority) {
            case CORE_NEAREST -> Material.COMPASS;
            case NEAREST -> Material.CLOCK;
            case HEALTH_HIGH -> Material.DIAMOND;
            case HEALTH_LOW -> Material.REDSTONE;
            case BOSS -> Material.BEACON;
        };
    }

    private static ItemStack item(
            Material material,
            String name,
            List<String> lore,
            NamedTextColor color) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta(), "GUI item metadata");
        meta.displayName(Component.text(name, color));
        meta.lore(lore.stream().map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }
}
