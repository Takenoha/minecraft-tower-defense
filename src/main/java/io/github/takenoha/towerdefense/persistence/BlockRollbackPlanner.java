package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;

/**
 * Decides whether a recovery worker may restore a block without overwriting a later player edit.
 * The planner has no Bukkit dependency and can therefore be tested before Paper integration.
 */
public final class BlockRollbackPlanner {
    public BlockRollbackDecision decide(
            StoredBlockChange change,
            BlockStateSnapshot current) {
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(current, "current");
        if (change.status() == BlockChangeStatus.ROLLED_BACK) {
            return BlockRollbackDecision.SKIP_ALREADY_BEFORE;
        }
        if (change.status() == BlockChangeStatus.CONFLICT) {
            return BlockRollbackDecision.CONFLICT;
        }
        BlockChange value = change.change();
        if (current.blockData().equals(value.expectedAfterBlockData())
                && current.blockState().equals(value.expectedAfterBlockState())
                && current.tileNbt().equals(value.expectedAfterTileNbt())) {
            return BlockRollbackDecision.RESTORE;
        }
        if (current.blockData().equals(value.beforeBlockData())
                && current.blockState().equals(value.beforeBlockState())
                && current.tileNbt().equals(value.beforeTileNbt())) {
            return BlockRollbackDecision.SKIP_ALREADY_BEFORE;
        }
        return BlockRollbackDecision.CONFLICT;
    }
}
