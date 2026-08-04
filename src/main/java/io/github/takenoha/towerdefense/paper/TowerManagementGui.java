package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.domain.TowerTargetPriority;
import io.github.takenoha.towerdefense.persistence.TowerRecord;
import java.util.List;
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
        Objects.requireNonNull(tower, "tower");
        Objects.requireNonNull(removalReason, "removalReason");
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
                        "対象優先: " + tower.targetPriority().displayName(),
                        "座標: " + tower.blockX() + ", " + tower.blockY()
                                + ", " + tower.blockZ(),
                        "右クリックしたタワーの操作画面"),
                NamedTextColor.AQUA));
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
