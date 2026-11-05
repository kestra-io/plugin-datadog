package io.kestra.plugin.datadog.cases;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.incidents.CollectionOutput;
import io.kestra.plugin.datadog.incidents.PaginatedResponseTask;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.LinkedHashMap;
import java.util.Locale;

/** Datadog operationId: SearchCases. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Search Datadog cases", description = "Reads cases using numbered pagination and server-side filters. Requires cases_read. Supports ION storage.")
@Plugin(examples = @Example(title = "Find open cases", full = true, code = """
    id: search_cases
    namespace: company.observability
    tasks:
      - id: cases
        type: io.kestra.plugin.datadog.cases.Search
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        filter: "status:OPEN"
        sortField: CREATED_AT
    """))
public class Search extends PaginatedResponseTask implements RunnableTask<CollectionOutput> {
    @Schema(title = "Case search filter")
    @PluginProperty(group = "processing")
    private Property<String> filter;

    @Schema(title = "Sort field")
    @PluginProperty(group = "processing")
    private Property<SortField> sortField;

    @Schema(title = "Sort ascending")
    @PluginProperty(group = "main")
    private Property<Boolean> ascending;

    public enum SortField {
        CREATED_AT, PRIORITY, STATUS
    }

    @Override
    public CollectionOutput run(RunContext context) throws Exception {
        var params = new LinkedHashMap<String, Object>();
        context.render(filter).as(String.class).ifPresent(v -> params.put("filter", v));
        context.render(sortField).as(SortField.class).ifPresent(v -> params.put("sort[field]", v.name().toLowerCase(Locale.ROOT)));
        context.render(ascending).as(Boolean.class).ifPresent(v -> params.put("sort[asc]", v));
        return collect(context, "/api/v2/cases", params, "page[size]", true, false);
    }
}
