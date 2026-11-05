package io.kestra.plugin.datadog.civisibility;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.logs.AbstractSearch;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/** Datadog v2 operationId: SearchCIAppPipelineEvents. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Search Datadog CI pipelines", description = "Reads pipeline events page by page up to maxResults. Needs ci_visibility_read. Use store to export ION. Incomplete responses fail the task.")
@Plugin(examples = @Example(title = "Export failed pipeline events", full = true, code = """
    id: datadog_ci_pipelines
    namespace: company.observability
    tasks:
      - id: pipelines
        type: io.kestra.plugin.datadog.civisibility.SearchPipelines
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        query: "@ci.status:error"
        store: true
    """))
public class SearchPipelines extends AbstractSearch implements RunnableTask<AbstractSearch.Output> {
    @Override
    public Output run(RunContext context) throws Exception {
        return search(context, "/api/v2/ci/pipelines/events/search", false, false);
    }
}
