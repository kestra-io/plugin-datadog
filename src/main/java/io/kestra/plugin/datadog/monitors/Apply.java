package io.kestra.plugin.datadog.monitors;

import com.fasterxml.jackson.databind.DeserializationFeature;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.DatadogFetch;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Datadog operationIds: ListMonitors, GetMonitor, ValidateMonitor, ValidateExistingMonitor, CreateMonitor and UpdateMonitor. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Apply Datadog monitors from code", description = "Reconciles definitions using one managed_by:kestra:<key> tag per monitor. Reports CREATED, UPDATED or UNCHANGED with a field diff. Only supplied writable fields are managed. Tag order is significant. Duplicate keys fail. Needs monitors_read and monitors_write. Run serially for each key: the API has no atomic upsert. A missing monitor during update fails without recreation. A batch is not transactional.")
@Plugin(examples = @Example(title = "Preview a monitor change", full = true, code = """
    id: apply_monitors
    namespace: company.observability
    tasks:
      - id: apply
        type: io.kestra.plugin.datadog.monitors.Apply
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        plan: true
        definitions: |
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
public class Apply extends AbstractMonitorTask implements RunnableTask<Apply.Output> {
    private static final int MAX_BYTES = 10 * 1024 * 1024;

    @Schema(title = "Definitions as YAML or JSON", description = "One monitor object or an array of objects as text. Each requires query, type, name and one managed_by:kestra:<key> tag. Give this or from. Maximum 1000 definitions and 10 MiB.")
    @PluginProperty(group = "main")
    private Property<String> definitions;
    @Schema(title = "Definitions file", description = "A kestra:// internal storage URI containing YAML or JSON. File contents are not rendered as expressions. Give this or definitions.")
    @PluginProperty(group = "processing")
    private Property<String> from;
    @Schema(title = "Plan changes", description = "False by default. Uses GET requests to compute a diff without writing. Validates definitions locally and skips remote validation. Actions describe the proposed writes.")
    @PluginProperty(group = "advanced")
    private Property<Boolean> plan;
    @Schema(title = "Validate before writing", description = "True by default for writes. Validates every definition with Datadog before the first write. Cannot be supplied when plan is true.")
    @PluginProperty(group = "advanced")
    private Property<Boolean> validateFirst;
    @Schema(title = "Fetch type", description = "FETCH returns rows, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only the count.", defaultValue = "FETCH")
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var desired = read(context);
            var rPreview = context.render(plan).as(Boolean.class).orElse(false);
            if (rPreview && validateFirst != null) {
                throw new IllegalArgumentException("`validateFirst` cannot be used when `plan` is true");
            }
            var rValidate = context.render(validateFirst).as(Boolean.class).orElse(true);
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
                if (rValidate && !rPreview) {
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
            return Output.builder().results(result.rows()).row(result.row()).uri(result.uri()).size(result.size()).plan(rPreview).build();
        }
    }

    private List<Map<String, Object>> read(RunContext context) throws Exception {
        var rText = context.render(definitions).as(String.class).orElse(null);
        var rLocation = context.render(from).as(String.class).orElse(null);
        if ((rText == null) == (rLocation == null)) {
            throw new IllegalArgumentException("Give exactly one of definitions or from");
        }
        if (rLocation != null) {
            var uri = URI.create(rLocation);
            if (!"kestra".equals(uri.getScheme())) {
                throw new IllegalArgumentException("from must be a kestra:// internal storage URI");
            }
            try (var input = context.storage().getFile(uri)) {
                var bytes = input.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) {
                    throw new IllegalArgumentException("Definitions exceed 10 MiB");
                }
                rText = new String(bytes, StandardCharsets.UTF_8);
            }
        }
        if (rText.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Definitions exceed 10 MiB");
        }
        Object decoded;
        try {
            decoded = JacksonMapper.ofYaml().readerFor(Object.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(rText);
        } catch (Exception e) {
            throw new IllegalArgumentException("Definitions must contain one valid YAML or JSON document");
        }
        List<?> rows = decoded instanceof List<?> list ? list : Collections.singletonList(decoded);
        if (rows.isEmpty() || rows.size() > 1000) {
            throw new IllegalArgumentException("Give between 1 and 1000 monitor definitions");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        var keys = new HashSet<String>();
        for (var row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Each monitor definition must be an object");
            }
            var definition = MonitorDefinitions.validate(MonitorDefinitions.object(map), true);
            if (!(definition.get("name") instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("Apply requires a non-blank monitor name");
            }
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
        @Schema(title = "Monitor id", description = "Null for a proposed creation in plan mode.")
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
        @Schema(title = "Plan mode")
        private final Boolean plan;
    }
}
