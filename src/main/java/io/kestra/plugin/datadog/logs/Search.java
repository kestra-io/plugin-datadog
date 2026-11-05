package io.kestra.plugin.datadog.logs;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** Datadog v2 operationId: ListLogs. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Search Datadog logs", description = "Reads logs page by page up to maxResults. Needs logs_read_data. Use store to export ION. Incomplete responses fail the task.")
@Plugin(examples = @Example(title = "Export recent error logs", full = true, code = """
    id: datadog_error_logs
    namespace: company.observability
    tasks:
      - id: errors
        type: io.kestra.plugin.datadog.logs.Search
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        query: "service:orders status:error"
        from: now-15m
        maxResults: 1000
        store: true
    """))
public class Search extends AbstractSearch implements RunnableTask<Search.Output> {
    @Schema(title = "Indexes", description = "Log indexes to search. All indexes when omitted.")
    private Property<List<String>> indexes;

    @Override
    protected Map<String, Object> filter(RunContext context) throws Exception {
        Map<String, Object> filter = super.filter(context);
        List<String> values = context.render(indexes).asList(String.class);
        if (!values.isEmpty()) {
            filter.put("indexes", values);
        }
        return filter;
    }

    @Override
    public Output run(RunContext context) throws Exception {
        AbstractSearch.Output result = search(context, "/api/v2/logs/events/search", false, true);
        return Output.builder().logs(result.getEvents()).size(result.getSize()).uri(result.getUri()).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Log events", description = "Flattened attributes with the event id. Null when store is true.")
        private final List<Map<String, Object>> logs;

        @Schema(title = "Number of events")
        private final Integer size;

        @Schema(title = "Stored ION file", description = "Internal storage URI when store is true.")
        private final URI uri;
    }
}
