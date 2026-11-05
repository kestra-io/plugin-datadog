package io.kestra.plugin.datadog.incidents;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.LinkedHashMap;

/** Datadog operationId: ListIncidents. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "List Datadog incidents", description = "Reads incidents with offset paging up to maxResults. Requires incident_read. Supports ION storage.")
@Plugin(examples = @Example(title = "Store incidents", full = true, code = """
    id: list_incidents
    namespace: company.observability
    tasks:
      - id: incidents
        type: io.kestra.plugin.datadog.incidents.List
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        fetchType: STORE
    """))
public class List extends PaginatedResponseTask implements RunnableTask<CollectionOutput> {
    @Override
    public CollectionOutput run(RunContext context) throws Exception {
        return collect(context, "/api/v2/incidents", new LinkedHashMap<>(), "page[size]", false, false);
    }
}
