package io.kestra.plugin.datadog.synthetics;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Map;

@SuperBuilder
@Getter
@NoArgsConstructor
public abstract class AbstractWriteTest extends AbstractDatadogTask {
    @NotNull
    @Schema(title = "Test definition", description = "Complete API or browser test object. Requires name, message, config, locations, options and type. Nested configuration is passed through to Datadog.")
    @ToString.Exclude
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> definition;
    @Schema(title = "Include secrets", description = "Return the complete answer. False omits configuration, options, steps and notification messages.", defaultValue = "false")
    @PluginProperty(group = "advanced")
    private Property<Boolean> includeSecrets;

    protected Map<String, Object> definition(RunContext context) throws Exception {
        var rBody = context.render(definition).asMap(String.class, Object.class);
        for (var key : List.of("name", "message", "type")) {
            if (!(rBody.get(key) instanceof String)) {
                throw new IllegalArgumentException("`definition." + key + "` must be a string");
            }
        }
        if (!List.of("api", "browser").contains(rBody.get("type"))) {
            throw new IllegalArgumentException("`definition.type` must be api or browser");
        }
        for (var key : List.of("config", "options")) {
            if (!(rBody.get(key) instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("`definition." + key + "` must be an object");
            }
        }
        if (!(rBody.get("locations") instanceof List<?> locations) || locations.stream().anyMatch(item -> !(item instanceof String))) {
            throw new IllegalArgumentException("`definition.locations` must be an array of strings");
        }
        if ("browser".equals(rBody.get("type"))) {
            var config = (Map<?, ?>) rBody.get("config");
            if (!(config.get("request") instanceof Map<?, ?>) || !(config.get("assertions") instanceof List<?>)) {
                throw new IllegalArgumentException("Browser config requires a request object and assertions array");
            }
        }
        return rBody;
    }
}
