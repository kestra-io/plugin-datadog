package io.kestra.plugin.datadog.dashboards;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.DatadogFetch;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.Map;
import java.util.List;

/** Datadog operationId: GetDashboard. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Export a Datadog dashboard", description = "Writes a writable dashboard definition as JSON to internal storage. Removes server-owned top-level fields. Needs dashboards_read. Known credentials are redacted by default.")
@Plugin(examples = @Example(title = "Export a dashboard definition", full = true, code = """
    id: datadog_dashboard_export
    namespace: company.observability
    tasks:
      - id: export
        type: io.kestra.plugin.datadog.dashboards.Export
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        dashboardId: abc-def-ghi
    """))
public class Export extends AbstractDashboardTask implements RunnableTask<Export.Output> {
    @NotNull
    @Schema(title = "Dashboard identifier")
    private Property<String> dashboardId;

    @Schema(title = "Fetch type", description = "STORE writes a JSON definition accepted by Import. FETCH returns definitions, FETCH_ONE returns row and NONE returns only size.", defaultValue = "STORE")
    private Property<FetchType> fetchType;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            String id = context.render(dashboardId).as(String.class).orElse(null);
            String path = Dashboards.path(id);
            var definition = Dashboards.definition(client.get(path, Map.of()).map());
            definition = Dashboards.redact(definition, context.render(getIncludeSecrets()).as(Boolean.class).orElse(false));
            var rFetchType = context.render(fetchType).as(FetchType.class).orElse(FetchType.STORE);
            var result = DatadogFetch.apply(context, rFetchType == FetchType.STORE ? FetchType.NONE : rFetchType, List.of(definition));
            return Output.builder().dashboardId(id).definitions(result.rows()).row(result.row()).size(result.size())
                .uri(rFetchType == FetchType.STORE ? DatadogFetch.storeDocument(context, definition, false) : null).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Dashboard definitions", description = "Present with FETCH.")
        private final List<Map<String, Object>> definitions;
        @Schema(title = "First definition", description = "Present with FETCH_ONE.")
        private final Map<String, Object> row;
        @Schema(title = "Number of definitions")
        private final Integer size;
        @Schema(title = "Source dashboard identifier")
        private final String dashboardId;
        @Schema(title = "JSON definition URI", description = "Pass to Import.from. Imports reject redacted placeholders.")
        private final URI uri;
    }
}
