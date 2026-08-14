package io.github.takenoha.towerdefense.persistence

/** The two classes of world mutation that the event owns. */
enum class BlockChangeKind {
    EVENT_BLOCK,
    TEMPORARY_BLOCK,
}
