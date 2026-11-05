package io.kestra.plugin.datadog.slos;

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

/** Datadog operationId: DeleteSLO. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Delete one Datadog SLO", description = "Deletes only the identified SLO. Needs slos_write. Does not force deletion when dependent monitors block it. HTTP 404 fails unless ignoreNotFound is true.")
@Plugin(examples = @Example(title = "Delete a retired SLO", full = true, code = """
    id: datadog_slo_delete
    namespace: company.observability
    tasks:
      - id: remove
        type: io.kestra.plugin.datadog.slos.Delete
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        sloId: 0123456789abcdef0123456789abcdef
        ignoreNotFound: true
    """))
public class Delete extends AbstractDatadogTask implements RunnableTask<Delete.Output> {
    @NotNull
    @Schema(title = "SLO identifier")
    private Property<String> sloId;
    @Schema(title = "Ignore a missing SLO", description = "Treat HTTP 404 as a no-op. False by default.")
    private Property<Boolean> ignoreNotFound;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            String id = context.render(sloId).as(String.class).orElse(null);
            String path = Slos.path(id);
            boolean ignore = context.render(ignoreNotFound).as(Boolean.class).orElse(false);
            try {
                var response = client.delete(path).map();
                Object errors = response.get("errors");
                if (errors != null && !(errors instanceof java.util.Map<?, ?> map && map.isEmpty())) {
                    throw new IllegalStateException("Datadog reported errors deleting the SLO");
                }
                if (!(response.get("data") instanceof java.util.List<?> ids) || !ids.contains(id)) {
                    throw new IllegalStateException("Datadog did not confirm deletion of the requested SLO");
                }
                return Output.builder().sloId(id).deleted(true).build();
            } catch (DatadogClient.DatadogException e) {
                if (!ignore || e.getStatus() != 404) {
                    throw e;
                }
                return Output.builder().sloId(id).deleted(false).build();
            }
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "SLO identifier")
        private final String sloId;
        @Schema(title = "SLO was deleted", description = "False for an ignored HTTP 404.")
        private final Boolean deleted;
    }
}
