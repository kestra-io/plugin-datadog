package io.kestra.plugin.datadog.dashboards;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Datadog operationIds: ListDashboards, GetDashboard, CreateDashboard and UpdateDashboard. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Apply a Datadog dashboard definition", description = "Matches one dashboard by id or exact title plus managingTag. Creates or updates it only when needed and reports a field-level diff. Needs dashboards_read and dashboards_write for writes. plan performs reads only. Serialize imports for the same identity.")
@Plugin(examples = @Example(title = "Apply a team-owned dashboard", full = true, code = """
    id: datadog_dashboard_import
    namespace: company.observability
    tasks:
      - id: apply
        type: io.kestra.plugin.datadog.dashboards.Import
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        managingTag: team:platform
        plan: true
        definition:
          title: Orders
          layout_type: ordered
          tags: ["team:platform"]
          widgets: []
    """))
public class Import extends AbstractDashboardTask implements RunnableTask<Import.Output> {
    @Schema(title = "Existing dashboard identifier", description = "Updates this exact dashboard. HTTP 404 fails instead of creating a different id. Mutually exclusive with managingTag.")
    private Property<String> dashboardId;
    @Schema(title = "Managing team tag", description = "Used with the exact definition title when dashboardId is absent. Must be team:<name> and present in definition.tags. Ambiguous matches fail.")
    private Property<String> managingTag;
    @Schema(title = "JSON definition URI", description = "A kestra:/// internal-storage file, for example Export.uri. Supply from or definition, exactly one.")
    private Property<String> from;
    @PluginProperty(dynamic = true, group = "main")
    @ToString.Exclude
    @Schema(title = "Desired dashboard definition", description = "Requires title, layout_type and widgets. Omitted top-level fields are preserved on update. Provided nested objects and arrays are replaced.")
    private Map<String, Object> definition;
    @Schema(title = "Plan changes", description = "Use GET requests to report the intended action and diff without writing. False by default.")
    private Property<Boolean> plan;
    @Schema(title = "Maximum discovery results", description = "Between 1 and 10000, default 1000. Fail before writing when title/tag discovery exceeds this bound.")
    private Property<Integer> maxResults;

    @Override
    public Output run(RunContext context) throws Exception {
        String file = context.render(from).as(String.class).orElse(null);
        if ((definition == null) == (file == null)) {
            throw new IllegalArgumentException("Supply exactly one of `from` and `definition`");
        }
        Map<String, Object> desired;
        if (file != null) {
            URI uri = URI.create(file);
            if (!"kestra".equals(uri.getScheme())) {
                throw new IllegalArgumentException("`from` must be an internal-storage kestra URI");
            }
            try (var input = context.storage().getFile(uri)) {
                // Exported JSON is data. Do not render expressions embedded in the file a second time.
                desired = Dashboards.definition(Dashboards.object(JacksonMapper.ofJson().readValue(input, Object.class)));
            }
        } else {
            desired = Dashboards.definition(context.render(definition));
        }
        if (Dashboards.containsRedaction(desired)) {
            throw new IllegalArgumentException("The dashboard contains redacted placeholders; provide the original values before importing");
        }
        String id = context.render(dashboardId).as(String.class).orElse(null);
        String tag = context.render(managingTag).as(String.class).orElse(null);
        if ((id == null) == (tag == null)) {
            throw new IllegalArgumentException("Supply exactly one of `dashboardId` and `managingTag`");
        }
        if (id != null) {
            Dashboards.path(id);
        } else if (!tag.matches("team:[^\\s:]+") || !(desired.get("tags") instanceof java.util.List<?> tags) || !tags.contains(tag)) {
            throw new IllegalArgumentException("`managingTag` must be a team:<name> tag present in definition.tags");
        }
        int limit = Dashboards.limit(context.render(maxResults).as(Integer.class).orElse(1000));
        boolean preview = context.render(plan).as(Boolean.class).orElse(false);
        boolean include = context.render(getIncludeSecrets()).as(Boolean.class).orElse(false);
        DatadogClient client = client(context, true);
        Map<String, Object> current = null;
        if (id != null) {
            current = client.get(Dashboards.path(id), Map.of()).map();
        } else {
            var summaries = Dashboards.collect(client, DatadogClient.query(), limit + 1);
            if (summaries.size() > limit) {
                throw new IllegalStateException("Dashboard discovery exceeded maxResults; use dashboardId or increase maxResults");
            }
            for (var summary : summaries) {
                if (!desired.get("title").equals(summary.get("title"))) {
                    continue;
                }
                String candidateId = (String) summary.get("id");
                var candidate = client.get(Dashboards.path(candidateId), Map.of()).map();
                if (desired.get("title").equals(candidate.get("title")) && candidate.get("tags") instanceof java.util.List<?> tags && tags.contains(tag)) {
                    if (current != null) {
                        throw new IllegalStateException("Multiple dashboards match the title and managingTag; use dashboardId");
                    }
                    current = candidate;
                    id = candidateId;
                }
            }
        }
        Map<String, Object> before = current == null ? Map.of() : Dashboards.definition(current);
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.putAll(desired);
        var diff = Dashboards.diff(before, after, include);
        Action action = current == null ? Action.CREATED : diff.isEmpty() ? Action.UNCHANGED : Action.UPDATED;
        if (!preview && action != Action.UNCHANGED) {
            var response = action == Action.CREATED ? client.post(Dashboards.PATH, after).map() : client.put(Dashboards.path(id), after).map();
            if (action == Action.CREATED) {
                if (!(response.get("id") instanceof String createdId) || createdId.isBlank()) {
                    throw new IllegalStateException("Datadog did not return the created dashboard id");
                }
                id = createdId;
            }
        }
        return Output.builder().dashboardId(id).action(action).plan(preview).diff(diff).build();
    }

    public enum Action { CREATED, UPDATED, UNCHANGED }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Dashboard identifier", description = "Absent when planning a create.")
        private final String dashboardId;
        @Schema(title = "Apply action", description = "CREATED, UPDATED or UNCHANGED. In plan mode this is the planned action.")
        private final Action action;
        @Schema(title = "Plan mode")
        private final Boolean plan;
        @Schema(title = "Field-level changes", description = "JSON pointer paths with before and after values. Known credentials are redacted by default.")
        private final java.util.List<Dashboards.Change> diff;
    }
}
