package io.kestra.plugin.datadog.logs;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@SuperBuilder
@NoArgsConstructor
public abstract class AbstractWindow extends AbstractDatadogTask {
    @Builder.Default
    @Schema(title = "Query", description = "Datadog Explorer query. Defaults to all events.", defaultValue = "*")
    @PluginProperty(group = "main")
    private Property<String> query = Property.ofValue("*");

    @Builder.Default
    @Schema(title = "Window start", description = "ISO-8601 instant, epoch milliseconds or Datadog relative time. Sent unchanged.", defaultValue = "now-15m")
    @PluginProperty(group = "processing")
    private Property<String> from = Property.ofValue("now-15m");

    @Builder.Default
    @Schema(title = "Window end", description = "Same formats as from. Sent unchanged.", defaultValue = "now")
    @PluginProperty(group = "processing")
    private Property<String> to = Property.ofValue("now");

    protected Map<String, Object> filter(RunContext context) throws Exception {
        var filter = new LinkedHashMap<String, Object>();
        filter.put("query", context.render(query).as(String.class).filter(v -> !v.isBlank()).orElse("*"));
        filter.put("from", Telemetry.timestamp("from", context.render(from).as(String.class).orElse("now-15m")));
        filter.put("to", Telemetry.timestamp("to", context.render(to).as(String.class).orElse("now")));
        return filter;
    }
}
