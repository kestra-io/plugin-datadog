package io.kestra.plugin.datadog.statuspages;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.incidents.ResponseOutput;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

/** Datadog operationId: UpdateDegradation. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Update or resolve a status page degradation", description = "PATCHes supplied attributes of one degradation. Set status to RESOLVED to resolve it. Requires status_pages_incident_write. Dry run is true by default.")
@Plugin(examples = @Example(title = "Preview degradation resolution", full = true, code = """
    id: resolve_degradation
    namespace: company.observability
    tasks:
      - id: resolve
        type: io.kestra.plugin.datadog.statuspages.UpdateDegradation
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        pageId: "00000000-0000-0000-0000-000000000001"
        degradationId: "00000000-0000-0000-0000-000000000002"
        status: RESOLVED
        message: The service has recovered.
        dryRun: true
    """))
public class UpdateDegradation extends DegradationTask implements RunnableTask<ResponseOutput> {
    @NotNull
    @Schema(title = "Degradation id")
    private Property<String> degradationId;

    @Override
    public ResponseOutput run(RunContext context) throws Exception {
        return publish(context, uuid(context, degradationId, "degradationId"));
    }
}
