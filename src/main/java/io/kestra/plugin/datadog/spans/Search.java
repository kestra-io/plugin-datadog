package io.kestra.plugin.datadog.spans;

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

/** Datadog v2 operationId: ListSpans. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Search Datadog spans", description = "Reads retained spans page by page up to maxResults. Needs apm_read. Use store to export ION. Incomplete responses fail the task.")
@Plugin(examples = @Example(title = "Export service spans", full = true, code = """
    id: datadog_spans
    namespace: company.observability
    tasks:
      - id: spans
        type: io.kestra.plugin.datadog.spans.Search
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        query: "service:orders"
        from: now-15m
        fetchType: STORE
    """))
public class Search extends AbstractSearch implements RunnableTask<AbstractSearch.Output> {
    @Override
    public Output run(RunContext context) throws Exception {
        return search(context, "/api/v2/spans/events/search", true, false);
    }
}
