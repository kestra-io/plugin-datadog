package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Getter
@NoArgsConstructor
public abstract class AbstractMonitorTask extends AbstractDatadogTask {
    @Schema(title = "Include sensitive output values", description = "False by default. Removes values under credential-like keys and HTTP URLs from returned data and diffs. Set true only when the execution output may contain these values.")
    private Property<Boolean> includeSecrets;

    protected boolean includeSecrets(RunContext context) throws Exception {
        return context.render(includeSecrets).as(Boolean.class).orElse(false);
    }
}
