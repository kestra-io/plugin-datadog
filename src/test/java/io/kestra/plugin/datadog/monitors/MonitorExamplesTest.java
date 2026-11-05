package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.serializers.YamlParser;
import io.kestra.plugin.datadog.downtimes.Create;
import io.kestra.plugin.datadog.downtimes.Get;
import io.kestra.plugin.datadog.downtimes.List;
import io.kestra.plugin.datadog.downtimes.Update;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class MonitorExamplesTest {
    @Test
    void everyNewTaskExampleParsesAsAFullFlow() {
        for (var task : java.util.List.of(Create.class, Update.class, Get.class, Delete.class, Validate.class, Apply.class, Export.class, Search.class, CanDelete.class, Configuration.class,
            Create.class, Get.class, List.class, Update.class)) {
            for (var example : task.getAnnotation(Plugin.class).examples()) {
                assertTrue(example.full());
                var flow = YamlParser.parse(String.join("\n", example.code()), Flow.class);
                assertNotNull(flow.getId(), task.getName());
                assertFalse(flow.getTasks().isEmpty(), task.getName());
            }
        }
    }

    @Test
    void maintenanceCancellationLivesInFinallyAndHasACompletionGuard() {
        var example = String.join("\n", Create.class.getAnnotation(Plugin.class).examples()[0].code());
        var flow = YamlParser.parse(example, Flow.class);
        assertEquals(2, flow.getTasks().size());
        assertTrue(example.contains("finally:"));
        assertTrue(example.contains("runIf: \"{{ outputs.mute is defined }}\""));
        assertTrue(example.contains("ignoreNotFound: true"));
    }

    @Test
    void areaDocumentationFlowParses() throws Exception {
        var doc = Files.readString(Path.of("docs/areas/monitors.md"));
        var matcher = Pattern.compile("```yaml\\n(.*?)```", Pattern.DOTALL).matcher(doc);
        var count = 0;
        while (matcher.find()) {
            assertNotNull(YamlParser.parse(matcher.group(1), Flow.class));
            count++;
        }
        assertEquals(1, count);
    }
}
