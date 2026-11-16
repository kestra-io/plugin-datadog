package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.serializers.YamlParser;
import io.kestra.core.junit.annotations.KestraTest;
import jakarta.validation.Validator;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@KestraTest
class MonitorExamplesTest {
    @Inject
    Validator validator;

    @Test
    void everyNewTaskExampleParsesAsAFullFlow() {
        for (var task : java.util.List.of(Create.class, Update.class, Get.class, Delete.class, Validate.class, Apply.class, Export.class, Search.class, CanDelete.class, Configuration.class)) {
            for (var example : task.getAnnotation(Plugin.class).examples()) {
                assertTrue(example.full());
                var flow = YamlParser.parse(String.join("\n", example.code()), Flow.class);
                assertNotNull(flow.getId(), task.getName());
                assertFalse(flow.getTasks().isEmpty(), task.getName());
                assertTrue(validator.validate(flow).isEmpty(), () -> task.getName() + ": " + validator.validate(flow));
            }
        }
    }
}
