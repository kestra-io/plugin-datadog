package io.kestra.plugin.datadog.monitors;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.DatadogFetch;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.Map;

/** Datadog operationId: ListMonitors. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Export Datadog monitor definitions", description = "Writes writable monitor fields to YAML or ION in internal storage. Needs monitors_read. Fails if maxMonitors would truncate the export. YAML is accepted by Apply after adding a unique management tag to each unmanaged monitor. Sensitive values are filtered by default and must be restored before applying.")
@Plugin(examples = @Example(title = "Export production monitors to YAML", full = true, code = """
    id: export_monitors
    namespace: company.observability
    tasks:
      - id: export
        type: io.kestra.plugin.datadog.monitors.Export
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        monitorTags: [env:prod]
        format: YAML
    """))
public class Export extends AbstractMonitorTask implements RunnableTask<Export.Output> {
    @Schema(title = "Monitor tags filter")
    private Property<java.util.List<String>> monitorTags;
    @Schema(title = "Maximum monitors", description = "Between 1 and 10000. Defaults to 1000.")
    private Property<@Min(1) @Max(10000) Integer> maxMonitors;
    @Schema(title = "File format", description = "YAML by default. ION has one monitor per row.")
    private Property<Format> format;

    @Schema(title = "Fetch type", description = "STORE writes definitions in format. FETCH returns definitions, FETCH_ONE returns row and NONE returns only size.", defaultValue = "STORE")
    private Property<FetchType> fetchType;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            int limit = MonitorIO.limit(context.render(maxMonitors).as(Integer.class).orElse(null));
            Format renderedFormat = context.render(format).as(Format.class).orElse(Format.YAML);
            boolean include = includeSecrets(context);
            var rFetchType = context.render(fetchType).as(FetchType.class).orElse(FetchType.STORE);
            if (rFetchType != FetchType.STORE && format != null) {
                throw new IllegalArgumentException("`format` is only used with fetchType STORE");
            }
            var params = DatadogClient.query();
            Monitors.join(context.render(monitorTags).asList(String.class)).ifPresent(value -> params.put("monitor_tags", value));
            var monitors = Monitors.collect(client, params, limit + 1);
            if (monitors.size() > limit) {
                throw new IllegalStateException("Export exceeds maxMonitors; narrow monitorTags or raise the limit");
            }
            var rows = monitors.stream().map(MonitorDefinitions::writable).map(row -> MonitorIO.redactMap(row, include)).toList();
            var yaml = rFetchType == FetchType.STORE && renderedFormat == Format.YAML;
            var result = DatadogFetch.apply(context, yaml ? FetchType.NONE : rFetchType, rows);
            return Output.builder().definitions(result.rows()).row(result.row())
                .uri(yaml ? DatadogFetch.storeDocument(context, rows, true) : result.uri()).size(result.size()).build();
        }
    }

    public enum Format { YAML, ION }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Monitor definitions", description = "Present with FETCH.")
        private final java.util.List<Map<String, Object>> definitions;
        @Schema(title = "First definition", description = "Present with FETCH_ONE.")
        private final Map<String, Object> row;
        @Schema(title = "Export file URI")
        private final URI uri;
        @Schema(title = "Number of monitors")
        private final Integer size;
    }
}
