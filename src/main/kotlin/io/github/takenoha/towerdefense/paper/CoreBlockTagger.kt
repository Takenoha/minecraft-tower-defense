package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.persistence.CorePlacement
import java.util.Objects
import org.bukkit.block.Block
import org.bukkit.plugin.Plugin

/** Checks the physical material at a database-owned core coordinate. */
class CoreBlockTagger(plugin: Plugin) {
    init {
        Objects.requireNonNull(plugin, "plugin")
    }

    fun tag(block: Block, placement: CorePlacement): Boolean {
        Objects.requireNonNull(block, "block")
        Objects.requireNonNull(placement, "placement")
        return CoreMaterialPolicy.isCurrentBlock(block.type)
    }

    fun matches(block: Block, placement: CorePlacement): Boolean {
        Objects.requireNonNull(block, "block")
        Objects.requireNonNull(placement, "placement")
        return CoreMaterialPolicy.isCoreBlockMaterial(block.type)
    }
}
