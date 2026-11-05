package io.kestra.plugin.datadog.synthetics;

import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogFetch;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Datadog operationIds: v1:GetAPITestLatestResults, v1:GetBrowserTestLatestResults, v1:GetAPITestResult and v1:GetBrowserTestResult. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Read Datadog synthetic test results", description = "Reads the latest results or one full result by ID. Needs synthetics_read. Latest reads return the server window without automatic historical pagination. Secret-bearing details are omitted by default.")
@Plugin(examples = @Example(title = "Read recent API test results", full = true, code = """
    id: synthetic_results
    namespace: company.delivery
    tasks:
      - id: results
        type: io.kestra.plugin.datadog.synthetics.Results
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        publicId: abc-def-ghi
    """))
public class Results extends AbstractDatadogTask implements RunnableTask<Results.Output> {
    @NotNull
    @Schema(title = "Public test ID")
    private Property<String> publicId;
    @Schema(title = "Test kind", defaultValue = "API")
    private Property<TestKind> testKind;
    @Schema(title = "Result ID", description = "Read one full result when set. Otherwise read the latest results.")
    private Property<String> resultId;
    @Schema(title = "Include secrets", description = "Return full records including request data and response bodies. False keeps check_time, probe_dc, result_id and status only.", defaultValue = "false")
    private Property<Boolean> includeSecrets;
    @Schema(title = "Fetch type", description = "FETCH returns rows, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only the count.", defaultValue = "FETCH")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            String id = Synthetics.id(context.render(publicId).as(String.class).orElse(null), "publicId");
            String result = context.render(resultId).as(String.class).orElse(null);
            if (result != null) {
                Synthetics.id(result, "resultId");
            }
            var kind = context.render(testKind).as(TestKind.class).orElse(TestKind.API);
            String path = "/api/v1/synthetics/tests/" + (kind == TestKind.BROWSER ? "browser/" : "") + id + "/results";
            var response = client.get(path + (result == null ? "" : "/" + result), Map.of()).map();
            List<Map<String, Object>> rows = result == null ? Synthetics.rows(response.get("results"), "results") : List.of(response);
            boolean reveal = context.render(includeSecrets).as(Boolean.class).orElse(false);
            var safe = rows.stream().map(row -> {
                if (reveal) {
                    return row;
                }
                Map<String, Object> summary = new LinkedHashMap<>();
                for (String key : List.of("check_time", "probe_dc", "result_id", "status")) {
                    if (row.containsKey(key)) {
                        summary.put(key, row.get(key));
                    }
                }
                return summary;
            }).toList();
            var fetched = DatadogFetch.apply(context, context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH), safe);
            Object timestamp = response.get("last_timestamp_fetched");
            return Output.builder().results(fetched.rows()).row(fetched.row()).size(fetched.size())
                .uri(fetched.uri())
                .lastTimestampFetched(timestamp instanceof Number n ? n.longValue() : null).build();
        }
    }

    public enum TestKind { API, BROWSER }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Results")
        private final List<Map<String, Object>> results;
        @Schema(title = "First result", description = "Present with FETCH_ONE.")
        private final Map<String, Object> row;
        @Schema(title = "Number of results")
        private final Integer size;
        @Schema(title = "ION storage URI")
        private final URI uri;
        @Schema(title = "Server result-window timestamp")
        private final Long lastTimestampFetched;
    }
}
