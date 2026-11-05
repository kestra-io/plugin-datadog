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

/** Datadog operationId: UpdateDashboard. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Update a Datadog dashboard", description = "Replaces the definition of the identified dashboard, including its widgets. Needs dashboards_write. Supply the complete desired definition.")
@Plugin(examples = @Example(title = "Replace one dashboard", full = true, code = """
    id: datadog_dashboard_update
    namespace: company.observability
    tasks:
      - id: dashboard
        type: io.kestra.plugin.datadog.dashboards.Update
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        dashboardId: abc-def-ghi
        definition:
          title: Orders
          layout_type: ordered
          widgets: []
    """))
public class Update extends AbstractDashboardTask implements RunnableTask<Update.Output> {
    @NotNull
    @Schema(title = "Dashboard identifier")
    private Property<String> dashboardId;

    @NotNull
    @PluginProperty(dynamic = true, group = "main")
    @ToString.Exclude
    @Schema(title = "Replacement dashboard definition", description = "Requires title, layout_type and widgets. Server-owned fields are removed before the request.")
    private Map<String, Object> definition;

    @Override
    public Output run(RunContext context) throws Exception {
        String path = Dashboards.path(context.render(dashboardId).as(String.class).orElse(null));
        Map<String, Object> body = Dashboards.definition(definition == null ? null : context.render(definition));
        return Output.builder().dashboard(Dashboards.redact(client(context, true).put(path, body).map(), context.render(getIncludeSecrets()).as(Boolean.class).orElse(false))).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Updated dashboard")
        private final Map<String, Object> dashboard;
    }
}
