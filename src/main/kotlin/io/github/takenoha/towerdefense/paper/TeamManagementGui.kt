package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.persistence.CoreRecord
import io.github.takenoha.towerdefense.persistence.DefenseRepository
import io.github.takenoha.towerdefense.persistence.TeamRecord
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Objects
import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.SkullMeta

/** Builds the player-facing team membership and ownership controls. */
class TeamManagementGui private constructor() {
    companion object {
        const val SIZE: Int = 54
        const val INVITE_SLOT: Int = 45
        const val LEAVE_SLOT: Int = 47
        const val RENAME_SLOT: Int = 51
        const val CLOSE_SLOT: Int = 53
        const val CONFIRM_SLOT: Int = 11
        const val CANCEL_SLOT: Int = 15

        private val MEMBER_SLOTS: List<Int> = Collections.unmodifiableList(
            ArrayList((0 until 45).toList()),
        )

        @JvmStatic
        fun create(core: CoreRecord, team: TeamRecord, viewerId: UUID): Inventory {
            Objects.requireNonNull(core, "core")
            Objects.requireNonNull(team, "team")
            Objects.requireNonNull(viewerId, "viewerId")
            val holder = TeamManagementInventoryHolder(core.id())
            val inventory = Bukkit.createInventory(
                holder,
                SIZE,
                Component.text("チーム管理", NamedTextColor.GREEN),
            )
            holder.attach(inventory)

            val members = team.members()
                .sortedWith(compareBy<UUID> { playerName(it) }.thenBy { it.toString() })
            val memberSlots = LinkedHashMap<Int, UUID>()
            for (index in 0 until minOf(members.size, MEMBER_SLOTS.size)) {
                val memberId = members[index]
                val slot = MEMBER_SLOTS[index]
                memberSlots[slot] = memberId
                inventory.setItem(
                    slot,
                    memberHead(
                        memberId,
                        team,
                        viewerId,
                        members.size > MEMBER_SLOTS.size,
                    ),
                )
            }
            holder.attachMemberSlots(memberSlots)

            val owner = team.ownerId().equals(viewerId)
            inventory.setItem(
                INVITE_SLOT,
                item(
                    if (owner) Material.EMERALD else Material.GRAY_DYE,
                    if (owner) "近くのプレイヤーを招待" else "プレイヤー招待（オーナーのみ）",
                    if (owner) {
                        listOf(
                            "6ブロック以内のプレイヤーが1人だけのとき、",
                            "そのプレイヤーをチームへ招待します。",
                            "オフライン招待は /td team invite <player> を使用します。",
                        )
                    } else {
                        listOf("チームオーナーだけが使用できます。")
                    },
                    if (owner) NamedTextColor.GREEN else NamedTextColor.GRAY,
                ),
            )
            inventory.setItem(
                LEAVE_SLOT,
                item(
                    Material.OAK_DOOR,
                    "チームから脱退",
                    listOf("確認後、現在のチームから脱退します。"),
                    NamedTextColor.YELLOW,
                ),
            )
            inventory.setItem(
                RENAME_SLOT,
                item(
                    if (owner) Material.NAME_TAG else Material.GRAY_DYE,
                    if (owner) "チーム名を変更" else "チーム名変更（オーナーのみ）",
                    if (owner) {
                        listOf("チャットで /td team rename <名前> を実行します。")
                    } else {
                        listOf("チームオーナーだけが使用できます。")
                    },
                    if (owner) NamedTextColor.LIGHT_PURPLE else NamedTextColor.GRAY,
                ),
            )
            inventory.setItem(
                CLOSE_SLOT,
                item(Material.BARRIER, "閉じる", emptyList(), NamedTextColor.RED),
            )
            inventory.setItem(
                49,
                item(
                    Material.BOOK,
                    "操作方法",
                    listOf(
                        "メンバーを左クリック: オーナーが除名",
                        "メンバーを右クリック: オーナーを移譲",
                        "チーム上限: ${DefenseRepository.MAX_TEAM_MEMBERS}人",
                        "オーナー自身には操作できません。",
                    ),
                    NamedTextColor.AQUA,
                ),
            )
            return inventory
        }

        @JvmStatic
        fun createConfirmation(
            coreId: UUID,
            targetId: UUID,
            action: TeamManagementConfirmationHolder.Action,
        ): Inventory {
            Objects.requireNonNull(coreId, "coreId")
            Objects.requireNonNull(targetId, "targetId")
            Objects.requireNonNull(action, "action")
            val holder = TeamManagementConfirmationHolder(coreId, targetId, action)
            val inventory = Bukkit.createInventory(
                holder,
                27,
                Component.text("チーム操作の確認", NamedTextColor.YELLOW),
            )
            holder.attach(inventory)

            val targetName = playerName(targetId)
            val operation = when (action) {
                TeamManagementConfirmationHolder.Action.REMOVE_MEMBER ->
                    "「$targetName」をチームから除名します。"
                TeamManagementConfirmationHolder.Action.TRANSFER_OWNER ->
                    "「$targetName」へオーナーを移譲します。"
                TeamManagementConfirmationHolder.Action.LEAVE_TEAM ->
                    "「$targetName」としてチームから脱退します。"
            }
            inventory.setItem(
                4,
                item(
                    Material.PLAYER_HEAD,
                    "操作内容",
                    listOf(operation, "この操作は防衛戦中には実行できません。"),
                    NamedTextColor.YELLOW,
                ),
            )
            inventory.setItem(
                CONFIRM_SLOT,
                item(Material.LIME_CONCRETE, "実行する", listOf("クリックで確定します。"), NamedTextColor.GREEN),
            )
            inventory.setItem(
                CANCEL_SLOT,
                item(Material.RED_CONCRETE, "キャンセル", listOf("チーム管理画面へ戻ります。"), NamedTextColor.RED),
            )
            return inventory
        }

        private fun memberHead(
            memberId: UUID,
            team: TeamRecord,
            viewerId: UUID,
            overflow: Boolean,
        ): ItemStack {
            val item = ItemStack(Material.PLAYER_HEAD)
            val meta = Objects.requireNonNull(item.itemMeta, "player head metadata") as SkullMeta
            val player = Bukkit.getOfflinePlayer(memberId)
            meta.owningPlayer = player
            val role = if (team.ownerId().equals(memberId)) "オーナー" else "メンバー"
            val lore = ArrayList<String>()
            lore.add(role)
            if (memberId == viewerId) {
                lore.add("あなた")
            }
            if (team.ownerId().equals(viewerId) && !team.ownerId().equals(memberId)) {
                lore.add("左クリック: 除名")
                lore.add("右クリック: オーナー移譲")
            }
            if (overflow) {
                lore.add("表示できないメンバーがあります。")
            }
            meta.displayName(Component.text(playerName(memberId), NamedTextColor.WHITE))
            meta.lore(
                Collections.unmodifiableList(
                    ArrayList(lore.map { line -> Component.text(line, NamedTextColor.GRAY) }),
                ),
            )
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            item.itemMeta = meta
            return item
        }

        private fun item(
            material: Material,
            name: String,
            lore: List<String>,
            color: NamedTextColor,
        ): ItemStack {
            val item = ItemStack(material)
            val meta: ItemMeta = Objects.requireNonNull(item.itemMeta, "GUI item metadata")
            meta.displayName(Component.text(name, color))
            meta.lore(
                Collections.unmodifiableList(
                    ArrayList(lore.map { line -> Component.text(line, NamedTextColor.GRAY) }),
                ),
            )
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            item.itemMeta = meta
            return item
        }

        private fun playerName(playerId: UUID): String {
            val player: OfflinePlayer = Bukkit.getOfflinePlayer(playerId)
            return player.name ?: playerId.toString().substring(0, 8)
        }
    }
}
