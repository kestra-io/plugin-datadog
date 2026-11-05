package io.kestra.plugin.datadog.incidents;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/** Datadog operationId: UpdateIncident. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Update one Datadog incident", description = "PATCHes only supplied attributes and relationships. Requires incident_write. Set state to RESOLVED to close the response loop.")
@Plugin(examples = @Example(title = "Resolve an incident", full = true, code = """
    id: resolve_incident
    namespace: company.observability
    tasks:
      - id: resolve
        type: io.kestra.plugin.datadog.incidents.Update
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        incidentId: "00000000-0000-0000-0000-000000000001"
        state: RESOLVED
    """))
public class Update extends ResponseSupport implements RunnableTask<ResponseOutput> {
    @NotNull
    @Schema(title = "Incident id")
    private Property<String> incidentId;

    @Schema(title = "Incident title", description = "Nonblank when supplied.")
    private Property<String> title;

    @Schema(title = "Customers impacted")
    private Property<Boolean> customerImpacted;

    @Schema(title = "Customer impact scope")
    private Property<String> customerImpactScope;

    @Schema(title = "Severity")
    private Property<IncidentFields.Severity> severity;

    @Schema(title = "Incident state", description = "Common state values mapped to the state dropdown field. Custom values can use fields.")
    private Property<IncidentFields.State> state;

    @Schema(title = "Incident fields", description = "Additional field name to type/value objects. Unset fields are omitted.")
    private Property<Map<String, Object>> fields;

    @Schema(title = "Commander user id", description = "Assigns this user when supplied.")
    private Property<String> commanderUserId;

    @Override
    public ResponseOutput run(RunContext context) throws Exception {
        String id = identifier(context, incidentId, "incidentId");
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (title != null) {
            attributes.put("title", required(context, title, "title"));
        }
        context.render(customerImpacted).as(Boolean.class).ifPresent(v -> attributes.put("customer_impacted", v));
        context.render(customerImpactScope).as(String.class).ifPresent(v -> attributes.put("customer_impact_scope", v));
        Map<String, Object> renderedFields = IncidentFields.fields(context.render(fields).asMap(String.class, Object.class),
            context.render(severity).as(IncidentFields.Severity.class).orElse(null),
            context.render(state).as(IncidentFields.State.class).orElse(null));
        if (!renderedFields.isEmpty()) {
            attributes.put("fields", renderedFields);
        }
        Map<String, Object> data = resource("incidents", id, attributes.isEmpty() ? null : attributes);
        if (commanderUserId != null) {
            data.put("relationships", Map.of("commander_user", Map.of("data",
                Map.of("type", "users", "id", identifier(context, commanderUserId, "commanderUserId")))));
        }
        if (attributes.isEmpty() && !data.containsKey("relationships")) {
            throw new IllegalArgumentException("Set at least one incident attribute or commanderUserId");
        }
        return send(context, "PATCH", "/api/v2/incidents/" + id, Map.of(), Map.of("data", data), false);
    }
}
