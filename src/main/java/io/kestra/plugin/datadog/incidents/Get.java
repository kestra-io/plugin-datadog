package io.kestra.plugin.datadog.incidents;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/** Datadog operationId: GetIncident. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Get a Datadog incident", description = "Reads one incident by id. Requires incident_read.")
@Plugin(examples = @Example(title = "Read an incident", full = true, code = """
    id: read_incident
    namespace: company.observability
    tasks:
      - id: incident
        type: io.kestra.plugin.datadog.incidents.Get
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        incidentId: "00000000-0000-0000-0000-000000000001"
    """))
public class Get extends ResponseSupport implements RunnableTask<ResponseOutput> {
    @NotNull
    @Schema(title = "Incident id")
    @PluginProperty(group = "main")
    private Property<String> incidentId;

    @Override
    public ResponseOutput run(RunContext context) throws Exception {
        return send(context, "GET", "/api/v2/incidents/" + identifier(context, incidentId, "incidentId"), Map.of(), null, false);
    }
}
