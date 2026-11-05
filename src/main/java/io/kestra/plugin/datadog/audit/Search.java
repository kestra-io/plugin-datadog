package io.kestra.plugin.datadog.audit;

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

/** Datadog v2 operationId: SearchAuditLogs. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Search the Datadog audit trail", description = "Reads audit events page by page up to maxResults. Needs audit trail read permission. Use store to export ION. Incomplete responses fail the task.")
@Plugin(examples = @Example(title = "Export recent audit events", full = true, code = """
    id: datadog_audit
    namespace: company.observability
    tasks:
      - id: audit
        type: io.kestra.plugin.datadog.audit.Search
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        from: now-1h
        maxResults: 10000
        store: true
    """))
public class Search extends AbstractSearch implements RunnableTask<AbstractSearch.Output> {
    @Override
    public Output run(RunContext context) throws Exception {
        return search(context, "/api/v2/audit/events/search", false, false);
    }
}
