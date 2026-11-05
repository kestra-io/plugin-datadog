package io.kestra.plugin.datadog.logs;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
@NoArgsConstructor
public abstract class AbstractCount extends AbstractWindow {
    @NotNull
    @Schema(title = "Threshold", description = "Nonnegative count boundary. Breached only when count is strictly greater than this value.")
    private Property<Long> threshold;

    @Builder.Default
    @Schema(title = "Fail on breach", description = "Fail the task when the count exceeds threshold. Otherwise return count and breached.", defaultValue = "false")
    private Property<Boolean> failOnBreach = Property.ofValue(false);

    protected Output count(RunContext context, boolean spans) throws Exception {
        long boundary = Counts.threshold(context.render(threshold).as(Long.class).orElse(null));
        var filter = filter(context);
        long count = Counts.query(client(context, true), filter, spans);
        boolean breached = Counts.breached(count, boundary);
        if (breached && context.render(failOnBreach).as(Boolean.class).orElse(false)) {
            throw new IllegalStateException("Datadog count " + count + " exceeds threshold " + boundary);
        }
        return Output.builder().count(count).breached(breached).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Matching event count")
        private final Long count;

        @Schema(title = "Threshold breached", description = "True only when count is greater than threshold.")
        private final Boolean breached;
    }
}
