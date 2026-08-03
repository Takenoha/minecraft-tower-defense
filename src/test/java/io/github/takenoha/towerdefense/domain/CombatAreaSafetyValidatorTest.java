package io.github.takenoha.towerdefense.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.config.ForbiddenRegion;
import io.github.takenoha.towerdefense.config.ProtectionSettings;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CombatAreaSafetyValidatorTest {
    private static final CombatArea AREA = new CombatArea(80.0d, 56.0d, 80.0d, 192.0d, 32.0d);

    @Test
    void acceptsAnAreaInsideTheBorderAndOutsideDenyListedRegions() {
        List<String> violations = CombatAreaSafetyValidator.violations(
                "world",
                0.0d,
                0.0d,
                AREA,
                new ProtectionSettings(
                        Set.of("world_nether"),
                        List.of(new ForbiddenRegion("world", 200.0d, 200.0d, 300.0d, 300.0d))),
                new WorldBorderSnapshot(0.0d, 0.0d, 1_000.0d));

        assertTrue(violations.isEmpty(), () -> "unexpected violations: " + violations);
    }

    @Test
    void rejectsForbiddenWorldRegionIntersectionAndBorderOverflowTogether() {
        List<String> violations = CombatAreaSafetyValidator.violations(
                "WORLD",
                450.0d,
                0.0d,
                AREA,
                new ProtectionSettings(
                        Set.of("world"),
                        List.of(new ForbiddenRegion("world", 500.0d, -10.0d, 600.0d, 10.0d))),
                new WorldBorderSnapshot(0.0d, 0.0d, 800.0d));

        assertEquals(3, violations.size());
        assertTrue(violations.get(0).contains("forbidden-worlds"));
        assertTrue(violations.get(1).contains("forbidden-regions[0]"));
        assertTrue(violations.get(2).contains("world-border"));
    }

    @Test
    void treatsRegionAndBorderEdgesAsUnsafe() {
        ForbiddenRegion region = new ForbiddenRegion("world", 80.0d, -1.0d, 100.0d, 1.0d);

        assertTrue(region.intersectsCircle("world", 0.0d, 0.0d, 80.0d));
        assertFalse(region.intersectsCircle("other", 0.0d, 0.0d, 80.0d));
        assertTrue(new WorldBorderSnapshot(0.0d, 0.0d, 160.0d).containsCircle(0.0d, 0.0d, 80.0d));
        assertFalse(new WorldBorderSnapshot(0.0d, 0.0d, 159.999d).containsCircle(0.0d, 0.0d, 80.0d));
    }

    @Test
    void appendsThirdPartyRegionViolationsToTheExplicitSafetyChecks() {
        List<String> violations = CombatAreaSafetyValidator.violations(
                "world",
                0.0d,
                0.0d,
                AREA,
                ProtectionSettings.empty(),
                new WorldBorderSnapshot(0.0d, 0.0d, 1_000.0d),
                (worldName, centerX, centerZ, radius) ->
                        List.of("protection.third-party: claim overlap"));

        assertEquals(List.of("protection.third-party: claim overlap"), violations);
    }
}
