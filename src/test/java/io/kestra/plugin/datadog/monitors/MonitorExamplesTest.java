package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.serializers.YamlParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MonitorExamplesTest {
    @Test
    void everyNewTaskExampleParsesAsAFullFlow() {
        for (Class<?> task : java.util.List.of(Create.class, Update.class, Get.class, Delete.class, Validate.class, Apply.class, Export.class, Search.class, CanDelete.class, Configuration.class,
            io.kestra.plugin.datadog.downtimes.Create.class, io.kestra.plugin.datadog.downtimes.Get.class, io.kestra.plugin.datadog.downtimes.List.class, io.kestra.plugin.datadog.downtimes.Update.class)) {
            for (var example : task.getAnnotation(Plugin.class).examples()) {
                assertTrue(example.full());
                Flow flow = YamlParser.parse(String.join("\n", example.code()), Flow.class);
                assertNotNull(flow.getId(), task.getName());
                assertFalse(flow.getTasks().isEmpty(), task.getName());
            }
        }
    }

    @Test
    void maintenanceCancellationLivesInFinallyAndHasACompletionGuard() {
        String example = String.join("\n", io.kestra.plugin.datadog.downtimes.Create.class.getAnnotation(Plugin.class).examples()[0].code());
        Flow flow = YamlParser.parse(example, Flow.class);
        assertEquals(2, flow.getTasks().size());
        assertTrue(example.contains("finally:"));
        assertTrue(example.contains("runIf: \"{{ outputs.mute is defined }}\""));
        assertTrue(example.contains("ignoreNotFound: true"));
    }

    @Test
    void areaDocumentationFlowParses() throws Exception {
        String doc = java.nio.file.Files.readString(java.nio.file.Path.of("docs/areas/monitors.md"));
        var matcher = java.util.regex.Pattern.compile("```yaml\\n(.*?)```", java.util.regex.Pattern.DOTALL).matcher(doc);
        int count = 0;
        while (matcher.find()) {
            assertNotNull(YamlParser.parse(matcher.group(1), Flow.class));
            count++;
        }
        assertEquals(1, count);
    }
}
