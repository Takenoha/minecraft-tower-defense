package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.persistence.ResourceType;
import io.github.takenoha.towerdefense.persistence.TeamResourceSnapshot;
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

/** Displays team balances and active-event provisional claims without exposing item drops. */
public final class ResourceVaultGui {
    public static final int SIZE = 27;
    public static final int DEFENSE_SLOT = 11;
    public static final int ENHANCEMENT_SLOT = 15;
    public static final int CLOSE_SLOT = 22;

    private ResourceVaultGui() {
    }

    public static Inventory create(
            java.util.UUID coreId,
            TeamResourceSnapshot resources) {
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(resources, "resources");
        ResourceVaultInventoryHolder holder = new ResourceVaultInventoryHolder(coreId);
        Inventory inventory = Bukkit.createInventory(
                holder,
                SIZE,
                Component.text("コア資源庫", NamedTextColor.LIGHT_PURPLE));
        holder.attach(inventory);
        inventory.setItem(DEFENSE_SLOT, item(
                Material.PRISMARINE_SHARD,
                ResourceType.DEFENSE_POINTS.displayName(),
                resourceLore(resources, ResourceType.DEFENSE_POINTS),
                NamedTextColor.AQUA));
        inventory.setItem(ENHANCEMENT_SLOT, item(
                Material.NETHER_STAR,
                ResourceType.ENHANCEMENT_POINTS.displayName(),
                resourceLore(resources, ResourceType.ENHANCEMENT_POINTS),
                NamedTextColor.GOLD));
        inventory.setItem(CLOSE_SLOT, item(
                Material.BARRIER,
                "戻る",
                List.of("コア管理へ戻ります。"),
                NamedTextColor.RED));
        return inventory;
    }

    private static List<String> resourceLore(
            TeamResourceSnapshot resources,
            ResourceType type) {
        return List.of(
                "チーム残高: " + resources.balance(type) + "P",
                "今回のチーム仮確保: " + resources.teamProvisional(type) + "P",
                "今回のあなたの仮確保: " + resources.provisional(type) + "P",
                "防衛戦の正常終了時に残高へ確定します。",
                "仮確保分は終端処理まで消費できません。",
                "確定残高は準備時間・ウェーブ間の強化や修理に使用できます。",
                "ポイント証票の発行は後続機能です。");
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
