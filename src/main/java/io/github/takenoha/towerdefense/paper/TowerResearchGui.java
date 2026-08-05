package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.config.TowerSettings;
import io.github.takenoha.towerdefense.domain.TeamProgress;
import io.github.takenoha.towerdefense.domain.TowerResearch;
import io.github.takenoha.towerdefense.domain.TowerType;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Builds the team-shared tower research purchase screen. */
public final class TowerResearchGui {
    public static final int SIZE = 27;
    public static final int RESEARCH_START_SLOT = 10;
    public static final int CLOSE_SLOT = 22;

    private TowerResearchGui() {
    }

    public static Inventory create(
            java.util.UUID coreId,
            TeamProgress progress,
            List<TowerResearch> research,
            TowerSettings settings) {
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(research, "research");
        Objects.requireNonNull(settings, "settings");
        TowerResearchInventoryHolder holder = new TowerResearchInventoryHolder(coreId);
        Inventory inventory = Bukkit.createInventory(
                holder,
                SIZE,
                Component.text("タワー研究", NamedTextColor.LIGHT_PURPLE));
        holder.attach(inventory);
        inventory.setItem(4, item(
                Material.ENCHANTING_TABLE,
                "チーム研究",
                List.of(
                        "研究ポイント: " + progress.researchPoints(),
                        "研究Lvは同種タワーの個体Lv上限です。"),
                NamedTextColor.LIGHT_PURPLE));
        for (TowerResearch value : research) {
            int slot = RESEARCH_START_SLOT + value.towerType().ordinal();
            int cost = settings.researchCost(value.researchLevel());
            boolean canPurchase = progress.researchPoints() >= cost
                    && value.researchLevel() < Integer.MAX_VALUE;
            inventory.setItem(slot, item(
                    TowerItemTagger.materialFor(value.towerType()),
                    value.towerType().displayName() + "研究Lv" + value.researchLevel(),
                    canPurchase
                            ? List.of(
                                    "次の研究Lv: " + (value.researchLevel() + 1),
                                    "必要研究ポイント: " + cost,
                                    "クリックで研究を購入")
                            : List.of(
                                    "次の研究Lv費用: " + cost,
                                    progress.researchPoints() < cost
                                            ? "研究ポイントが不足しています。"
                                            : "これ以上研究できません。"),
                    canPurchase ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        }
        inventory.setItem(CLOSE_SLOT, item(
                Material.BARRIER, "閉じる", List.of(), NamedTextColor.RED));
        return inventory;
    }

    public static java.util.Optional<TowerType> towerTypeAt(int slot) {
        int index = slot - RESEARCH_START_SLOT;
        TowerType[] types = TowerType.values();
        return index < 0 || index >= types.length
                ? java.util.Optional.empty()
                : java.util.Optional.of(types[index]);
    }

    private static ItemStack item(
            Material material,
            String name,
            List<String> lore,
            NamedTextColor color) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta(), "research GUI metadata");
        meta.displayName(Component.text(name, color));
        meta.lore(lore.stream().map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }
}
