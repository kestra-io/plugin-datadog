package io.kestra.plugin.datadog.statuspages;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.incidents.CollectionOutput;
import io.kestra.plugin.datadog.incidents.PaginatedResponseTask;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import java.util.LinkedHashMap;
import java.util.Map;

/** Datadog operationId: ListStatusPages. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "List Datadog status pages", description = "Lists status pages with offset paging. Requires status_pages_settings_read. Supports ION storage.")
@Plugin(examples = @Example(title = "Find a status page", full = true, code = """
    id: list_status_pages
    namespace: company.observability
    tasks:
      - id: pages
        type: io.kestra.plugin.datadog.statuspages.List
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        name: Orders
    """))
public class List extends PaginatedResponseTask implements RunnableTask<CollectionOutput> {
    @Schema(title = "Page name filter")
    private Property<String> name;

    @Schema(title = "Domain prefix filter")
    private Property<String> domainPrefix;

    @Override
    public CollectionOutput run(RunContext context) throws Exception {
        Map<String, Object> query = new LinkedHashMap<>();
        context.render(name).as(String.class).ifPresent(v -> query.put("filter[name]", v));
        context.render(domainPrefix).as(String.class).ifPresent(v -> query.put("filter[domain_prefix]", v));
        return collect(context, "/api/v2/statuspages", query, "page[limit]", false, false);
    }
}
