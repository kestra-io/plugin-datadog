package io.kestra.plugin.datadog.oncall;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.incidents.ResponseOutput;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Datadog operationId: CreateOnCallPage. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Page a Datadog On-Call team or user", description = "Creates one page and can wake responders. Requires both keys. Uses the regional On-Call paging host. Dry run is true by default.")
@Plugin(examples = @Example(title = "Preview a page for a team", full = true, code = """
    id: preview_page
    namespace: company.observability
    tasks:
      - id: page
        type: io.kestra.plugin.datadog.oncall.Page
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        title: Orders API needs attention
        targetType: TEAM_HANDLE
        target: orders-oncall
        urgency: HIGH
        tags: ["service:orders", "source:kestra"]
        dryRun: true
    """))
public class Page extends PagingTask implements RunnableTask<ResponseOutput> {
    @NotNull
    @Schema(title = "Page title", description = "Explicit nonblank title.")
    @PluginProperty(group = "main")
    private Property<String> title;

    @NotNull
    @Schema(title = "Target kind", description = "A team id, team handle or user id.")
    @PluginProperty(group = "main")
    private Property<TargetType> targetType;

    @NotNull
    @Schema(title = "Target identifier", description = "Nonblank identifier for the selected target kind.")
    @PluginProperty(group = "main")
    private Property<String> target;

    @NotNull
    @Schema(title = "Urgency", description = "Explicit HIGH or LOW urgency.")
    @PluginProperty(group = "main")
    private Property<Urgency> urgency;

    @Schema(title = "Tags")
    @PluginProperty(group = "processing")
    private Property<List<String>> tags;

    @Schema(title = "Dry run", description = "Render the exact request without paging anyone. True by default.")
    @PluginProperty(group = "advanced")
    private Property<Boolean> dryRun;

    public enum TargetType {
        TEAM_ID, TEAM_HANDLE, USER_ID
    }

    public enum Urgency {
        LOW, HIGH
    }

    @Override
    public ResponseOutput run(RunContext context) throws Exception {
        var attributes = new LinkedHashMap<String, Object>();
        attributes.put("title", required(context, title, "title"));
        attributes.put("target", Map.of("type", required(context, targetType, TargetType.class, "targetType").name().toLowerCase(Locale.ROOT),
            "identifier", required(context, target, "target")));
        attributes.put("urgency", required(context, urgency, Urgency.class, "urgency").name().toLowerCase(Locale.ROOT));
        if (tags != null) {
            attributes.put("tags", context.render(tags).asList(String.class));
        }
        try (var client = client(context, true)) {
            return send(context, client, pagingBase(context, client), "POST", "/api/v2/on-call/pages", Map.of(),
                Map.of("data", resource("pages", null, attributes)), dryRun(context, dryRun));
        }
    }
}
