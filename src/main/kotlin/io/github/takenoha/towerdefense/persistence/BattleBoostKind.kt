package io.github.takenoha.towerdefense.persistence

/** Temporary stat that can be purchased with an event's team-shared battle funds. */
enum class BattleBoostKind(private val idValue: String) {
    POWER("power"),
    SPEED("speed"),
    RANGE("range"),
    ;

    fun id(): String = idValue
}
