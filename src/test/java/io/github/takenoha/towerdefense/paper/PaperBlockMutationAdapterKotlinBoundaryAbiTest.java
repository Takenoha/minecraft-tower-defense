package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.DefensePhase;
import io.github.takenoha.towerdefense.persistence.BlockChangeKind;
import io.github.takenoha.towerdefense.persistence.BlockChangeRepository;
import io.github.takenoha.towerdefense.persistence.BlockRollbackPlanner;
import io.github.takenoha.towerdefense.persistence.BlockStateSnapshot;
import io.github.takenoha.towerdefense.persistence.OperationOutcome;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.UUID;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin Paper block mutation adapter. */
class PaperBlockMutationAdapterKotlinBoundaryAbiTest {
    @Test
    void adapterBoundaryRemainsCompatible() throws Exception {
        Class<?> type = PaperBlockMutationAdapter.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));

        Constructor<?> oneArgument = type.getConstructor(BlockChangeRepository.class);
        assertTrue(Modifier.isPublic(oneArgument.getModifiers()));

        Constructor<?> twoArgument = type.getConstructor(
                BlockChangeRepository.class,
                BlockRollbackPlanner.class);
        assertTrue(Modifier.isPublic(twoArgument.getModifiers()));

        assertPublicMethod(type, "nextGeneration", long.class, UUID.class, Block.class);
        assertPublicMethod(type, "countUnresolvedTemporaryBlocks", long.class, UUID.class);
        assertPublicMethod(
                type,
                "apply",
                OperationOutcome.class,
                UUID.class,
                long.class,
                BlockChangeKind.class,
                Block.class,
                BlockStateSnapshot.class,
                UUID.class,
                UUID.class,
                UUID.class,
                Instant.class);
        assertPublicMethod(type, "recoverEvent", void.class, UUID.class, Instant.class);
        assertPublicMethod(
                type,
                "settleEvent",
                void.class,
                UUID.class,
                DefensePhase.class,
                Instant.class);

        assertPrivateStatic(type, "validateEventBlock", void.class,
                io.github.takenoha.towerdefense.persistence.StoredBlockChange.class);
        assertPrivateStatic(type, "loadBlock", Block.class,
                io.github.takenoha.towerdefense.persistence.StoredBlockChange.class);
        assertPrivateStatic(type, "deterministicRollbackOperation", UUID.class, UUID.class, UUID.class);
        assertPrivateStatic(type, "requireMainThread", void.class);
    }

    private static void assertPublicMethod(
            Class<?> type,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        Method method = type.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
    }

    private static void assertPrivateStatic(
            Class<?> type,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        Method method = type.getDeclaredMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPrivate(method.getModifiers()), name);
        assertTrue(Modifier.isStatic(method.getModifiers()), name);
    }
}
