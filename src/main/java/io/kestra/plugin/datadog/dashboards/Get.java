package io.kestra.plugin.datadog.dashboards;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
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

import java.util.Map;

/** Datadog operationId: GetDashboard. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Get a Datadog dashboard", description = "Reads one dashboard and its nested widget definitions. Needs dashboards_read on the application key.")
@Plugin(examples = @Example(title = "Read a dashboard", full = true, code = """
    id: datadog_dashboard_get
    namespace: company.observability
    tasks:
      - id: dashboard
        type: io.kestra.plugin.datadog.dashboards.Get
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        dashboardId: abc-def-ghi
    """))
public class Get extends AbstractDashboardTask implements RunnableTask<Get.Output> {
    @NotNull
    @Schema(title = "Dashboard identifier")
    @PluginProperty(group = "main")
    private Property<String> dashboardId;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var rPath = Dashboards.path(context.render(dashboardId).as(String.class).orElse(null));
            return Output.builder().dashboard(Dashboards.redact(client.get(rPath, Map.of()).map(), context.render(getIncludeSecrets()).as(Boolean.class).orElse(false))).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Dashboard definition", description = "Dashboard fields from Datadog with known credentials redacted by default.")
        private final Map<String, Object> dashboard;
    }
}
