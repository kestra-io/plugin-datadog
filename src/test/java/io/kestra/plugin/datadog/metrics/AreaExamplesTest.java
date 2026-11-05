package io.kestra.plugin.datadog.metrics;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.serializers.YamlParser;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.cost.Estimated;
import io.kestra.plugin.datadog.cost.Historical;
import io.kestra.plugin.datadog.ddsql.Query;
import io.kestra.plugin.datadog.hosts.List;
import io.kestra.plugin.datadog.hosts.Mute;
import io.kestra.plugin.datadog.hosts.Totals;
import io.kestra.plugin.datadog.hosts.Unmute;
import io.kestra.plugin.datadog.usage.Check;
import io.kestra.plugin.datadog.usage.Hourly;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AreaExamplesTest extends AbstractDatadogTest {
    @TestFactory
    Stream<DynamicTest> everyAreaExampleParsesAsAFlow() {
        return Stream.of(QueryScalar.class, QueryTimeseries.class, Check.class, Threshold.class,
            List.class, Totals.class,
            Mute.class, Unmute.class,
            Hourly.class, Check.class,
            Estimated.class, Historical.class,
            Query.class).map(type -> DynamicTest.dynamicTest(type.getName(), () -> {
                var examples = type.getAnnotation(Plugin.class).examples();
                assertTrue(examples.length > 0);
                for (var example : examples) {
                    assertTrue(example.full());
                    var flow = YamlParser.parse(String.join("\n", example.code()), Flow.class);
                    assertNotNull(flow.getId());
                    assertNotNull(flow.getNamespace());
                    assertFalse(flow.getTasks().isEmpty());
                }
            }));
    }
}
