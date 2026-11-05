package io.kestra.plugin.datadog.cases;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.incidents.ResponseOutput;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import java.util.Map;

/** Datadog operationId: GetCase. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Get a Datadog case", description = "Reads one case by id. Requires cases_read.")
@Plugin(examples = @Example(title = "Read a case", full = true, code = """
    id: read_case
    namespace: company.observability
    tasks:
      - id: case
        type: io.kestra.plugin.datadog.cases.Get
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        caseId: "00000000-0000-0000-0000-000000000001"
    """))
public class Get extends CaseTask implements RunnableTask<ResponseOutput> {
    @Override
    public ResponseOutput run(RunContext context) throws Exception {
        return send(context, "GET", casePath(context), Map.of(), null, false);
    }
}
