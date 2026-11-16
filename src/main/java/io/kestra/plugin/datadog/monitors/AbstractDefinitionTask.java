package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Map;

@SuperBuilder
@Getter
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public abstract class AbstractDefinitionTask extends AbstractDatadogTask {
    @NotNull
    @Schema(title = "Monitor definition", description = "Datadog v1 monitor JSON object. Use the API field names, including nested `type`, `query`, `name`, `message`, `tags` and `options`. Known server fields are removed. Datadog validates fields and monitor types.")
    @PluginProperty(group = "main")
    @ToString.Exclude
    private Property<Map<String, Object>> definition;

    protected Map<String, Object> definition(RunContext context) throws Exception {
        return MonitorDefinitions.validate(context.render(definition).asMap(String.class, Object.class));
    }
}
