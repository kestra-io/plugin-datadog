package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Data;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.DatadogFetch;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Datadog operationIds: ListMonitors, GetMonitor, ValidateMonitor, ValidateExistingMonitor, CreateMonitor and UpdateMonitor. */
@SuperBuilder
@Getter
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Schema(title = "Apply Datadog monitors from code", description = "Reconciles definitions using one managed_by:kestra:<key> tag per monitor. Reports CREATED, UPDATED or UNCHANGED with a field diff. Only supplied writable fields are managed. Tag order is significant. Duplicate keys fail. Needs monitors_read and monitors_write. Run serially for each key: the API has no atomic upsert. A missing monitor during update fails without recreation. A batch is not transactional.")
@Plugin(examples = @Example(title = "Preview a monitor change", full = true, code = """
    id: apply_monitors
    namespace: company.observability
    tasks:
      - id: apply
        type: io.kestra.plugin.datadog.monitors.Apply
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        dryRun: true
        from:
          - name: CPU usage
            type: query alert
            query: "avg(last_5m):avg:system.cpu.user{env:prod} > 80"
            tags:
              - managed_by:kestra:cpu-prod
              - env:prod
            options:
              thresholds:
                critical: 80
    """))
public class Apply extends AbstractMonitorTask implements RunnableTask<Apply.Output>, Data.From {
    private static final int MAX_BYTES = 10 * 1024 * 1024;

    @NotNull
    @Schema(title = Data.From.TITLE, description = Data.From.DESCRIPTION + " Each monitor needs one managed_by:kestra:<key> tag. Maximum 1000 definitions and 10 MiB. Use Export with format ION for a file input.", anyOf = {String.class, List.class, Map.class})
    @PluginProperty(dynamic = true, group = "main")
    @ToString.Exclude
    private Object from;
    @Schema(title = "Dry run", description = "False by default. Uses GET requests to compute a diff without writing or calling remote validation. Actions describe proposed writes.")
    @PluginProperty(group = "advanced")
    private Property<Boolean> dryRun;
    @Builder.Default
    @Schema(title = "Fetch type", description = "FETCH returns rows, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only the count.", defaultValue = "FETCH")
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var desired = read(context);
            var rPreview = context.render(dryRun).as(Boolean.class).orElse(false);
            var include = includeSecrets(context);
            List<Plan> plans = new ArrayList<>();
            // Finish all local validation, discovery and remote validation before any persisted write.
            for (var definition : desired) {
                var key = MonitorDefinitions.key(definition);
                var params = DatadogClient.query();
                params.put("monitor_tags", key);
                var candidates = Monitors.collect(client, params, 10001);
                if (candidates.size() > 10000) {
                    throw new IllegalStateException("Apply lookup exceeded 10000 monitors; narrow the management key");
                }
                var matches = candidates.stream().filter(row -> row.get("tags") instanceof List<?> tags && tags.contains(key)).toList();
                if (matches.size() > 1) {
                    throw new IllegalStateException("More than one monitor has the management key " + key);
                }
                Long id = null;
                var current = Map.<String, Object>of();
                if (!matches.isEmpty()) {
                    id = MonitorOutput.of(matches.getFirst(), true).getMonitorId();
                    current = client.get("/api/v1/monitor/" + id, Map.of("with_assets", true)).map();
                    if (!(current.get("tags") instanceof List<?> tags) || !tags.contains(key)) {
                        throw new IllegalStateException("The monitor management key changed during Apply discovery");
                    }
                }
                var changes = MonitorDefinitions.diff(current, definition);
                var action = id == null ? Action.CREATED : changes.isEmpty() ? Action.UNCHANGED : Action.UPDATED;
                var body = MonitorDefinitions.merge(MonitorDefinitions.writable(current), definition);
                if (!rPreview) {
                    client.post(id == null ? "/api/v1/monitor/validate" : "/api/v1/monitor/" + id + "/validate", body);
                }
                plans.add(new Plan(key, id, action, body, changes));
            }

            List<Result> results = new ArrayList<>();
            for (var plan : plans) {
                var id = plan.id();
                if (!rPreview && plan.action() != Action.UNCHANGED) {
                    var response = id == null
                        ? client.post("/api/v1/monitor", plan.body()).map()
                        : client.put("/api/v1/monitor/" + id, plan.body()).map();
                    id = MonitorOutput.of(response, true).getMonitorId();
                }
                var filtered = plan.changes().stream().map(change -> MonitorDefinitions.Change.builder()
                    .field(change.getField()).before(MonitorIO.redactField(change.getField(), change.getBefore(), include))
                    .after(MonitorIO.redactField(change.getField(), change.getAfter(), include)).build()).toList();
                results.add(Result.builder().key(plan.key()).monitorId(id).action(plan.action()).diff(filtered).build());
            }
            var result = DatadogFetch.apply(context, context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH), results);
            return Output.builder().results(result.rows()).row(result.row()).uri(result.uri()).size(result.size()).dryRun(rPreview).build();
        }
    }

    private List<Map<String, Object>> read(RunContext context) throws Exception {
        if (from == null) {
            throw new IllegalArgumentException("from is required");
        }
        if (from instanceof String text && text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Definitions exceed 10 MiB");
        }
        List<Map<String, Object>> rows;
        try {
            rows = Data.from(from).read(context).take(1001).collectList().block(Duration.ofSeconds(30));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot read monitor definitions from `from`", e);
        }
        if (rows.isEmpty() || rows.size() > 1000) {
            throw new IllegalArgumentException("Give between 1 and 1000 monitor definitions");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        var keys = new HashSet<String>();
        var bytes = 0;
        for (var row : rows) {
            bytes += JacksonMapper.ofJson().writeValueAsBytes(row).length;
            if (bytes > MAX_BYTES) {
                throw new IllegalArgumentException("Definitions exceed 10 MiB");
            }
            var definition = MonitorDefinitions.validate(row);
            if (!keys.add(MonitorDefinitions.key(definition))) {
                throw new IllegalArgumentException("Apply definitions contain a duplicate management key");
            }
            result.add(definition);
        }
        return result;
    }

    public enum Action { CREATED, UPDATED, UNCHANGED }

    private record Plan(String key, Long id, Action action, Map<String, Object> body, List<MonitorDefinitions.Change> changes) {
    }

    @Builder
    @Getter
    public static class Result {
        @Schema(title = "Management tag")
        private final String key;
        @Schema(title = "Monitor id", description = "Null for a proposed creation in a dry run.")
        private final Long monitorId;
        @Schema(title = "Action")
        private final Action action;
        @Schema(title = "Changed fields", description = "JSON pointers with before and after values. Only supplied fields are compared.")
        private final List<MonitorDefinitions.Change> diff;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Per-monitor results", description = "Omitted with STORE.")
        private final List<Result> results;
        @Schema(title = "First reconciliation result", description = "Present with FETCH_ONE.")
        private final Result row;
        @Schema(title = "Result file URI")
        private final URI uri;
        @Schema(title = "Number of definitions")
        private final Integer size;
        @Schema(title = "Dry run")
        private final Boolean dryRun;
    }
}
