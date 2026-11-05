package io.kestra.plugin.datadog.cases;

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
import java.util.Map;

/** Datadog operationId: UpdateStatus. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Set a Datadog case status", description = "Changes one case to an existing status name for its case type. Uses status_name instead of the deprecated status enum. Requires cases_write.")
@Plugin(examples = @Example(title = "Close a case", full = true, code = """
    id: close_case
    namespace: company.observability
    tasks:
      - id: close
        type: io.kestra.plugin.datadog.cases.SetStatus
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        caseId: "00000000-0000-0000-0000-000000000001"
        statusName: Closed
    """))
public class SetStatus extends CaseTask implements RunnableTask<ResponseOutput> {
    @NotNull
    @Schema(title = "Status name")
    private Property<String> statusName;

    @Override
    public ResponseOutput run(RunContext context) throws Exception {
        return update(context, "status", Map.of("status_name", required(context, statusName, "statusName")));
    }
}
