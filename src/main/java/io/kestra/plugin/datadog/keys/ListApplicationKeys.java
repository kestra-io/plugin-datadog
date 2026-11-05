package io.kestra.plugin.datadog.keys;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.users.PagedTask;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.LinkedHashMap;

/** Datadog operationId: v2:ListApplicationKeys. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "List Datadog application keys", description = "Lists application key metadata and scopes page by page. Needs org_app_keys_read. Credential fields are redacted by default.")
@Plugin(examples = @Example(title = "Audit application key scopes", full = true, code = """
    id: datadog_application_key_inventory
    namespace: company.security
    tasks:
      - id: keys
        type: io.kestra.plugin.datadog.keys.ListApplicationKeys
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        fetchType: STORE
    """))
public class ListApplicationKeys extends PagedTask {
    @Schema(title = "Name filter", description = "Datadog's filter string for application key names.")
    @PluginProperty(group = "processing")
    private Property<String> filter;

    @Override
    public Output run(RunContext context) throws Exception {
        var params = new LinkedHashMap<String, Object>();
        context.render(filter).as(String.class).ifPresent(value -> params.put("filter", value));
        return collect(context, "/api/v2/application_keys", params);
    }
}
