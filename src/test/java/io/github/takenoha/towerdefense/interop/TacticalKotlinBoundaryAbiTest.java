package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.persistence.TacticalDefinitionCodec;
import io.github.takenoha.towerdefense.tactical.TacticalBuildCatalog;
import io.github.takenoha.towerdefense.tactical.TacticalEffectEntry;
import io.github.takenoha.towerdefense.tactical.TacticalSkillNodeDefinition;
import io.github.takenoha.towerdefense.tactical.TacticalSkillNodeSnapshot;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TacticalKotlinBoundaryAbiTest {
    private static final String LINEAR_TDB1_FIXTURE =
            "{\"format\":\"tdb1\",\"payload\":\"AAZnb2xkZW4AAAABAAZHb2xkZW4ADkdvbGRlbiBmaXh0dXJlAAdPRkZFTlNFAAZDT01NT04BAAAAAQAFQVJST1cAAAABAAVBUlJPVwAAAAAGAA1nb2xkZW4tdGllci0xAAAAAQAAAAEABlRpZXIgMQANRGVzY3JpcHRpb24gMQAAAAAADWdvbGRlbi10aWVyLTIAAAABAAAAAgAGVGllciAyAA1EZXNjcmlwdGlvbiAyAAAAAAANZ29sZGVuLXRpZXItMwAAAAEAAAADAAZUaWVyIDMADURlc2NyaXB0aW9uIDMAAAAAAA1nb2xkZW4tdGllci00AAAAAQAAAAQABlRpZXIgNAANRGVzY3JpcHRpb24gNAAAAAAADWdvbGRlbi10aWVyLTUAAAABAAAABQAGVGllciA1AA1EZXNjcmlwdGlvbiA1AAAAAAANZ29sZGVuLXRpZXItNgAAAAEAAAAGAAZUaWVyIDYADURlc2NyaXB0aW9uIDYAAAAA\"}";

    @Test
    void keepsJavaRecordShapeAndConstructorsForBothBranchModels() throws Exception {
        assertRecordShape(
                TacticalSkillNodeDefinition.class,
                List.of(
                        "id",
                        "version",
                        "tier",
                        "displayName",
                        "description",
                        "effects",
                        "prerequisiteNodeIds",
                        "exclusiveBranchGroup",
                        "branchId"));
        assertRecordShape(
                TacticalSkillNodeSnapshot.class,
                List.of(
                        "id",
                        "version",
                        "tier",
                        "displayName",
                        "description",
                        "effects",
                        "prerequisiteNodeIds",
                        "exclusiveBranchGroup",
                        "branchId"));

        assertNotNull(TacticalSkillNodeDefinition.class.getConstructor(
                String.class,
                int.class,
                int.class,
                String.class,
                String.class,
                List.class,
                List.class,
                Optional.class,
                Optional.class));
        assertNotNull(TacticalSkillNodeDefinition.class.getConstructor(
                String.class,
                int.class,
                int.class,
                String.class,
                String.class,
                List.class));
        assertNotNull(TacticalSkillNodeSnapshot.class.getConstructor(
                String.class,
                int.class,
                int.class,
                String.class,
                String.class,
                List.class,
                List.class,
                Optional.class,
                Optional.class));
        assertNotNull(TacticalSkillNodeSnapshot.class.getConstructor(
                String.class,
                int.class,
                int.class,
                String.class,
                String.class,
                List.class));
    }

    @Test
    void exposesCodecAsTheExistingJavaStaticBoundary() throws Exception {
        var encode = TacticalDefinitionCodec.class.getMethod(
                "encode",
                io.github.takenoha.towerdefense.tactical.TacticalBuildDefinition.class);
        var decode = TacticalDefinitionCodec.class.getMethod("decode", String.class);

        assertTrue(Modifier.isStatic(encode.getModifiers()));
        assertTrue(Modifier.isStatic(decode.getModifiers()));

        var definition = TacticalBuildCatalog.defaults().require("arrow-specialization");
        var encoded = TacticalDefinitionCodec.encode(definition);
        assertTrue(encoded.startsWith("{\"format\":\"tdb2\""));
        assertEquals(definition, TacticalDefinitionCodec.decode(encoded));
        assertThrows(
                IllegalArgumentException.class,
                () -> TacticalDefinitionCodec.decode(null));
    }

    @Test
    void keepsCanonicalCollectionCopiesAndRejectsNullElements() {
        var definitionPrerequisites = new ArrayList<String>(List.of("parent-node"));
        var definition = new TacticalSkillNodeDefinition(
                "copy-node",
                1,
                2,
                "Copy node",
                "Copy node fixture",
                List.of(),
                definitionPrerequisites,
                Optional.empty(),
                Optional.empty());
        definitionPrerequisites.add("later-node");

        assertEquals(List.of("parent-node"), definition.prerequisiteNodeIds());
        assertThrows(
                UnsupportedOperationException.class,
                () -> definition.prerequisiteNodeIds().add("later-node"));

        var snapshotPrerequisites = new ArrayList<String>(List.of("parent-node"));
        var snapshot = new TacticalSkillNodeSnapshot(
                "copy-node",
                1,
                2,
                "Copy node",
                "Copy node fixture",
                List.of(),
                snapshotPrerequisites,
                Optional.empty(),
                Optional.empty());
        snapshotPrerequisites.add("later-node");

        assertEquals(List.of("parent-node"), snapshot.prerequisiteNodeIds());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.prerequisiteNodeIds().add("later-node"));

        var nullPrerequisites = new ArrayList<String>();
        nullPrerequisites.add(null);
        assertThrows(
                NullPointerException.class,
                () -> new TacticalSkillNodeDefinition(
                        "null-node",
                        1,
                        1,
                        "Null node",
                        "Null node fixture",
                        List.of(),
                        nullPrerequisites,
                        Optional.empty(),
                        Optional.empty()));

        var nullEffects = new ArrayList<TacticalEffectEntry>();
        nullEffects.add(null);
        assertThrows(
                NullPointerException.class,
                () -> new TacticalSkillNodeSnapshot(
                        "null-node",
                        1,
                        1,
                        "Null node",
                        "Null node fixture",
                        nullEffects,
                        List.of(),
                        Optional.empty(),
                        Optional.empty()));
    }

    @Test
    void preservesTheExistingTdb1WireFixtureExactly() {
        var decoded = TacticalDefinitionCodec.decode(LINEAR_TDB1_FIXTURE);

        assertEquals("golden", decoded.id());
        assertEquals(6, decoded.nodes().size());
        assertTrue(decoded.nodes().stream().allMatch(node ->
                node.prerequisiteNodeIds().isEmpty()
                        && node.exclusiveBranchGroup().isEmpty()
                        && node.branchId().isEmpty()));
        assertEquals(LINEAR_TDB1_FIXTURE, TacticalDefinitionCodec.encode(decoded));
    }

    private static void assertRecordShape(Class<?> type, List<String> components) {
        assertTrue(type.isRecord());
        assertNotNull(type.getRecordComponents());
        assertEquals(
                components,
                Arrays.stream(type.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
    }
}
