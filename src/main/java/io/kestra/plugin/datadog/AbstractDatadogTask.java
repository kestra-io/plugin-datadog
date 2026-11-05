package io.kestra.plugin.datadog;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@ValidDatadogLimits
public abstract class AbstractDatadogTask extends Task {
    @Schema(
        title = "Datadog API key",
        description = ConnectionDocs.API_KEY
    )
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    private Property<String> apiKey;

    @Schema(
        title = "Datadog application key",
        description = ConnectionDocs.APP_KEY
    )
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    private Property<String> appKey;

    @Schema(
        title = "Datadog site",
        description = ConnectionDocs.SITE
    )
    @PluginProperty(group = "connection")
    private Property<String> site;

    @Schema(
        title = "API base URL",
        description = ConnectionDocs.API_URL
    )
    @PluginProperty(group = "advanced")
    private Property<String> apiUrl;

    /** Builds the client of this task. {@code needsAppKey} is {@code true} for the calls that Datadog does not open to an API key alone. */
    protected DatadogClient client(RunContext runContext, boolean needsAppKey) throws Exception {
        return DatadogClient.create(runContext, apiKey, appKey, site, apiUrl, needsAppKey);
    }
}
