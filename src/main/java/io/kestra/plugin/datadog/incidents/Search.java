package io.kestra.plugin.datadog.incidents;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import java.util.LinkedHashMap;
import java.util.Map;

/** Datadog operationId: SearchIncidents. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Search Datadog incidents", description = "Searches incidents and follows offset pagination. Requires incident_read. Returns the incidents from data.attributes.incidents.")
@Plugin(examples = @Example(title = "Search active incidents", full = true, code = """
    id: search_incidents
    namespace: company.observability
    tasks:
      - id: incidents
        type: io.kestra.plugin.datadog.incidents.Search
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        query: "state:active"
        sort: CREATED_DESC
    """))
public class Search extends PaginatedResponseTask implements RunnableTask<CollectionOutput> {
    @NotNull
    @Schema(title = "Search query")
    private Property<String> query;

    @Schema(title = "Sort order")
    private Property<Sort> sort;

    public enum Sort {
        CREATED_ASC, CREATED_DESC
    }

    @Override
    public CollectionOutput run(RunContext context) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("query", required(context, query, "query"));
        context.render(sort).as(Sort.class).ifPresent(v -> params.put("sort", v == Sort.CREATED_ASC ? "created" : "-created"));
        return collect(context, "/api/v2/incidents/search", params, "page[size]", false, true);
    }
}
