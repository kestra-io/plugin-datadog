package io.kestra.plugin.datadog.synthetics;

import io.kestra.plugin.datadog.ValidDatadogLimits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogFetch;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.Map;

/** Datadog operationIds: v1:ListTests and v1:SearchTests. */
@ValidDatadogLimits
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "List Datadog synthetic tests", description = "Reads tests page by page, optionally using Datadog search text. Needs synthetics_read. Default outputs omit test configuration and other secret-bearing content.")
@Plugin(examples = @Example(title = "Find production tests", full = true, code = """
    id: list_synthetics
    namespace: company.delivery
    tasks:
      - id: tests
        type: io.kestra.plugin.datadog.synthetics.List
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        searchQuery: tag:env:prod
        fetchType: STORE
    """))
public class List extends AbstractDatadogTask implements RunnableTask<List.Output> {
    @Schema(title = "Search query", description = "Uses SearchTests when nonblank. Otherwise uses ListTests.")
    private Property<String> searchQuery;
    @Schema(title = "Maximum tests", description = "Limit from 1 to 10000. A result at the limit can be truncated.", defaultValue = "1000")
    private Property<@Min(1) @Max(10000) Integer> maxTests;
    @Schema(title = "Fetch type", description = "FETCH returns rows, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only the count.", defaultValue = "FETCH")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);
    @Schema(title = "Include secrets", description = "Return the complete API records. False returns only public_id, name, type, subtype, status, tags, locations and monitor_id.", defaultValue = "false")
    private Property<Boolean> includeSecrets;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            int limit = Synthetics.limit(context.render(maxTests).as(Integer.class).orElse(1000));
            boolean reveal = context.render(includeSecrets).as(Boolean.class).orElse(false);
            String search = context.render(searchQuery).as(String.class).orElse("");
            var tests = Synthetics.collect(client, search, limit).stream().map(row -> Synthetics.summary(row, reveal)).toList();
            var result = DatadogFetch.apply(context, context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH), tests);
            return Output.builder().tests(result.rows()).row(result.row()).size(result.size())
                .uri(result.uri()).limitReached(tests.size() == limit).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Tests")
        private final java.util.List<Map<String, Object>> tests;
        @Schema(title = "First test", description = "Present with FETCH_ONE.")
        private final Map<String, Object> row;
        @Schema(title = "Number of tests")
        private final Integer size;
        @Schema(title = "ION storage URI")
        private final URI uri;
        @Schema(title = "Selection limit reached")
        private final Boolean limitReached;
    }
}
