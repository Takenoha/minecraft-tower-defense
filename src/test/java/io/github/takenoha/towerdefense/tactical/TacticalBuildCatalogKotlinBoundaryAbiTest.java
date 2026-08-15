package io.github.takenoha.towerdefense.tactical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TacticalBuildCatalogKotlinBoundaryAbiTest {
    @Test
    void keepsJavaConstructorConstantsAndMethods() throws Exception {
        assertTrue(Modifier.isPublic(TacticalBuildCatalog.class.getModifiers()));
        assertTrue(Modifier.isFinal(TacticalBuildCatalog.class.getModifiers()));

        var constructor = TacticalBuildCatalog.class.getConstructor(List.class);
        assertTrue(Modifier.isPublic(constructor.getModifiers()));

        for (String constant : List.of("DEFINITION_VERSION", "GENERATOR_VERSION")) {
            var field = TacticalBuildCatalog.class.getField(constant);
            assertTrue(Modifier.isPublic(field.getModifiers()));
            assertTrue(Modifier.isStatic(field.getModifiers()));
            assertTrue(Modifier.isFinal(field.getModifiers()));
            assertEquals(int.class, field.getType());
            assertEquals(1, field.getInt(null));
        }

        var defaults = TacticalBuildCatalog.class.getMethod("defaults");
        assertTrue(Modifier.isPublic(defaults.getModifiers()));
        assertTrue(Modifier.isStatic(defaults.getModifiers()));
        assertEquals(TacticalBuildCatalog.class, defaults.getReturnType());

        assertEquals(List.class, TacticalBuildCatalog.class
                .getMethod("definitions").getReturnType());
        assertEquals(List.class, TacticalBuildCatalog.class
                .getMethod("enabledDefinitions").getReturnType());
        assertEquals(TacticalBuildDefinition.class, TacticalBuildCatalog.class
                .getMethod("require", String.class).getReturnType());
    }

    @Test
    void preservesDefaultOrderLookupAndImmutableCopies() {
        var catalog = TacticalBuildCatalog.defaults();
        assertEquals(
                List.of(
                        "rapid-fire",
                        "long-range",
                        "heavy-fortress",
                        "flame-suppression",
                        "ice-lightning",
                        "final-defense-line",
                        "arrow-specialization"),
                catalog.definitions().stream().map(TacticalBuildDefinition::id).toList());
        assertEquals(catalog.definitions(), catalog.enabledDefinitions());
        assertEquals("arrow-specialization", catalog.require("arrow-specialization").id());
        assertEquals(6, catalog.require("arrow-specialization").nodes().size());

        assertThrows(
                UnsupportedOperationException.class,
                () -> catalog.definitions().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> catalog.enabledDefinitions().clear());

        var input = new ArrayList<>(catalog.definitions());
        var copied = new TacticalBuildCatalog(input);
        input.clear();
        assertEquals(7, copied.definitions().size());
    }

    @Test
    void preservesExplicitNullAndUnknownLookupGuards() {
        assertThrows(
                NullPointerException.class,
                () -> new TacticalBuildCatalog(null));
        assertThrows(
                NullPointerException.class,
                () -> TacticalBuildCatalog.defaults().require(null));
        assertThrows(
                NullPointerException.class,
                () -> TacticalBuildCatalog.defaults().require("missing-build"));
    }
}
