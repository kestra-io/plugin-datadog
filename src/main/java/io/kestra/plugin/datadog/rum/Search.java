package io.kestra.plugin.datadog.rum;

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

/** Datadog v2 operationId: SearchRUMEvents. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Search Datadog RUM events", description = "Reads RUM events page by page up to maxResults. Needs RUM read permission. Use store to export ION. Incomplete responses fail the task.")
@Plugin(examples = @Example(title = "Export browser errors", full = true, code = """
    id: datadog_rum
    namespace: company.observability
    tasks:
      - id: errors
        type: io.kestra.plugin.datadog.rum.Search
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        query: "@type:error"
        from: now-15m
        fetchType: STORE
    """))
public class Search extends AbstractSearch implements RunnableTask<AbstractSearch.Output> {
    @Override
    public Output run(RunContext context) throws Exception {
        return search(context, "/api/v2/rum/events/search", false, false);
    }
}
