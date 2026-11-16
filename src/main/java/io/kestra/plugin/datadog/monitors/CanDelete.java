package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.NumericIds;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Datadog operationId: CheckCanDeleteMonitor. */
@SuperBuilder
@Getter
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Schema(title = "Check whether Datadog monitors can be deleted", description = "Checks dependencies without deleting monitors. Needs monitors_read. Returns Datadog data and dependency errors on HTTP 200 or 409. Other HTTP errors fail the task.")
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
public class CanDelete extends AbstractMonitorTask implements RunnableTask<CanDelete.Output> {
    @NotNull
    @Schema(title = "Monitor ids", description = "A non-empty list of positive monitor ids.")
    @PluginProperty(group = "main")
    private Property<@NotEmpty List<@NotNull @Min(1) Long>> monitorIds;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var rIds = NumericIds.render(context, monitorIds, "monitorIds");
            if (rIds.isEmpty()) {
                throw new IllegalArgumentException("monitorIds must not be empty");
            }
            var query = rIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            var response = client.exchange("GET", client.apiBase(), "/api/v1/monitor/can_delete", Map.of("monitor_ids", query), null, false);
            if (!response.ok() && response.status() != 409) {
                throw new DatadogClient.DatadogException(response.status(), "Datadog deletion check failed with HTTP " + response.status());
            }
            return Output.builder().result(MonitorIO.redactMap(response.map(), includeSecrets(context))).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Deletion check", description = "data.ok lists deletable monitor ids. errors maps monitor ids to dependency messages.")
        private final Map<String, Object> result;
    }
}
