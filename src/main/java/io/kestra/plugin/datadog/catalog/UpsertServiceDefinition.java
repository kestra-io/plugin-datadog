package io.kestra.plugin.datadog.catalog;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.keys.AccessApi;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Datadog operationIds: v2:GetServiceDefinition and v2:CreateOrUpdateServiceDefinitions. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Apply a Datadog service definition", description = "Creates or updates one v2.2 service definition keyed by dd-service. Compares supplied fields and preserves omitted fields. Supplied arrays replace whole arrays. Returns CREATED, UPDATED or UNCHANGED with a field diff. Use plan for a read-only preview. Needs apm_service_catalog_read and apm_service_catalog_write for writes. Concurrent writers are not serialized by Datadog.")
@Plugin(examples = @Example(title = "Preview a service ownership update", full = true, code = """
    id: datadog_apply_service
    namespace: company.security
    tasks:
      - id: service
        type: io.kestra.plugin.datadog.catalog.UpsertServiceDefinition
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        definition:
          schema-version: v2.2
          dd-service: checkout
          team: payments
          tags: [env:prod]
        plan: true
    """))
public class UpsertServiceDefinition extends AbstractDatadogTask implements RunnableTask<UpsertServiceDefinition.Output> {
    @NotNull
    @Schema(title = "Service definition", description = "One v2.2 JSON object. Requires schema-version and dd-service. Supports the official v2.2 fields. Omitted fields are retained on update.")
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> definition;
    @Schema(title = "Plan changes", description = "Use GET requests to compute a diff without changing the service definition.", defaultValue = "false")
    @PluginProperty(group = "advanced")
    private Property<Boolean> plan;
    @Schema(title = "Include secrets", description = "Include credential and URL values in the diff. These values are redacted by default.", defaultValue = "false")
    @PluginProperty(group = "advanced")
    private Property<Boolean> includeSecrets;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            if (definition == null) { throw new IllegalArgumentException("`definition` is required"); }
            var rDesired = new LinkedHashMap<String, Object>(context.render(definition).asMap(String.class, Object.class));
            ServiceDefinitions.validate(rDesired);
            var service = AccessApi.required(rDesired.get("dd-service"), "definition.dd-service");
            rDesired.put("dd-service", service);
            var path = "/api/v2/services/definitions/" + AccessApi.segment(service);
            var preview = AccessApi.flag(context, plan);
            var include = AccessApi.flag(context, includeSecrets);
            var current = Map.<String, Object>of();
            var exists = true;
            try {
                var data = AccessApi.object(AccessApi.json(AccessApi.request(client, "GET", path, Map.of("schema_version", "v2.2"), null)).get("data"));
                current = AccessApi.object(AccessApi.object(data.get("attributes")).get("schema"));
                if (!service.equals(current.get("dd-service"))) {
                    throw new IllegalStateException("Datadog returned a different service definition");
                }
            } catch (DatadogClient.DatadogException e) {
                if (e.getStatus() != 404) { throw e; }
                exists = false;
            }
            var diff = new ArrayList<Change>();
            ServiceDefinitions.diff("", current, rDesired, diff, include);
            var status = !exists ? Status.CREATED : diff.isEmpty() ? Status.UNCHANGED : Status.UPDATED;
            if (!preview && status != Status.UNCHANGED) {
                AccessApi.request(client, "POST", "/api/v2/services/definitions", Map.of(), ServiceDefinitions.merge(current, rDesired));
            }
            return Output.builder().serviceName(service).status(status).plan(preview).diff(diff).build();
        }
    }

    public enum Status { CREATED, UPDATED, UNCHANGED }

    @Builder
    @Getter
    public static class Change {
        @Schema(title = "Changed field", description = "JSON Pointer path of the supplied field.")
        private final String field;
        @Schema(title = "Previous value", description = "Credential and URL fields are redacted by default.")
        private final Object before;
        @Schema(title = "Desired value", description = "Credential and URL fields are redacted by default.")
        private final Object after;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Service name")
        private final String serviceName;
        @Schema(title = "Apply result", description = "With plan this describes the planned result.")
        private final Status status;
        @Schema(title = "Plan mode")
        private final Boolean plan;
        @Schema(title = "Field changes")
        private final List<Change> diff;
    }
}
