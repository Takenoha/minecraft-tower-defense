package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.tactical.TacticalBuildDefinition;
import io.github.takenoha.towerdefense.tactical.TacticalCandidate;
import io.github.takenoha.towerdefense.tactical.TacticalCandidateSet;
import java.util.List;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Small, one-screen candidate selection UI used before the existing start transaction. */
public final class TacticalBuildSelectionGui {
    public static final int SIZE = 27;
    public static final int[] CANDIDATE_SLOTS = {11, 13, 15};
    public static final int[] BRANCH_SLOTS = {3, 5};
    public static final int CONFIRM_SLOT = 22;
    public static final int CLOSE_SLOT = 26;

    private TacticalBuildSelectionGui() {
    }

    public static Inventory create(TacticalBuildSelectionInventoryHolder holder) {
        Objects.requireNonNull(holder, "holder");
        Inventory inventory = Bukkit.createInventory(
                holder,
                SIZE,
                Component.text("戦術ビルドを選択", NamedTextColor.DARK_PURPLE));
        holder.attach(inventory);
        refresh(
                inventory,
                holder.candidates(),
                holder.selectedBuildId().orElse(null),
                holder.selectedBranchId().orElse(null));
        inventory.setItem(
                CONFIRM_SLOT,
                item(
                        Material.LIME_CONCRETE,
                        "このビルドで開始",
                        List.of("選択後にクリックすると開始確認へ進みます。"),
                        NamedTextColor.GREEN));
        inventory.setItem(
                CLOSE_SLOT,
                item(
                        Material.BARRIER,
                        "キャンセル",
                        List.of("閉じても開始印は消費されません。"),
                        NamedTextColor.RED));
        return inventory;
    }

    public static void refresh(
            Inventory inventory,
            TacticalCandidateSet candidates,
            String selectedBuildId) {
        refresh(inventory, candidates, selectedBuildId, null);
    }

    public static void refresh(
            Inventory inventory,
            TacticalCandidateSet candidates,
            String selectedBuildId,
            String selectedBranchId) {
        for (int branchSlot : BRANCH_SLOTS) {
            inventory.setItem(branchSlot, null);
        }
        for (int slot = 0; slot < CANDIDATE_SLOTS.length; slot++) {
            TacticalBuildDefinition definition = candidates.candidates().get(slot).definition();
            boolean selected = definition.id().equals(selectedBuildId);
            inventory.setItem(
                    CANDIDATE_SLOTS[slot],
                    item(
                            material(definition.iconMaterial()),
                            (selected ? "▶ " : "") + definition.displayName(),
                            List.of(
                                    definition.description(),
                                    "カテゴリ: " + definition.category(),
                                    "レアリティ: " + definition.rarity(),
                                    selected ? "選択中" : "クリックで選択"),
                            selected ? NamedTextColor.GREEN : NamedTextColor.AQUA));
        }
        if (selectedBuildId == null) {
            return;
        }
        TacticalBuildDefinition selected = candidates.requireBuild(selectedBuildId);
        List<String> branchIds = selected.branchIds();
        for (int index = 0; index < Math.min(branchIds.size(), BRANCH_SLOTS.length); index++) {
            String branchId = branchIds.get(index);
            boolean branchSelected = branchId.equals(selectedBranchId);
            inventory.setItem(
                    BRANCH_SLOTS[index],
                    item(
                            branchMaterial(branchId),
                            (branchSelected ? "▶ " : "") + branchName(branchId),
                            branchLore(selected, branchId, branchSelected),
                            branchSelected ? NamedTextColor.GREEN : NamedTextColor.LIGHT_PURPLE));
        }
    }

    public static int candidateIndexAt(int rawSlot) {
        for (int index = 0; index < CANDIDATE_SLOTS.length; index++) {
            if (CANDIDATE_SLOTS[index] == rawSlot) {
                return index;
            }
        }
        return -1;
    }

    public static int branchIndexAt(int rawSlot) {
        for (int index = 0; index < BRANCH_SLOTS.length; index++) {
            if (BRANCH_SLOTS[index] == rawSlot) {
                return index;
            }
        }
        return -1;
    }

    private static List<String> branchLore(
            TacticalBuildDefinition definition,
            String branchId,
            boolean selected) {
        List<String> lore = new java.util.ArrayList<>();
        lore.add("この枝のTier効果:");
        definition.nodes().stream()
                .filter(node -> node.branchId().filter(branchId::equals).isPresent())
                .sorted(java.util.Comparator.comparingInt(
                        io.github.takenoha.towerdefense.tactical.TacticalSkillNodeDefinition::tier))
                .forEach(node -> lore.add(
                        "Tier " + node.tier() + ": " + node.description()));
        lore.add(selected ? "選択中" : "クリックで選択");
        return lore;
    }

    private static String branchName(String branchId) {
        return switch (branchId) {
            case "rapid-fire" -> "連射ルート";
            case "range" -> "射程ルート";
            default -> branchId + "ルート";
        };
    }

    private static Material branchMaterial(String branchId) {
        return switch (branchId) {
            case "rapid-fire" -> Material.SPECTRAL_ARROW;
            case "range" -> Material.SPYGLASS;
            default -> Material.PAPER;
        };
    }

    private static Material material(String name) {
        Material material = Material.matchMaterial(name);
        return material == null ? Material.PAPER : material;
    }

    private static ItemStack item(
            Material material,
            String name,
            List<String> lore,
            NamedTextColor color) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta(), "selection item metadata");
        meta.displayName(Component.text(name, color));
        meta.lore(lore.stream().map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }
}
