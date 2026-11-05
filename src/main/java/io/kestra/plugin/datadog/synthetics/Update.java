package io.kestra.plugin.datadog.synthetics;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/** Datadog operationIds: v1:UpdateAPITest and v1:UpdateBrowserTest. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Replace a Datadog synthetic test", description = "Overwrites one identified API or browser test with the supplied full definition. Needs synthetics_write. A missing test fails the task.")
@Plugin(examples = @Example(title = "Replace a test definition", full = true, code = """
    id: update_synthetic
    namespace: company.delivery
    tasks:
      - id: update
        type: io.kestra.plugin.datadog.synthetics.Update
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        publicId: abc-def-ghi
        definition:
          name: Homepage smoke test
          type: api
          subtype: http
          message: ""
          locations: [aws:eu-west-3]
          options: {tick_every: 300}
          config:
            request: {method: GET, url: "https://example.com"}
            assertions: [{type: statusCode, operator: is, target: 200}]
    """))
public class Update extends AbstractWriteTest implements RunnableTask<Update.Output> {
    @NotNull
    @Schema(title = "Public test ID")
    @PluginProperty(group = "main")
    private Property<String> publicId;
    @Schema(title = "Ignore missing test", description = "Return updated=false if Datadog answers 404.", defaultValue = "false")
    @PluginProperty(group = "reliability")
    private Property<Boolean> ignoreNotFound;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var rId = Synthetics.id(context.render(publicId).as(String.class).orElse(null), "publicId");
            var body = definition(context);
            var rReveal = context.render(getIncludeSecrets()).as(Boolean.class).orElse(false);
            var rIgnore = context.render(ignoreNotFound).as(Boolean.class).orElse(false);
            try {
                var test = client.put("/api/v1/synthetics/tests/" + body.get("type") + "/" + rId, body).map();
                return Output.builder().updated(true).test(Synthetics.summary(test, rReveal)).build();
            } catch (DatadogClient.DatadogException e) {
                if (e.getStatus() != 404 || !rIgnore) {
                    throw e;
                }
                return Output.builder().updated(false).test(Map.of()).build();
            }
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Test was updated")
        private final Boolean updated;
        @Schema(title = "Updated test")
        private final Map<String, Object> test;
    }
}
