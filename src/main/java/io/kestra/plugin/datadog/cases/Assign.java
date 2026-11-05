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

/** Datadog operationId: AssignCase. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Assign a Datadog case", description = "Assigns one case to the specified user. Requires cases_write.")
@Plugin(examples = @Example(title = "Assign incident follow-up", full = true, code = """
    id: assign_case
    namespace: company.observability
    tasks:
      - id: assign
        type: io.kestra.plugin.datadog.cases.Assign
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        caseId: "00000000-0000-0000-0000-000000000001"
        assigneeId: "00000000-0000-0000-0000-000000000002"
    """))
public class Assign extends CaseTask implements RunnableTask<ResponseOutput> {
    @NotNull
    @Schema(title = "Assignee user id")
    private Property<String> assigneeId;

    @Override
    public ResponseOutput run(RunContext context) throws Exception {
        return update(context, "assign", Map.of("assignee_id", identifier(context, assigneeId, "assigneeId")));
    }
}
