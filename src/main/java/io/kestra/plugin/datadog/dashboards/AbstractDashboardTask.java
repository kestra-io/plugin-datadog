package io.kestra.plugin.datadog.dashboards;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Getter
@NoArgsConstructor
public abstract class AbstractDashboardTask extends AbstractDatadogTask {
    @Schema(title = "Include secrets", description = "Include known credential fields and webhook URLs in dashboard outputs, diffs and exported files. False by default.")
    private Property<Boolean> includeSecrets;
}
