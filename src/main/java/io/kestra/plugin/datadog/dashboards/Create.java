package io.kestra.plugin.datadog.dashboards;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
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

/** Datadog operationId: CreateDashboard. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Create a Datadog dashboard", description = "Creates a new dashboard on every run. Needs dashboards_write. Use Import for a repeatable apply.")
@Plugin(examples = @Example(title = "Create an empty dashboard", full = true, code = """
    id: datadog_dashboard_create
    namespace: company.observability
    tasks:
      - id: dashboard
        type: io.kestra.plugin.datadog.dashboards.Create
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        definition:
          title: Orders
          layout_type: ordered
          widgets: []
    """))
public class Create extends AbstractDashboardTask implements RunnableTask<Create.Output> {
    @NotNull
    @PluginProperty(dynamic = true, group = "main")
    @ToString.Exclude
    @Schema(title = "Dashboard definition", description = "Datadog JSON object. Requires title, layout_type (ordered or free) and a widgets array. Nested widget schemas are validated by Datadog.")
    private Map<String, Object> definition;

    @Override
    public Output run(RunContext context) throws Exception {
        Map<String, Object> body = Dashboards.definition(definition == null ? null : context.render(definition));
        return Output.builder().dashboard(Dashboards.redact(client(context, true).post(Dashboards.PATH, body).map(), context.render(getIncludeSecrets()).as(Boolean.class).orElse(false))).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Created dashboard")
        private final Map<String, Object> dashboard;
    }
}
