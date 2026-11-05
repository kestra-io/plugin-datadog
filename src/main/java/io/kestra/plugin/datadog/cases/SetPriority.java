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

/** Datadog operationId: UpdatePriority. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Set a Datadog case priority", description = "Changes the priority of one case. Requires cases_write.")
@Plugin(examples = @Example(title = "Raise case priority", full = true, code = """
    id: case_priority
    namespace: company.observability
    tasks:
      - id: prioritize
        type: io.kestra.plugin.datadog.cases.SetPriority
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        caseId: "00000000-0000-0000-0000-000000000001"
        priority: P1
    """))
public class SetPriority extends CaseTask implements RunnableTask<ResponseOutput> {
    @NotNull
    @Schema(title = "Priority")
    private Property<Priority> priority;

    @Override
    public ResponseOutput run(RunContext context) throws Exception {
        return update(context, "priority", Map.of("priority", required(context, priority, Priority.class, "priority").name()));
    }
}
