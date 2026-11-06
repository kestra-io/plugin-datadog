package io.kestra.plugin.datadog.logs;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Send log entries to Datadog",
    description = """
        Sends one log entry (`message`) or several (`logs`) to the Datadog log intake. Needs the API key only.

        Datadog accepts at most 1000 entries and 5 MB per request and answers before it indexes them: \
        an entry that an index filter drops later does not make the task fail. \
        Giving both `message` and `logs` or neither, is an error."""
)
@Plugin(
    examples = {
        @Example(
            title = "Send a log line for a pipeline step",
            full = true,
            code = """
                id: datadog_log
                namespace: company.observability

                tasks:
                  - id: send
                    type: io.kestra.plugin.datadog.logs.Send
                    apiKey: "{{ secret('DATADOG_API_KEY') }}"
                    site: datadoghq.eu
                    message: "Loaded 1250 orders"
                    service: orders-pipeline
                    source: kestra
                    tags:
                      - "env:prod"
                    attributes:
                      execution_id: "{{ execution.id }}"
                      rows: 1250
                """
        )
    }
)
public class Send extends AbstractDatadogTask implements RunnableTask<Send.Output> {
    private static final int MAX_ENTRIES = 1000;
    // the fields of an entry that have a property of their own
    private static final List<String> RESERVED = List.of("message", "service", "ddsource", "hostname", "ddtags");

    @Schema(title = "Message", description = "The text of one log entry.")
    @PluginProperty(group = "main")
    private Property<String> message;

    @Schema(title = "Service", description = "The service the entry belongs to, to filter on in the Log Explorer. With `logs`, the default for the entries that set no `service`.")
    @PluginProperty(group = "processing")
    private Property<String> service;

    @Schema(title = "Source", description = "The source of the entry (`ddsource`), which picks the log pipeline in Datadog, such as `kestra`.")
    @PluginProperty(group = "processing")
    private Property<String> source;

    @Schema(title = "Host", description = "The host name the entry comes from (`hostname`).")
    @PluginProperty(group = "processing")
    private Property<String> host;

    @Schema(title = "Tags", description = "Tags to attach, such as `env:prod`. With `logs`, they are added to the `ddtags` of every entry.")
    @PluginProperty(group = "processing")
    private Property<List<String>> tags;

    @Schema(title = "Attributes", description = "Extra attributes added to the entry as JSON fields. `message`, `service`, `ddsource`, `hostname` and `ddtags` are refused here because they have a property of their own. With `logs`, the default for the entries that do not have the attribute.")
    @PluginProperty(group = "processing")
    private Property<Map<String, Object>> attributes;

    @Schema(
        title = "Log entries",
        description = "A list of entries in the shape of the Datadog `POST /api/v2/logs` body (`message`, `ddsource`, `ddtags`, `hostname`, `service` and any other field). Use instead of `message`."
    )
    @PluginProperty(group = "main")
    private Property<List<Map<String, Object>>> logs;

    @Schema(title = "Log intake URL", description = "Replaces `https://http-intake.logs.<site>` when set, for a proxy or a test server. `apiUrl` does not apply to this task: the log intake has its own address, so a proxy for the other tasks needs its own route for it.")
    @PluginProperty(group = "advanced")
    private Property<String> intakeUrl;

    private static void rejectReserved(Map<String, Object> attributes) {
        for (String reserved : RESERVED) {
            if (attributes.containsKey(reserved)) {
                throw new IllegalArgumentException("`attributes` cannot set '" + reserved + "': use the property of the task with the matching name (`message`, `service`, `source`, `host` or `tags`)");
            }
        }
    }

    private Map<String, Object> defaults(RunContext runContext) throws Exception {
        var defaults = new LinkedHashMap<String, Object>(runContext.render(this.attributes).asMap(String.class, Object.class));
        rejectReserved(defaults);
        runContext.render(this.service).as(String.class).filter(v -> !v.isBlank()).ifPresent(v -> defaults.put("service", v));
        runContext.render(this.source).as(String.class).filter(v -> !v.isBlank()).ifPresent(v -> defaults.put("ddsource", v));
        runContext.render(this.host).as(String.class).filter(v -> !v.isBlank()).ifPresent(v -> defaults.put("hostname", v));
        return defaults;
    }

    @Override
    public Output run(RunContext runContext) throws Exception {
        if ((this.message == null) == (this.logs == null)) {
            throw new IllegalArgumentException("Give either `message` for one entry or `logs` for several, but not both and not neither");
        }

        var entries = new ArrayList<Map<String, Object>>();
        if (this.logs != null) {
            // service, source, host, tags and attributes are defaults for the entries that do not set them
            var defaults = defaults(runContext);
            var defaultTags = runContext.render(this.tags).asList(String.class).stream().filter(t -> t != null && !t.isBlank()).toList();
            for (Map<?, ?> raw : runContext.render(this.logs).asList(Map.class)) {
                var entry = new LinkedHashMap<String, Object>();
                raw.forEach((key, value) -> entry.put(String.valueOf(key), value));
                if (entry.get("message") == null || String.valueOf(entry.get("message")).isBlank()) {
                    throw new IllegalArgumentException("Every entry of `logs` needs a `message`");
                }
                defaults.forEach(entry::putIfAbsent);
                if (!defaultTags.isEmpty()) {
                    var own = entry.get("ddtags") == null ? "" : String.valueOf(entry.get("ddtags"));
                    entry.put("ddtags", own.isBlank() ? String.join(",", defaultTags) : own + "," + String.join(",", defaultTags));
                }
                entries.add(entry);
            }
            if (entries.isEmpty()) {
                throw new IllegalArgumentException("`logs` must contain at least one entry");
            }
        } else {
            var rMessage = runContext.render(this.message).as(String.class).orElse("");
            if (rMessage.isBlank()) {
                throw new IllegalArgumentException("The Datadog log `message` must not be blank");
            }
            var entry = new LinkedHashMap<String, Object>(runContext.render(this.attributes).asMap(String.class, Object.class));
            rejectReserved(entry);
            entry.put("message", rMessage);
            runContext.render(this.service).as(String.class).filter(v -> !v.isBlank()).ifPresent(v -> entry.put("service", v));
            runContext.render(this.source).as(String.class).filter(v -> !v.isBlank()).ifPresent(v -> entry.put("ddsource", v));
            runContext.render(this.host).as(String.class).filter(v -> !v.isBlank()).ifPresent(v -> entry.put("hostname", v));
            var rTags = runContext.render(this.tags).asList(String.class).stream().filter(t -> t != null && !t.isBlank()).toList();
            if (!rTags.isEmpty()) {
                entry.put("ddtags", String.join(",", rTags));
            }
            entries.add(entry);
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Datadog accepts at most " + MAX_ENTRIES + " log entries per request but " + entries.size() + " were given: split them over several tasks");
        }

        try (var client = client(runContext, false)) {
            var base = client.logsIntakeBase(runContext.render(this.intakeUrl).as(String.class).orElse(""));
            client.exchange("POST", base, "/api/v2/logs", Map.of(), entries, true);
        }

        runContext.logger().info("Datadog accepted {} log entr{}", entries.size(), entries.size() == 1 ? "y" : "ies");
        return Output.builder().sent(entries.size()).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Entries sent", description = "The number of log entries Datadog accepted.")
        private final Integer sent;
    }
}
