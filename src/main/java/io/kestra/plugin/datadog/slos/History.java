package io.kestra.plugin.datadog.slos;

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
import java.util.Map;

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
        store: true
    """))
public class History extends AbstractWindowTask implements RunnableTask<History.Output> {
    @Schema(title = "Target percentage", description = "Optional history target, strictly between 0 and 100. Sent as the target query parameter.")
    private Property<Double> target;
    @Schema(title = "Store history", description = "Return a JSON internal-storage URI instead of inline history. False by default.")
    private Property<Boolean> store;

    @Override
    public Output run(RunContext context) throws Exception {
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
        boolean stored = context.render(store).as(Boolean.class).orElse(false);
        var history = Slos.history(client(context, true), id, from, to, target, corrections);
        return Output.builder().history(stored ? null : history).uri(stored ? Slos.store(context, history, false) : null).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "SLO history data", description = "The data object with overall, groups, monitors and series when provided. Absent when store is true.")
        private final Map<String, Object> history;
        @Schema(title = "JSON history URI", description = "Present when store is true.")
        private final URI uri;
    }
}
