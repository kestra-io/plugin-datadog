package io.kestra.plugin.datadog.teams;

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

/** Datadog operationId: v2:ListTeams. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "List Datadog teams", description = "Lists team IDs, handles and membership counts page by page. Needs teams_read.")
@Plugin(examples = @Example(title = "Inventory teams", full = true, code = """
    id: datadog_team_inventory
    namespace: company.security
    tasks:
      - id: teams
        type: io.kestra.plugin.datadog.teams.List
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        keyword: platform
    """))
public class List extends PagedTask {
    @Schema(title = "Keyword filter", description = "Matches team names and handles using Datadog's filter[keyword].")
    private Property<String> keyword;

    @Override
    public Output run(RunContext context) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        context.render(keyword).as(String.class).ifPresent(value -> params.put("filter[keyword]", value));
        return collect(context, "/api/v2/team", params);
    }
}
