package io.kestra.plugin.datadog.catalog;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.keys.AccessApi;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** Datadog operationId: v2:DeleteServiceDefinition. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Delete one Datadog service definition", description = "Deletes the definition identified by serviceName. This changes catalog metadata and does not stop the service. Needs apm_service_catalog_write.")
@Plugin(examples = @Example(title = "Remove a retired service definition", full = true, code = """
    id: datadog_delete_service
    namespace: company.security
    tasks:
      - id: delete
        type: io.kestra.plugin.datadog.catalog.Delete
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        serviceName: retired-service
        ignoreNotFound: true
    """))
public class Delete extends AbstractDatadogTask implements RunnableTask<Delete.Output> {
    @NotNull
    @Schema(title = "Service name", description = "Stable dd-service name of the definition to delete.")
    private Property<String> serviceName;
    @Schema(title = "Ignore missing definition", description = "Treat HTTP 404 as already absent.", defaultValue = "false")
    private Property<Boolean> ignoreNotFound;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            String path = "/api/v2/services/definitions/" + AccessApi.segment(AccessApi.required(context, serviceName, "serviceName"));
            boolean ignore = AccessApi.flag(context, ignoreNotFound);
            return Output.builder().deleted(AccessApi.delete(client, path, ignore)).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Definition deleted", description = "False when ignoreNotFound accepted HTTP 404.")
        private final Boolean deleted;
    }
}
