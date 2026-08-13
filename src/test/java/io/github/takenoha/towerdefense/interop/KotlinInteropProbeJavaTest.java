package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.takenoha.towerdefense.tactical.TacticalSkillNodeDefinition;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KotlinInteropProbeJavaTest {
    @Test
    void JavaCallsKotlinProbeWithJvmStaticAndThrows() {
        TacticalSkillNodeDefinition node = new TacticalSkillNodeDefinition(
                "java-caller-node",
                1,
                1,
                "Java caller node",
                "Java caller probe",
                List.of(),
                List.of(),
                Optional.empty(),
                Optional.empty());

        assertEquals("java-caller-node", KotlinInteropProbe.javaRecordId(node));
        assertNull(KotlinInteropProbe.javaRecordBranchId(node));
        assertEquals(Optional.of("value"), KotlinInteropProbe.nullableToOptional("value"));
        assertNull(KotlinInteropProbe.optionalToNullable(Optional.empty()));
        assertThrows(IOException.class, KotlinInteropProbe::throwChecked);
        assertThrows(
                UnsupportedOperationException.class,
                () -> KotlinInteropProbe.immutableValues().add("gamma"));
    }
}
