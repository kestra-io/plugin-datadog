package io.kestra.plugin.datadog.oncall;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.incidents.ResponseOutput;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

/** Datadog operationId: ResolveOnCallPage. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Resolve a Datadog On-Call page", description = "Resolves one page. HTTP 202 confirms acceptance. Requires both keys. Dry run is true by default.")
@Plugin(examples = @Example(title = "Preview page resolution", full = true, code = """
    id: resolve_page
    namespace: company.observability
    tasks:
      - id: resolve
        type: io.kestra.plugin.datadog.oncall.Resolve
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        pageId: "00000000-0000-0000-0000-000000000001"
        dryRun: true
    """))
public class Resolve extends PageAction implements RunnableTask<ResponseOutput> {
    @Override
    public ResponseOutput run(RunContext context) throws Exception {
        return act(context, "resolve");
    }
}
