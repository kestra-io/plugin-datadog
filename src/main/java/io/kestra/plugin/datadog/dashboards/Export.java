package io.kestra.plugin.datadog.dashboards;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
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

    @Override
    public Output run(RunContext context) throws Exception {
        String id = context.render(dashboardId).as(String.class).orElse(null);
        String path = Dashboards.path(id);
        var definition = Dashboards.definition(client(context, true).get(path, Map.of()).map());
        definition = Dashboards.redact(definition, context.render(getIncludeSecrets()).as(Boolean.class).orElse(false));
        return Output.builder().dashboardId(id).uri(Dashboards.storeJson(context, definition)).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Source dashboard identifier")
        private final String dashboardId;
        @Schema(title = "JSON definition URI", description = "Pass to Import.from. Imports reject redacted placeholders.")
        private final URI uri;
    }
}
