package io.kestra.plugin.datadog.keys;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.users.PagedTask;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/** Operation: v2 ListAPIKeys. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "List Datadog API keys", description = "Lists API key IDs, names and last4 previews page by page. Needs api_keys_read. Credential fields are redacted by default.")
@Plugin(examples = @Example(title = "Audit API keys", full = true, code = """
    id: datadog_api_key_inventory
    namespace: company.security
    tasks:
      - id: keys
        type: io.kestra.plugin.datadog.keys.ListApiKeys
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        store: true
    """))
public class ListApiKeys extends PagedTask {
    @Schema(title = "Name filter", description = "Datadog's filter string for API key names.")
    private Property<String> filter;

    @Override
    public Output run(RunContext context) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        context.render(filter).as(String.class).ifPresent(value -> params.put("filter", value));
        return collect(context, "/api/v2/api_keys", params);
    }
}
