package io.kestra.plugin.datadog.downtimes;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.monitors.MonitorIO;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/** Datadog operationId: v2:GetDowntime. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Get a Datadog downtime", description = "Reads one downtime by id from the v2 API. Needs monitors_downtime. A missing downtime fails the task.")
@Plugin(examples = @Example(title = "Inspect a maintenance window", full = true, code = """
    id: get_downtime
    namespace: company.observability
    tasks:
      - id: get
        type: io.kestra.plugin.datadog.downtimes.Get
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        downtimeId: 00000000-0000-1234-0000-000000000000
    """))
public class Get extends AbstractDatadogTask implements RunnableTask<Get.Output> {
    @NotNull
    @Schema(title = "Downtime id")
    private Property<String> downtimeId;
    @Schema(title = "Include sensitive output values", description = "False by default. Filters credential-like keys and HTTP URLs.")
    private Property<Boolean> includeSecrets;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            String id = context.render(downtimeId).as(String.class).orElse(null);
            String path = DowntimeSupport.path(id);
            boolean include = context.render(includeSecrets).as(Boolean.class).orElse(false);
            var response = client.get(path, Map.of()).map();
            if (!(response.get("data") instanceof Map<?, ?>)) {
                throw new IllegalStateException("Datadog downtime response must contain a data object");
            }
            return Output.builder().downtimeId(id).downtime(MonitorIO.redactMap(response, include)).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Downtime id")
        private final String downtimeId;
        @Schema(title = "Downtime response", description = "The v2 response including data.attributes and relationships.")
        private final Map<String, Object> downtime;
    }
}
