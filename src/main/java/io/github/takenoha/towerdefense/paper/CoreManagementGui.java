package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.domain.CoreRepairCost;
import io.github.takenoha.towerdefense.domain.TeamProgress;
import io.github.takenoha.towerdefense.persistence.CoreRecord;
import io.github.takenoha.towerdefense.persistence.TeamRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Builds the first player-facing core management GUI. */
public final class CoreManagementGui {
    public static final int SIZE = 27;
    public static final int TEAM_SLOT = 0;
    public static final int RESEARCH_DEPOSIT_SLOT = 9;
    public static final int TOWER_RESEARCH_SLOT = 10;
    public static final int REPAIR_SLOT = 11;
    public static final int START_SLOT = 13;
    public static final int RELOCATE_SLOT = 15;
    public static final int CLOSE_SLOT = 22;

    private CoreManagementGui() {
    }

    public static Inventory create(
            CoreRecord core,
            TeamRecord team,
            TeamProgress progress,
            CoreRepairCost repairCost,
            String repairMaterialName) {
        Objects.requireNonNull(core, "core");
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(progress, "progress");
        CoreManagementInventoryHolder holder = new CoreManagementInventoryHolder(core.id());
        Inventory inventory = Bukkit.createInventory(
                holder,
                SIZE,
                Component.text("防衛コア管理", NamedTextColor.AQUA));
        holder.attach(inventory);

        inventory.setItem(4, item(
                Material.BEACON,
                "コア状態",
                List.of(
                        "HP: " + core.currentHitPoints() + " / " + core.maximumHitPoints(),
                        "位置: " + core.blockX() + ", " + core.blockY() + ", " + core.blockZ(),
                        "最高クリアLv: " + progress.highestClearedLevel(),
                        "研究ポイント: " + progress.researchPoints()),
                NamedTextColor.AQUA));

        List<String> memberLore = new ArrayList<>();
        memberLore.add("チーム名: " + team.displayName());
        memberLore.add("オーナー: " + playerName(team.ownerId()));
        memberLore.add("メンバー: " + team.members().size() + " / "
                + io.github.takenoha.towerdefense.persistence.DefenseRepository.MAX_TEAM_MEMBERS + "人");
        team.members().stream()
                .sorted()
                .map(CoreManagementGui::playerName)
                .forEach(memberLore::add);
        memberLore.add("クリックでチーム管理を開きます。");
        inventory.setItem(TEAM_SLOT, item(Material.PLAYER_HEAD, "チーム", memberLore, NamedTextColor.GREEN));
        inventory.setItem(RESEARCH_DEPOSIT_SLOT, item(
                Material.AMETHYST_SHARD,
                "研究結晶を納品",
                List.of(
                        "手に持った発行元チームの研究結晶を納品します。",
                        "納品数: 手に持っているスタック全量",
                        "現在の研究ポイント: " + progress.researchPoints(),
                        "クリックで納品"),
                NamedTextColor.LIGHT_PURPLE));
        inventory.setItem(TOWER_RESEARCH_SLOT, item(
                Material.ENCHANTING_TABLE,
                "タワー研究",
                List.of(
                        "現在の研究ポイント: " + progress.researchPoints(),
                        "タワー種別ごとの研究Lvを上げます。",
                        "クリックで研究画面を開きます。"),
                NamedTextColor.LIGHT_PURPLE));

        if (repairCost == null) {
            inventory.setItem(11, item(
                    Material.ANVIL,
                    "修繕不要",
                    List.of("コアは最大HPです。"),
                    NamedTextColor.GRAY));
        } else {
            inventory.setItem(11, item(
                    Material.ANVIL,
                    "コアを全修繕",
                    List.of(
                            "不足HP: " + repairCost.repairAmount(),
                            repairMaterialName + ": " + repairCost.vanillaMaterialAmount(),
                            "防衛の欠片: " + repairCost.defenseShardAmount(),
                            "クリックで材料を消費して修繕"),
                    NamedTextColor.YELLOW));
        }
        inventory.setItem(START_SLOT, item(
                Material.ENDER_EYE,
                "所持中の最高ステージを開始",
                List.of(
                        "所持中で最も高いステージの襲撃の印を1個消費します。",
                        "防衛範囲内にいるチームメンバーが参加します。",
                        "ステージを指定する場合は上段のステージボタンを押します。"),
                NamedTextColor.GOLD));
        inventory.setItem(15, item(
                Material.COMPASS,
                "コアを移設",
                List.of(
                        "満タンのコアのみ移設できます。",
                        "先に移設先の固体ブロックを見てください。",
                        "設置タワーがある場合は移設できません。"),
                NamedTextColor.LIGHT_PURPLE));
        inventory.setItem(CLOSE_SLOT, item(
                Material.BARRIER,
                "閉じる",
                List.of(),
                NamedTextColor.RED));

        for (long stageLevel : RaidSealCatalog.recipeStages()) {
            boolean unlocked = progress.unlockedLevel() >= stageLevel;
            inventory.setItem(
                    RaidSealCatalog.slotForStage(stageLevel),
                    item(
                            unlocked ? Material.ENDER_EYE : Material.GRAY_STAINED_GLASS_PANE,
                            unlocked
                                    ? "ステージ" + stageLevel
                                    : "ステージ" + stageLevel + "（未解放）",
                            unlocked
                                    ? List.of(
                                            "ステージ" + stageLevel + "の襲撃の印を消費して開始します。",
                                            "クリックでこのステージを選択します。")
                                    : List.of("前のステージを勝利すると解放されます。"),
                            unlocked ? NamedTextColor.GOLD : NamedTextColor.DARK_GRAY));
        }
        return inventory;
    }

    public static OptionalLong stageLevelAt(int rawSlot) {
        return RaidSealCatalog.stageAtSlot(rawSlot);
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

    private static String playerName(java.util.UUID playerId) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        return player.getName() == null ? playerId.toString().substring(0, 8) : player.getName();
    }
}
