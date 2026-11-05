package io.kestra.plugin.datadog.events;

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

/** Datadog v2 operationId: SearchEvents. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Search Datadog events", description = "Reads v2 events page by page up to maxResults. Needs events_read. Use store to export ION. Incomplete responses fail the task.")
@Plugin(examples = @Example(title = "Read deployment events", full = true, code = """
    id: datadog_events
    namespace: company.observability
    tasks:
      - id: events
        type: io.kestra.plugin.datadog.events.Search
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        query: "tags:deployment"
        from: now-1h
    """))
public class Search extends AbstractSearch implements RunnableTask<AbstractSearch.Output> {
    @Override
    public Output run(RunContext context) throws Exception {
        return search(context, "/api/v2/events/search", false, false);
    }
}
