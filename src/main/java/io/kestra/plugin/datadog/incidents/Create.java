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

/** Datadog operationId: CreateIncident. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Create a Datadog incident", description = "Opens one incident and can notify responders. Requires incident_write. Dry run is true by default and sends no request.")
@Plugin(examples = @Example(title = "Preview an incident", full = true, code = """
    id: preview_incident
    namespace: company.observability
    tasks:
      - id: incident
        type: io.kestra.plugin.datadog.incidents.Create
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        title: Orders API error rate
        customerImpacted: true
        severity: SEV_2
        dryRun: true
    """))
public class Create extends ResponseSupport implements RunnableTask<ResponseOutput> {
    @NotNull
    @Schema(title = "Incident title", description = "Explicit nonblank title.")
    private Property<String> title;

    @NotNull
    @Schema(title = "Customers impacted", description = "Required explicit customer impact flag.")
    private Property<Boolean> customerImpacted;

    @Schema(title = "Severity")
    private Property<IncidentFields.Severity> severity;

    @Schema(title = "Incident fields", description = "Additional field name to type/value objects. Typed severity takes the severity field exclusively.")
    private Property<Map<String, Object>> fields;

    @Schema(title = "Commander user id")
    private Property<String> commanderUserId;

    @Schema(title = "Customer impact scope")
    private Property<String> customerImpactScope;

    @Schema(title = "Test incident", description = "Sets the API is_test attribute when supplied.")
    private Property<Boolean> isTest;

    @Schema(title = "Dry run", description = "Render the request without opening an incident. True by default.")
    private Property<Boolean> dryRun;

    @Override
    public ResponseOutput run(RunContext context) throws Exception {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("title", required(context, title, "title"));
        attributes.put("customer_impacted", required(context, customerImpacted, Boolean.class, "customerImpacted"));
        context.render(customerImpactScope).as(String.class).ifPresent(v -> attributes.put("customer_impact_scope", v));
        context.render(isTest).as(Boolean.class).ifPresent(v -> attributes.put("is_test", v));
        Map<String, Object> renderedFields = IncidentFields.fields(context.render(fields).asMap(String.class, Object.class),
            context.render(severity).as(IncidentFields.Severity.class).orElse(null), null);
        if (!renderedFields.isEmpty()) {
            attributes.put("fields", renderedFields);
        }
        Map<String, Object> data = resource("incidents", null, attributes);
        if (commanderUserId != null) {
            data.put("relationships", Map.of("commander_user", Map.of("data",
                Map.of("type", "users", "id", identifier(context, commanderUserId, "commanderUserId")))));
        }
        return send(context, "POST", "/api/v2/incidents", Map.of(), Map.of("data", data), dryRun(context, dryRun));
    }
}
