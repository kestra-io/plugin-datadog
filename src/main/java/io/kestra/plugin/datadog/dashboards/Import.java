package io.kestra.plugin.datadog.dashboards;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.ValidDatadogLimits;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Datadog operationIds: ListDashboards, GetDashboard, CreateDashboard and UpdateDashboard. */
@ValidDatadogLimits
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
    @PluginProperty(group = "main")
    private Property<String> dashboardId;
    @Schema(title = "Managing team tag", description = "Used with the exact definition title when dashboardId is absent. Must be team:<name> and present in definition.tags. Ambiguous matches fail.")
    @PluginProperty(group = "main")
    private Property<String> managingTag;
    @Schema(title = "JSON definition URI", description = "A kestra:/// internal-storage file, for example Export.uri. Supply from or definition, exactly one.")
    @PluginProperty(group = "processing")
    private Property<String> from;
    @PluginProperty(dynamic = true, group = "main")
    @ToString.Exclude
    @Schema(title = "Desired dashboard definition", description = "Requires title, layout_type and widgets. Omitted top-level fields are preserved on update. Provided nested objects and arrays are replaced.")
    private Map<String, Object> definition;
    @Schema(title = "Plan changes", description = "Use GET requests to report the intended action and diff without writing. False by default.")
    @PluginProperty(group = "advanced")
    private Property<Boolean> plan;
    @Schema(title = "Maximum discovery results", description = "Between 1 and 10000, default 1000. Fail before writing when title/tag discovery exceeds this bound.")
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(10000) Integer> maxResults;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var rFile = context.render(from).as(String.class).orElse(null);
            if ((definition == null) == (rFile == null)) {
                throw new IllegalArgumentException("Supply exactly one of `from` and `definition`");
            }
            Map<String, Object> desired;
            if (rFile != null) {
                var uri = URI.create(rFile);
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
            var rId = context.render(dashboardId).as(String.class).orElse(null);
            var rTag = context.render(managingTag).as(String.class).orElse(null);
            if ((rId == null) == (rTag == null)) {
                throw new IllegalArgumentException("Supply exactly one of `dashboardId` and `managingTag`");
            }
            if (rId != null) {
                Dashboards.path(rId);
            } else if (!rTag.matches("team:[^\\s:]+") || !(desired.get("tags") instanceof List<?> tags) || !tags.contains(rTag)) {
                throw new IllegalArgumentException("`managingTag` must be a team:<name> tag present in definition.tags");
            }
            var rLimit = Dashboards.limit(context.render(maxResults).as(Integer.class).orElse(1000));
            var rPreview = context.render(plan).as(Boolean.class).orElse(false);
            var rInclude = context.render(getIncludeSecrets()).as(Boolean.class).orElse(false);
            Map<String, Object> current = null;
            if (rId != null) {
                current = client.get(Dashboards.path(rId), Map.of()).map();
            } else {
                var summaries = Dashboards.collect(client, DatadogClient.query(), rLimit + 1);
                if (summaries.size() > rLimit) {
                    throw new IllegalStateException("Dashboard discovery exceeded maxResults; use dashboardId or increase maxResults");
                }
                for (var summary : summaries) {
                    if (!desired.get("title").equals(summary.get("title"))) {
                        continue;
                    }
                    var candidateId = (String) summary.get("id");
                    var candidate = client.get(Dashboards.path(candidateId), Map.of()).map();
                    if (desired.get("title").equals(candidate.get("title")) && candidate.get("tags") instanceof List<?> tags && tags.contains(rTag)) {
                        if (current != null) {
                            throw new IllegalStateException("Multiple dashboards match the title and managingTag; use dashboardId");
                        }
                        current = candidate;
                        rId = candidateId;
                    }
                }
            }
            var before = current == null ? Map.<String, Object>of() : Dashboards.definition(current);
            var after = new LinkedHashMap<String, Object>(before);
            after.putAll(desired);
            var diff = Dashboards.diff(before, after, rInclude);
            var action = current == null ? Action.CREATED : diff.isEmpty() ? Action.UNCHANGED : Action.UPDATED;
            if (!rPreview && action != Action.UNCHANGED) {
                var response = action == Action.CREATED ? client.post(Dashboards.PATH, after).map() : client.put(Dashboards.path(rId), after).map();
                if (action == Action.CREATED) {
                    if (!(response.get("id") instanceof String createdId) || createdId.isBlank()) {
                        throw new IllegalStateException("Datadog did not return the created dashboard id");
                    }
                    rId = createdId;
                }
            }
            return Output.builder().dashboardId(rId).action(action).plan(rPreview).diff(diff).build();
        }
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
        private final List<Dashboards.Change> diff;
    }
}
