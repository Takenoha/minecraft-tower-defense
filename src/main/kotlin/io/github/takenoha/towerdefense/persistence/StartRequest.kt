package io.github.takenoha.towerdefense.persistence

import io.github.takenoha.towerdefense.domain.DefensePhase
import io.github.takenoha.towerdefense.domain.DefenseSessionSnapshot
import java.time.Instant
import java.util.Objects
import java.util.Optional
import java.util.UUID
import kotlin.jvm.JvmRecord

/** Immutable input to the atomic session-create and global-lock operation. */
@JvmRecord
data class StartRequest(
    val session: DefenseSessionSnapshot,
    val coreId: UUID,
    val configSnapshot: String,
    val configVersion: Int,
    val startedAt: Instant,
    val raidSealId: Optional<UUID>,
) {
    /** Backwards-compatible administrator/test start which deliberately consumes no seal. */
    constructor(
        session: DefenseSessionSnapshot,
        coreId: UUID,
        configSnapshot: String,
        configVersion: Int,
        startedAt: Instant,
    ) : this(session, coreId, configSnapshot, configVersion, startedAt, Optional.empty())

    init {
        Objects.requireNonNull(session, "session")
        Objects.requireNonNull(coreId, "coreId")
        Objects.requireNonNull(configSnapshot, "configSnapshot")
        Objects.requireNonNull(startedAt, "startedAt")
        Objects.requireNonNull(raidSealId, "raidSealId")
        if (session.phase() != DefensePhase.COUNTDOWN) {
            throw IllegalArgumentException("A new session must begin in COUNTDOWN")
        }
        if (!session.coreState().present) {
            throw IllegalArgumentException("A new session requires a present core")
        }
        if (configVersion <= 0) {
            throw IllegalArgumentException("configVersion must be positive")
        }
    }
}
