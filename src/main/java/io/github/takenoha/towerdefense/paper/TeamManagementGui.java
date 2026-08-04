package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.persistence.CoreRecord;
import io.github.takenoha.towerdefense.persistence.TeamRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/** Builds the player-facing team membership and ownership controls. */
public final class TeamManagementGui {
    public static final int SIZE = 54;
    public static final int INVITE_SLOT = 45;
    public static final int LEAVE_SLOT = 47;
    public static final int CLOSE_SLOT = 53;
    public static final int CONFIRM_SLOT = 11;
    public static final int CANCEL_SLOT = 15;

    private static final List<Integer> MEMBER_SLOTS = java.util.stream.IntStream
            .range(0, 45)
            .boxed()
            .toList();

    private TeamManagementGui() {
    }

    public static Inventory create(CoreRecord core, TeamRecord team, UUID viewerId) {
        Objects.requireNonNull(core, "core");
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(viewerId, "viewerId");
        TeamManagementInventoryHolder holder = new TeamManagementInventoryHolder(core.id());
        Inventory inventory = Bukkit.createInventory(
                holder,
                SIZE,
                Component.text("チーム管理", NamedTextColor.GREEN));
        holder.attach(inventory);

        List<UUID> members = team.members().stream()
                .sorted(Comparator.comparing(TeamManagementGui::playerName)
                        .thenComparing(UUID::toString))
                .toList();
        Map<Integer, UUID> memberSlots = new LinkedHashMap<>();
        for (int index = 0; index < Math.min(members.size(), MEMBER_SLOTS.size()); index++) {
            UUID memberId = members.get(index);
            int slot = MEMBER_SLOTS.get(index);
            memberSlots.put(slot, memberId);
            inventory.setItem(slot, memberHead(
                    memberId,
                    team,
                    viewerId,
                    members.size() > MEMBER_SLOTS.size()));
        }
        holder.attachMemberSlots(memberSlots);

        boolean owner = team.ownerId().equals(viewerId);
        inventory.setItem(INVITE_SLOT, item(
                owner ? Material.EMERALD : Material.GRAY_DYE,
                owner ? "近くのプレイヤーを招待" : "プレイヤー招待（オーナーのみ）",
                owner
                        ? List.of(
                                "6ブロック以内のプレイヤーが1人だけのとき、",
                                "そのプレイヤーをチームへ招待します。")
                        : List.of("チームオーナーだけが使用できます。"),
                owner ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        inventory.setItem(LEAVE_SLOT, item(
                Material.OAK_DOOR,
                "チームから脱退",
                List.of("確認後、現在のチームから脱退します。"),
                NamedTextColor.YELLOW));
        inventory.setItem(CLOSE_SLOT, item(
                Material.BARRIER,
                "閉じる",
                List.of(),
                NamedTextColor.RED));
        inventory.setItem(49, item(
                Material.BOOK,
                "操作方法",
                List.of(
                        "メンバーを左クリック: オーナーが除名",
                        "メンバーを右クリック: オーナーを移譲",
                        "オーナー自身には操作できません。"),
                NamedTextColor.AQUA));
        return inventory;
    }

    public static Inventory createConfirmation(
            UUID coreId,
            UUID targetId,
            TeamManagementConfirmationHolder.Action action) {
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(action, "action");
        TeamManagementConfirmationHolder holder = new TeamManagementConfirmationHolder(
                coreId,
                targetId,
                action);
        Inventory inventory = Bukkit.createInventory(
                holder,
                27,
                Component.text("チーム操作の確認", NamedTextColor.YELLOW));
        holder.attach(inventory);

        String targetName = playerName(targetId);
        String operation = switch (action) {
            case REMOVE_MEMBER -> "「" + targetName + "」をチームから除名します。";
            case TRANSFER_OWNER -> "「" + targetName + "」へオーナーを移譲します。";
            case LEAVE_TEAM -> "「" + targetName + "」としてチームから脱退します。";
        };
        inventory.setItem(4, item(
                Material.PLAYER_HEAD,
                "操作内容",
                List.of(operation, "この操作は防衛戦中には実行できません。"),
                NamedTextColor.YELLOW));
        inventory.setItem(TeamManagementGui.CONFIRM_SLOT, item(
                Material.LIME_CONCRETE,
                "実行する",
                List.of("クリックで確定します。"),
                NamedTextColor.GREEN));
        inventory.setItem(TeamManagementGui.CANCEL_SLOT, item(
                Material.RED_CONCRETE,
                "キャンセル",
                List.of("チーム管理画面へ戻ります。"),
                NamedTextColor.RED));
        return inventory;
    }

    private static ItemStack memberHead(
            UUID memberId,
            TeamRecord team,
            UUID viewerId,
            boolean overflow) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) Objects.requireNonNull(
                item.getItemMeta(),
                "player head metadata");
        OfflinePlayer player = Bukkit.getOfflinePlayer(memberId);
        meta.setOwningPlayer(player);
        String role = team.ownerId().equals(memberId) ? "オーナー" : "メンバー";
        List<String> lore = new ArrayList<>();
        lore.add(role);
        if (memberId.equals(viewerId)) {
            lore.add("あなた");
        }
        if (team.ownerId().equals(viewerId) && !team.ownerId().equals(memberId)) {
            lore.add("左クリック: 除名");
            lore.add("右クリック: オーナー移譲");
        }
        if (overflow) {
            lore.add("表示できないメンバーがあります。");
        }
        meta.displayName(Component.text(playerName(memberId), NamedTextColor.WHITE));
        meta.lore(lore.stream().map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
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

    private static String playerName(UUID playerId) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        return player.getName() == null ? playerId.toString().substring(0, 8) : player.getName();
    }
}
