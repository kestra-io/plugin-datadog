package io.kestra.plugin.datadog.synthetics;

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

import java.util.Map;

/** Datadog operationId: v1:DeleteTests, restricted to one public ID without forced dependency deletion. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Delete one Datadog synthetic test", description = "Permanently deletes one test by public ID. Does not force removal of dependencies. Needs synthetics_write.")
@Plugin(examples = @Example(title = "Delete a temporary smoke test", full = true, code = """
    id: delete_synthetic
    namespace: company.delivery
    tasks:
      - id: delete
        type: io.kestra.plugin.datadog.synthetics.Delete
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        publicId: abc-def-ghi
        ignoreNotFound: true
    """))
public class Delete extends AbstractDatadogTask implements RunnableTask<Delete.Output> {
    @NotNull
    @Schema(title = "Public test ID")
    private Property<String> publicId;
    @Schema(title = "Ignore missing test", description = "Return deleted=false for HTTP 404 or an empty deletion response.", defaultValue = "false")
    private Property<Boolean> ignoreNotFound;

    @Override
    public Output run(RunContext context) throws Exception {
        String id = Synthetics.id(context.render(publicId).as(String.class).orElse(null), "publicId");
        boolean ignore = context.render(ignoreNotFound).as(Boolean.class).orElse(false);
        try {
            var response = client(context, true).post("/api/v1/synthetics/tests/delete", Map.of("public_ids", java.util.List.of(id), "force_delete_dependencies", false)).map();
            boolean deleted = Synthetics.rows(response.get("deleted_tests"), "deleted_tests").stream().anyMatch(row -> id.equals(row.get("public_id")));
            if (!deleted && !ignore) {
                throw new IllegalStateException("Datadog did not report deletion of test " + id);
            }
            return Output.builder().publicId(id).deleted(deleted).build();
        } catch (DatadogClient.DatadogException e) {
            if (e.getStatus() != 404 || !ignore) {
                throw e;
            }
            return Output.builder().publicId(id).deleted(false).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Test ID")
        private final String publicId;
        @Schema(title = "Test was deleted")
        private final Boolean deleted;
    }
}
