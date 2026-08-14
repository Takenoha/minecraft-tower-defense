package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.EnemyRole;
import io.github.takenoha.towerdefense.runtime.TaggedEnemy;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin entity taggers. */
class PaperEntityTaggersKotlinBoundaryAbiTest {
    @Test
    void constructorsAndMethodsKeepJavaDescriptors() throws Exception {
        assertPublicConstructor(EventEnemyTagger.class, Plugin.class);
        assertMethod(EventEnemyTagger.class, "tag", void.class, Entity.class, TaggedEnemy.class);
        assertMethod(EventEnemyTagger.class, "read", Optional.class, Entity.class);

        assertPublicConstructor(TowerEntityTagger.class, Plugin.class);
        assertMethod(TowerEntityTagger.class, "tag", void.class, Entity.class, TowerEntityIdentity.class);
        assertMethod(TowerEntityTagger.class, "read", Optional.class, Entity.class);
        assertEquals(1, TowerEntityTagger.class.getField("ENTITY_VERSION").getInt(null));
    }

    @Test
    void publicClassesRemainFinalAndTaggedEnemyRoleFallbackRemainsRepresentable() {
        assertTrue(Modifier.isFinal(EventEnemyTagger.class.getModifiers()));
        assertTrue(Modifier.isFinal(TowerEntityTagger.class.getModifiers()));
        TaggedEnemy enemy = new TaggedEnemy(UUID.randomUUID(), UUID.randomUUID(), EnemyRole.NORMAL);
        assertEquals(EnemyRole.NORMAL, enemy.role());
    }

    private static void assertPublicConstructor(Class<?> type, Class<?>... parameterTypes) throws Exception {
        var constructor = type.getDeclaredConstructor(parameterTypes);
        assertTrue(Modifier.isPublic(constructor.getModifiers()), type.getName());
    }

    private static void assertMethod(
            Class<?> type, String name, Class<?> returnType, Class<?>... parameterTypes) throws Exception {
        var method = type.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
    }
}
