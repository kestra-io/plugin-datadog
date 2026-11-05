package io.kestra.plugin.datadog.keys;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Operations: v2 CreateAPIKey and CreateCurrentUserApplicationKey. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Create a Datadog key", description = "Creates an API key or an application key owned by the authenticated user. Returns a redacted value unless includeSecrets is true. Each run creates a new key. Needs api_keys_write or user_app_keys.")
@Plugin(examples = @Example(title = "Create a scoped application key", full = true, code = """
    id: datadog_create_key
    namespace: company.security
    tasks:
      - id: create
        type: io.kestra.plugin.datadog.keys.Create
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        keyKind: APPLICATION
        name: pipeline-reader
        scopes: [user_access_read]
    """))
public class Create extends AbstractDatadogTask implements RunnableTask<KeyOutput> {
    @NotNull
    @Schema(title = "Key name", description = "Name of the new key. Must not be blank.")
    private Property<String> name;
    @Schema(title = "Key kind", description = "API or APPLICATION. Application keys are created for the authenticated user.", defaultValue = "API")
    private Property<KeyKind> keyKind;
    @Schema(title = "Application key scopes", description = "Required for APPLICATION. An explicit empty list creates a key with no scopes. Not accepted for API keys.")
    private Property<List<String>> scopes;
    @Schema(title = "Include secrets", description = "Return the newly created key value. False returns only a mask, preview and resource metadata.", defaultValue = "false")
    private Property<Boolean> includeSecrets;

    protected KeyKind kind(RunContext context) throws Exception {
        return context.render(keyKind).as(KeyKind.class).orElse(KeyKind.API);
    }

    protected Map<String, Object> body(RunContext context, KeyKind kind) throws Exception {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", AccessApi.required(context, name, "name"));
        if (kind == KeyKind.APPLICATION) {
            if (scopes == null) {
                throw new IllegalArgumentException("`scopes` must be explicit for an application key");
            }
            List<?> values = AccessApi.explicitList(context, scopes, "scopes");
            for (Object scope : values) {
                AccessApi.required(scope, "scope");
            }
            attributes.put("scopes", values);
        } else if (scopes != null) {
            throw new IllegalArgumentException("`scopes` applies only to application keys");
        }
        return Map.of("data", Map.of("type", kind.resourceType, "attributes", attributes));
    }

    @Override
    public KeyOutput run(RunContext context) throws Exception {
        KeyKind kind = kind(context);
        Map<String, Object> body = body(context, kind);
        boolean include = AccessApi.flag(context, includeSecrets);
        return KeyOutput.from(AccessApi.json(AccessApi.request(client(context, true), "POST", kind.createPath, Map.of(), body)), include);
    }
}
