package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SuperBuilder
@Getter
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Schema(
    title = "Check whether Datadog monitors can be deleted",
    description = """
        Checks dependencies without deleting monitors. Needs monitors_read.
        Returns Datadog data and dependency errors on HTTP 200 or 409. Other HTTP errors fail the task.
        """
)
@Plugin(examples = @Example(title = "Check monitor dependencies", full = true, code = """
    id: can_delete_monitor
    namespace: company.observability
    tasks:
      - id: check
        type: io.kestra.plugin.datadog.monitors.CanDelete
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        monitorIds: [123]
    """))
public class CanDelete extends AbstractDatadogTask implements RunnableTask<CanDelete.Output> {
    @NotNull
    @Schema(title = "Monitor ids", description = "A non-empty list of positive monitor ids.")
    @PluginProperty(group = "main")
    private Property<List<Long>> monitorIds;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var rIds = renderIds(context);
            if (rIds.isEmpty()) {
                throw new IllegalArgumentException("monitorIds must not be empty");
            }
            if (rIds.stream().anyMatch(id -> id < 1)) {
                throw new IllegalArgumentException("monitorIds must contain positive integers");
            }
            var query = rIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            return Output.builder().result(client.get("/api/v1/monitor/can_delete", Map.of("monitor_ids", query), 409).map()).build();
        }
    }

    private List<Long> renderIds(RunContext context) throws Exception {
        var result = new ArrayList<Long>();
        // Property.asList can retain Integer elements even when its requested element type is Long.
        for (var value : (List<?>) context.render(monitorIds).asList(Object.class)) {
            try {
                if (value instanceof Number number) {
                    result.add(new BigDecimal(number.toString()).longValueExact());
                } else if (value instanceof String text) {
                    result.add(Long.parseLong(text));
                } else {
                    throw new IllegalArgumentException("monitorIds must contain non-null 64-bit integers");
                }
            } catch (ArithmeticException | NumberFormatException e) {
                throw new IllegalArgumentException("monitorIds must contain non-null 64-bit integers", e);
            }
        }
        return result;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Deletion check", description = "data.ok lists deletable monitor ids. errors maps monitor ids to dependency messages.")
        private final Map<String, Object> result;
    }
}
