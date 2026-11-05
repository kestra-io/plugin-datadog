package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.NumericIds;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Map;
import java.util.stream.Collectors;

/** Datadog operationId: CheckCanDeleteMonitor. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Check whether Datadog monitors can be deleted", description = "Checks dependencies without deleting monitors. Needs monitors_read. Returns Datadog data and dependency errors. HTTP errors still fail the task.")
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
    private Property<java.util.List<Long>> monitorIds;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var ids = NumericIds.render(context, monitorIds, "monitorIds");
            if (ids.isEmpty()) {
                throw new IllegalArgumentException("monitorIds must not be empty");
            }
            ids.forEach(MonitorIO::positiveId);
            String query = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
            return Output.builder().result(MonitorIO.redactMap(client.get("/api/v1/monitor/can_delete", Map.of("monitor_ids", query)).map(), includeSecrets(context))).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Deletion check", description = "data.ok lists deletable monitor ids. errors maps monitor ids to dependency messages.")
        private final Map<String, Object> result;
    }
}
