package io.kestra.plugin.datadog.incidents;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import java.util.Map;

/** Datadog operationId: DeleteIncident. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Delete one Datadog incident", description = "Permanently deletes the incident identified by incidentId. Requires incident_write. No query-based deletion.")
@Plugin(examples = @Example(title = "Remove a test incident", full = true, code = """
    id: delete_test_incident
    namespace: company.observability
    tasks:
      - id: delete
        type: io.kestra.plugin.datadog.incidents.Delete
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        incidentId: "00000000-0000-0000-0000-000000000001"
        ignoreNotFound: true
    """))
public class Delete extends ResponseSupport implements RunnableTask<ResponseOutput> {
    @NotNull
    @Schema(title = "Incident id")
    private Property<String> incidentId;

    @Schema(title = "Ignore missing incident", description = "Treats only HTTP 404 as success. False by default.")
    private Property<Boolean> ignoreNotFound;

    @Override
    public ResponseOutput run(RunContext context) throws Exception {
        String id = identifier(context, incidentId, "incidentId");
        boolean ignore = context.render(ignoreNotFound).as(Boolean.class).orElse(false);
        try {
            return send(context, "DELETE", "/api/v2/incidents/" + id, Map.of(), null, false);
        } catch (DatadogClient.DatadogException e) {
            if (e.getStatus() == 404 && ignore) {
                return ResponseOutput.builder().id(id).statusCode(404).body(Map.of()).build();
            }
            throw e;
        }
    }
}
