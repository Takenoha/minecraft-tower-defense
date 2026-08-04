package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.persistence.TowerRecord;
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

/** Builds the first tower-management screen for inspection and retrieval. */
public final class TowerManagementGui {
    public static final int SIZE = 27;
    public static final int REMOVE_SLOT = 11;
    public static final int CLOSE_SLOT = 15;

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
                Material.BOW,
                tower.type().displayName() + "タワー",
                List.of(
                        "個体Lv: " + tower.individualLevel(),
                        "座標: " + tower.blockX() + ", " + tower.blockY()
                                + ", " + tower.blockZ(),
                        "右クリックしたタワーの操作画面"),
                NamedTextColor.AQUA));
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
        inventory.setItem(22, item(
                Material.BOOK,
                "操作方法",
                List.of(
                        "回収したアイテムは個体Lvを保持します。",
                        "プレイヤー採掘・爆発・ピストンでは移動しません。"),
                NamedTextColor.YELLOW));
        return inventory;
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
