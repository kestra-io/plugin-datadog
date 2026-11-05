package io.kestra.plugin.datadog.users;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/** Datadog operationId: v2:ListUsers. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "List Datadog users", description = "Lists user access metadata, disabled status and role relationships page by page. Includes service accounts. Needs user_access_read.")
@Plugin(examples = @Example(title = "Audit organization access", full = true, code = """
    id: datadog_user_inventory
    namespace: company.security
    tasks:
      - id: users
        type: io.kestra.plugin.datadog.users.List
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        store: true
    """))
public class List extends PagedTask {
    @Schema(title = "User filter", description = "Datadog's filter for user names, handles or emails.")
    private Property<String> filter;

    @Override
    public Output run(RunContext context) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        context.render(filter).as(String.class).ifPresent(value -> params.put("filter", value));
        return collect(context, "/api/v2/users", params);
    }
}
