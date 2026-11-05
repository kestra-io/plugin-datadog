package io.kestra.plugin.datadog.monitors;

import com.fasterxml.jackson.databind.DeserializationFeature;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
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
        dryRun: true
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
    private Property<String> definitions;
    @Schema(title = "Definitions file", description = "A kestra:// internal storage URI containing YAML or JSON. File contents are not rendered as expressions. Give this or definitions.")
    private Property<String> from;
    @Schema(title = "Preview changes", description = "False by default. Reads and validates definitions but never creates or updates monitors. Actions describe the proposed writes.")
    private Property<Boolean> dryRun;
    @Schema(title = "Validate before writing", description = "True by default. Validates every planned definition with Datadog before the first write.")
    private Property<Boolean> validateFirst;
    @Schema(title = "Store results", description = "Writes result rows to an ION file instead of returning them inline.")
    private Property<Boolean> store;

    @Override
    public Output run(RunContext context) throws Exception {
        var desired = read(context);
        boolean preview = context.render(dryRun).as(Boolean.class).orElse(false);
        boolean validate = context.render(validateFirst).as(Boolean.class).orElse(true);
        boolean include = includeSecrets(context);
        boolean storage = context.render(store).as(Boolean.class).orElse(false);
        DatadogClient client = client(context, true);
        java.util.List<Plan> plans = new ArrayList<>();
        // Finish all local validation, discovery and remote validation before any persisted write.
        for (Map<String, Object> definition : desired) {
            String key = MonitorDefinitions.key(definition);
            var params = DatadogClient.query();
            params.put("monitor_tags", key);
            var candidates = Monitors.collect(client, params, 10001);
            if (candidates.size() > 10000) {
                throw new IllegalStateException("Apply lookup exceeded 10000 monitors; narrow the management key");
            }
            var matches = candidates.stream().filter(row -> row.get("tags") instanceof java.util.List<?> tags && tags.contains(key)).toList();
            if (matches.size() > 1) {
                throw new IllegalStateException("More than one monitor has the management key " + key);
            }
            Long id = null;
            Map<String, Object> current = Map.of();
            if (!matches.isEmpty()) {
                id = MonitorOutput.of(matches.getFirst(), true).getMonitorId();
                current = client.get("/api/v1/monitor/" + id, Map.of("with_assets", true)).map();
                if (!(current.get("tags") instanceof java.util.List<?> tags) || !tags.contains(key)) {
                    throw new IllegalStateException("The monitor management key changed during Apply discovery");
                }
            }
            var changes = MonitorDefinitions.diff(current, definition);
            Action action = id == null ? Action.CREATED : changes.isEmpty() ? Action.UNCHANGED : Action.UPDATED;
            Map<String, Object> body = MonitorDefinitions.merge(MonitorDefinitions.writable(current), definition);
            if (validate) {
                client.post(id == null ? "/api/v1/monitor/validate" : "/api/v1/monitor/" + id + "/validate", body);
            }
            plans.add(new Plan(key, id, action, body, changes));
        }

        java.util.List<Result> results = new ArrayList<>();
        for (Plan plan : plans) {
            Long id = plan.id();
            if (!preview && plan.action() != Action.UNCHANGED) {
                Map<String, Object> response = id == null
                    ? client.post("/api/v1/monitor", plan.body()).map()
                    : client.put("/api/v1/monitor/" + id, plan.body()).map();
                id = MonitorOutput.of(response, true).getMonitorId();
            }
            var filtered = plan.changes().stream().map(change -> MonitorDefinitions.Change.builder()
                .field(change.getField()).before(MonitorIO.redactField(change.getField(), change.getBefore(), include))
                .after(MonitorIO.redactField(change.getField(), change.getAfter(), include)).build()).toList();
            results.add(Result.builder().key(plan.key()).monitorId(id).action(plan.action()).diff(filtered).build());
        }
        URI uri = storage ? MonitorIO.store(context, results, false) : null;
        return Output.builder().results(storage ? null : results).uri(uri).size(results.size()).dryRun(preview).build();
    }

    private java.util.List<Map<String, Object>> read(RunContext context) throws Exception {
        String text = context.render(definitions).as(String.class).orElse(null);
        String location = context.render(from).as(String.class).orElse(null);
        if ((text == null) == (location == null)) {
            throw new IllegalArgumentException("Give exactly one of definitions or from");
        }
        if (location != null) {
            URI uri = URI.create(location);
            if (!"kestra".equals(uri.getScheme())) {
                throw new IllegalArgumentException("from must be a kestra:// internal storage URI");
            }
            try (var input = context.storage().getFile(uri)) {
                byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) {
                    throw new IllegalArgumentException("Definitions exceed 10 MiB");
                }
                text = new String(bytes, StandardCharsets.UTF_8);
            }
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Definitions exceed 10 MiB");
        }
        Object decoded;
        try {
            decoded = JacksonMapper.ofYaml().readerFor(Object.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("Definitions must contain one valid YAML or JSON document");
        }
        java.util.List<?> rows = decoded instanceof java.util.List<?> list ? list : java.util.Collections.singletonList(decoded);
        if (rows.isEmpty() || rows.size() > 1000) {
            throw new IllegalArgumentException("Give between 1 and 1000 monitor definitions");
        }
        java.util.List<Map<String, Object>> result = new ArrayList<>();
        var keys = new HashSet<String>();
        for (Object row : rows) {
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

    private record Plan(String key, Long id, Action action, Map<String, Object> body, java.util.List<MonitorDefinitions.Change> changes) {
    }

    @Builder
    @Getter
    public static class Result {
        @Schema(title = "Management tag")
        private final String key;
        @Schema(title = "Monitor id", description = "Null for a proposed creation in dry-run mode.")
        private final Long monitorId;
        @Schema(title = "Action")
        private final Action action;
        @Schema(title = "Changed fields", description = "JSON pointers with before and after values. Only supplied fields are compared.")
        private final java.util.List<MonitorDefinitions.Change> diff;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Per-monitor results", description = "Omitted when store is true.")
        private final java.util.List<Result> results;
        @Schema(title = "Result file URI")
        private final URI uri;
        @Schema(title = "Number of definitions")
        private final Integer size;
        @Schema(title = "Preview mode")
        private final Boolean dryRun;
    }
}
