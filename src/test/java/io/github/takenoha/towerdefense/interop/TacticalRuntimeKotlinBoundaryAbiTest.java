package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.tactical.EmptyTacticalEffectSnapshot;
import io.github.takenoha.towerdefense.tactical.TacticalBuildStateProvider;
import io.github.takenoha.towerdefense.tactical.TacticalBuildSelectionView;
import io.github.takenoha.towerdefense.tactical.TacticalEffectCache;
import io.github.takenoha.towerdefense.tactical.TacticalEffectCompiler;
import io.github.takenoha.towerdefense.tactical.TacticalEffectSnapshot;
import io.github.takenoha.towerdefense.tactical.TacticalTierUnlockPolicy;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TacticalRuntimeKotlinBoundaryAbiTest {
    @Test
    void compilerAndCacheKeepJavaConstructionAndMethodBoundaries() throws Exception {
        Constructor<TacticalEffectCompiler> compilerConstructor =
                TacticalEffectCompiler.class.getDeclaredConstructor();
        assertTrue(Modifier.isPublic(compilerConstructor.getModifiers()));

        Method compile = TacticalEffectCompiler.class.getDeclaredMethod(
                "compile",
                TacticalBuildSelectionView.class);
        assertTrue(Modifier.isPublic(compile.getModifiers()));
        assertEquals(TacticalEffectSnapshot.class, compile.getReturnType());

        Constructor<TacticalEffectCache> stateOnlyConstructor =
                TacticalEffectCache.class.getDeclaredConstructor(TacticalBuildStateProvider.class);
        Constructor<TacticalEffectCache> explicitCompilerConstructor =
                TacticalEffectCache.class.getDeclaredConstructor(
                        TacticalBuildStateProvider.class,
                        TacticalEffectCompiler.class);
        assertTrue(Modifier.isPublic(stateOnlyConstructor.getModifiers()));
        assertTrue(Modifier.isPublic(explicitCompilerConstructor.getModifiers()));

        Method current = TacticalEffectCache.class.getDeclaredMethod(
                "currentForDefense",
                UUID.class);
        assertTrue(Modifier.isPublic(current.getModifiers()));
        assertTrue(Modifier.isSynchronized(current.getModifiers()));
        assertEquals(TacticalEffectSnapshot.class, current.getReturnType());
    }

    @Test
    void tierPolicyKeepsStaticJavaMethodsAndPrivateConstructor() throws Exception {
        Constructor<TacticalTierUnlockPolicy> constructor =
                TacticalTierUnlockPolicy.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));

        Method highest = TacticalTierUnlockPolicy.class.getDeclaredMethod(
                "highestProgressTier",
                int.class,
                int.class);
        Method newlyReached = TacticalTierUnlockPolicy.class.getDeclaredMethod(
                "newlyReachedProgressTiers",
                int.class,
                int.class,
                int.class);
        assertTrue(Modifier.isPublic(highest.getModifiers()));
        assertTrue(Modifier.isStatic(highest.getModifiers()));
        assertTrue(Modifier.isPublic(newlyReached.getModifiers()));
        assertTrue(Modifier.isStatic(newlyReached.getModifiers()));
    }

    @Test
    void cacheStillFailsClosedWhenProviderReturnsNullOptional() {
        UUID defenseId = UUID.randomUUID();
        TacticalEffectCache cache = new TacticalEffectCache(ignored -> null);

        cache.rebuild(defenseId);

        assertSame(
                EmptyTacticalEffectSnapshot.INSTANCE,
                cache.currentForDefense(defenseId));
        assertEquals(0, cache.size());
    }
}
