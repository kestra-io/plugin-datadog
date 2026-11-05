package io.kestra.plugin.datadog.synthetics;

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
    private Property<Map<String, Object>> definition;
    @Schema(title = "Include secrets", description = "Return the complete answer. False omits configuration, options, steps and notification messages.", defaultValue = "false")
    private Property<Boolean> includeSecrets;

    protected Map<String, Object> definition(RunContext context) throws Exception {
        Map<String, Object> body = context.render(definition).asMap(String.class, Object.class);
        for (String key : List.of("name", "message", "type")) {
            if (!(body.get(key) instanceof String)) {
                throw new IllegalArgumentException("`definition." + key + "` must be a string");
            }
        }
        if (!List.of("api", "browser").contains(body.get("type"))) {
            throw new IllegalArgumentException("`definition.type` must be api or browser");
        }
        for (String key : List.of("config", "options")) {
            if (!(body.get(key) instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("`definition." + key + "` must be an object");
            }
        }
        if (!(body.get("locations") instanceof List<?> locations) || locations.stream().anyMatch(item -> !(item instanceof String))) {
            throw new IllegalArgumentException("`definition.locations` must be an array of strings");
        }
        if ("browser".equals(body.get("type"))) {
            var config = (Map<?, ?>) body.get("config");
            if (!(config.get("request") instanceof Map<?, ?>) || !(config.get("assertions") instanceof List<?>)) {
                throw new IllegalArgumentException("Browser config requires a request object and assertions array");
            }
        }
        return body;
    }
}
