package io.github.takenoha.towerdefense.paper

import java.util.HashSet
import java.util.UUID

/** Main-thread lifecycle gate used while a player's voucher state is being reconciled. */
class PlayerRecoveryGuard {
    private val playerIds: MutableSet<UUID> = HashSet()

    fun begin(playerId: UUID) {
        playerIds.add(playerId)
    }

    fun complete(playerId: UUID) {
        playerIds.remove(playerId)
    }

    fun isGuarded(playerId: UUID): Boolean = playerIds.contains(playerId)
}
