package io.github.takenoha.towerdefense.tactical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;

class TacticalBuildDefinitionValidatorKotlinBoundaryAbiTest {
    @Test
    void keepsUtilityClassAndPublicStaticJavaBoundary() throws Exception {
        assertTrue(Modifier.isPublic(TacticalBuildDefinitionValidator.class.getModifiers()));
        assertTrue(Modifier.isFinal(TacticalBuildDefinitionValidator.class.getModifiers()));

        var constructor = TacticalBuildDefinitionValidator.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));

        var validate = TacticalBuildDefinitionValidator.class.getMethod(
                "validate", TacticalBuildDefinition.class);
        assertTrue(Modifier.isPublic(validate.getModifiers()));
        assertTrue(Modifier.isStatic(validate.getModifiers()));
        assertEquals(void.class, validate.getReturnType());

        var validateAll = TacticalBuildDefinitionValidator.class.getMethod(
                "validateAll", List.class);
        assertTrue(Modifier.isPublic(validateAll.getModifiers()));
        assertTrue(Modifier.isStatic(validateAll.getModifiers()));
        assertEquals(void.class, validateAll.getReturnType());
    }

    @Test
    void preservesValidCatalogAndValidationFailures() {
        var catalog = TacticalBuildCatalog.defaults();
        assertNotNull(catalog.require("rapid-fire"));
        assertNotNull(catalog.require("arrow-specialization"));

        TacticalBuildDefinitionValidator.validate(catalog.require("rapid-fire"));
        TacticalBuildDefinitionValidator.validateAll(catalog.definitions());

        assertThrows(
                IllegalArgumentException.class,
                () -> TacticalBuildDefinitionValidator.validate(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> TacticalBuildDefinitionValidator.validateAll(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> TacticalBuildDefinitionValidator.validateAll(List.of()));
    }
}
