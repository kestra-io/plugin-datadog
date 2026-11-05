package io.kestra.plugin.datadog.slos;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Getter
@NoArgsConstructor
public abstract class AbstractWindowTask extends AbstractDatadogTask {
    @NotNull
    @Schema(title = "SLO identifier")
    private Property<String> sloId;

    @NotNull
    @Schema(title = "Window start", description = "Inclusive Unix epoch seconds. Must be non-negative and earlier than to.")
    private Property<Long> from;

    @NotNull
    @Schema(title = "Window end", description = "Unix epoch seconds, strictly later than from. Datadog enforces retention limits.")
    private Property<Long> to;

    @Schema(title = "Apply status corrections", description = "Send apply_correction=true by default. Set false to evaluate the uncorrected SLI.")
    private Property<Boolean> applyCorrections;
}
