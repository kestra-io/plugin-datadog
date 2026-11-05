package io.kestra.plugin.datadog.slos;

import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
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
import java.util.List;

/** Datadog operationId: GetSLOHistory. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Read Datadog SLO history", description = "Returns overall and group SLI history for a window in epoch seconds. Needs slos_read. Top-level query errors fail the task. Check validates the overall data before using it as a gate.")
@Plugin(examples = @Example(title = "Store a week of SLO history", full = true, code = """
    id: datadog_slo_history
    namespace: company.observability
    tasks:
      - id: history
        type: io.kestra.plugin.datadog.slos.History
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        sloId: 0123456789abcdef0123456789abcdef
        from: "{{ now() | dateAdd(-7, 'DAYS') | date('X') }}"
        to: "{{ now() | date('X') }}"
        fetchType: STORE
    """))
public class History extends AbstractWindowTask implements RunnableTask<History.Output> {
    @Schema(title = "Target percentage", description = "Optional history target, strictly between 0 and 100. Sent as the target query parameter.")
    private Property<Double> target;
    @Schema(title = "Fetch type", description = "FETCH returns rows, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only the count.", defaultValue = "FETCH")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Schema(title = "Include sensitive values", description = "Return credential fields and URLs in history attributes. False by default.")
    private Property<Boolean> includeSecrets;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            String id = context.render(getSloId()).as(String.class).orElse(null);
            Slos.path(id);
            Long from = context.render(getFrom()).as(Long.class).orElse(null);
            Long to = context.render(getTo()).as(Long.class).orElse(null);
            Slos.window(from, to);
            Double target = context.render(this.target).as(Double.class).orElse(null);
            if (target != null) {
                Slos.target(target);
            }
            boolean corrections = context.render(getApplyCorrections()).as(Boolean.class).orElse(true);
            var history = Slos.history(client, id, from, to, target, corrections);
            history = io.kestra.plugin.datadog.monitors.MonitorIO.redactMap(history, context.render(includeSecrets).as(Boolean.class).orElse(false));
            var result = DatadogFetch.apply(context, context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH), List.of(history));
            return Output.builder().history(result.rows() == null ? null : result.rows().getFirst()).row(result.row()).uri(result.uri()).size(result.size()).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "SLO history data", description = "The data object with overall, groups, monitors and series when provided. Absent with STORE.")
        private final Map<String, Object> history;
        @Schema(title = "First history response", description = "Present with FETCH_ONE.")
        private final Map<String, Object> row;
        @Schema(title = "Number of responses")
        private final Integer size;
        @Schema(title = "ION history URI", description = "Present with STORE.")
        private final URI uri;
    }
}
