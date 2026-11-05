package io.kestra.plugin.datadog;

import io.kestra.core.models.annotations.PluginProperty;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PropertyGroupsTest {
    private static final Set<String> GROUPS = Set.of("main", "processing", "advanced", "reliability", "connection");

    @TestFactory
    Stream<DynamicTest> everyTaskAndTriggerPropertyHasAGroup() throws Exception {
        return SafetyInvariantsTest.components().stream().map(type -> DynamicTest.dynamicTest(type.getName(), () -> {
            for (var field : SafetyInvariantsTest.fields(type)) {
                var property = field.getAnnotation(PluginProperty.class);
                assertNotNull(property, type.getName() + "." + field.getName() + " needs @PluginProperty");
                assertTrue(GROUPS.contains(property.group()), type.getName() + "." + field.getName() + " has no recognized group");
            }
        }));
    }
}
