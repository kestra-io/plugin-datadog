package io.kestra.plugin.datadog.keys;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/** Datadog operationIds: v2:GetAPIKey, v2:GetApplicationKey, v2:CreateAPIKey and v2:CreateCurrentUserApplicationKey. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Create a replacement Datadog key", description = "Checks one old key by ID then creates its replacement. The old key stays active. Deploy and verify the replacement before calling Revoke. Each run creates a new key. Set application scopes explicitly; they are not copied from the old key. Application key ownership is the authenticated user.")
@Plugin(examples = @Example(title = "Prepare an API key rotation", full = true, code = """
    id: datadog_rotate_key
    namespace: company.security
    tasks:
      - id: replacement
        type: io.kestra.plugin.datadog.keys.Rotate
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        oldKeyId: 00000000-0000-0000-0000-000000000001
        name: pipeline-replacement
        includeSecrets: true
    """))
public class Rotate extends Create {
    @NotNull
    @Schema(title = "Old key identifier", description = "ID of the existing key. This is not the key value. The key is read but never revoked by this task.")
    @PluginProperty(group = "main")
    private Property<String> oldKeyId;

    @Override
    public KeyOutput run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var oldId = AccessApi.required(context, oldKeyId, "oldKeyId");
            var kind = kind(context);
            var body = body(context, kind);
            var path = kind.resourcePath(oldId);
            var include = AccessApi.flag(context, getIncludeSecrets());
            AccessApi.request(client, "GET", path, Map.of(), null);
            return KeyOutput.from(AccessApi.json(AccessApi.request(client, "POST", kind.createPath, Map.of(), body)), include);
        }
    }
}
