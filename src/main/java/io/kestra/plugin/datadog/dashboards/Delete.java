package io.kestra.plugin.datadog.dashboards;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/** Datadog operationId: DeleteDashboard. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Delete one Datadog dashboard", description = "Deletes only the dashboard identified by dashboardId. Needs dashboards_write. A missing dashboard fails unless ignoreNotFound is true.")
@Plugin(examples = @Example(title = "Delete a retired dashboard", full = true, code = """
    id: datadog_dashboard_delete
    namespace: company.observability
    tasks:
      - id: remove
        type: io.kestra.plugin.datadog.dashboards.Delete
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        dashboardId: abc-def-ghi
        ignoreNotFound: true
    """))
public class Delete extends AbstractDatadogTask implements RunnableTask<Delete.Output> {
    @NotNull
    @Schema(title = "Dashboard identifier")
    private Property<String> dashboardId;

    @Schema(title = "Ignore a missing dashboard", description = "Treat HTTP 404 as a no-op. False by default.")
    private Property<Boolean> ignoreNotFound;

    @Override
    public Output run(RunContext context) throws Exception {
        String id = context.render(dashboardId).as(String.class).orElse(null);
        String path = Dashboards.path(id);
        boolean ignore = context.render(ignoreNotFound).as(Boolean.class).orElse(false);
        try {
            client(context, true).delete(path);
            return Output.builder().dashboardId(id).deleted(true).build();
        } catch (DatadogClient.DatadogException e) {
            if (e.getStatus() != 404 || !ignore) {
                throw e;
            }
            return Output.builder().dashboardId(id).deleted(false).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Dashboard identifier")
        private final String dashboardId;
        @Schema(title = "Dashboard was deleted", description = "False when an ignored HTTP 404 reported it missing.")
        private final Boolean deleted;
    }
}
