package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.persistence.BlockStateSnapshot;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin Paper block codecs. */
class PaperBlockCodecsKotlinBoundaryAbiTest {
    @Test
    void utilityConstructorsAndStaticMethodsRemainCompatible() throws Exception {
        assertPrivateUtility(PaperTileNbtCodec.class);
        assertStaticMethod(PaperTileNbtCodec.class, "capture", String.class, BlockState.class);
        assertStaticMethod(PaperTileNbtCodec.class, "apply", void.class, BlockState.class, String.class);

        assertPrivateUtility(PaperBlockStateCodec.class);
        assertStaticMethod(
                PaperBlockStateCodec.class,
                "captureBefore",
                BlockStateSnapshot.class,
                Block.class);
        assertStaticMethod(
                PaperBlockStateCodec.class,
                "captureComparable",
                BlockStateSnapshot.class,
                Block.class);
        assertStaticMethod(PaperBlockStateCodec.class, "parseBlockData", BlockData.class, String.class);
        assertStaticMethod(
                PaperBlockStateCodec.class,
                "snapshotForBlockData",
                BlockStateSnapshot.class,
                String.class);
        assertStaticMethod(
                PaperBlockStateCodec.class,
                "applyBlockData",
                void.class,
                Block.class,
                String.class);
        assertStaticMethod(
                PaperBlockStateCodec.class,
                "applySnapshot",
                void.class,
                Block.class,
                BlockStateSnapshot.class);
    }

    private static void assertPrivateUtility(Class<?> type) throws Exception {
        assertTrue(Modifier.isPublic(type.getModifiers()), type.getName());
        assertTrue(Modifier.isFinal(type.getModifiers()), type.getName());
        Constructor<?> constructor = type.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()), type.getName());
    }

    private static void assertStaticMethod(
            Class<?> type,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        var method = type.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        assertTrue(Modifier.isStatic(method.getModifiers()), name);
    }
}
