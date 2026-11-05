package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Map;

@SuperBuilder
@Getter
@NoArgsConstructor
public abstract class AbstractDefinitionTask extends AbstractMonitorTask {
    @NotNull
    @Schema(title = "Monitor definition", description = "Datadog v1 monitor JSON object. Use the API field names, including nested `type`, `query`, `name`, `message`, `tags` and `options`. Create and Validate require `type` and `query`. Server fields are removed. Datadog validates type-specific options.")
    private Property<Map<String, Object>> definition;

    protected Map<String, Object> definition(RunContext context, boolean complete) throws Exception {
        return MonitorDefinitions.validate(context.render(definition).asMap(String.class, Object.class), complete);
    }
}
