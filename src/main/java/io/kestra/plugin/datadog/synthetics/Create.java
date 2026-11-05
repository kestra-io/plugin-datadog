package io.kestra.plugin.datadog.synthetics;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/** Datadog operationIds: v1:CreateSyntheticsAPITest and v1:CreateSyntheticsBrowserTest. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Create a Datadog synthetic test", description = "Creates an API or browser test with a full definition. Needs synthetics_write. This creates a new object on each run.")
@Plugin(examples = @Example(title = "Create a paused API test", full = true, code = """
    id: create_synthetic
    namespace: company.delivery
    tasks:
      - id: create
        type: io.kestra.plugin.datadog.synthetics.Create
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        definition:
          name: Homepage smoke test
          type: api
          subtype: http
          status: paused
          message: ""
          locations: [aws:eu-west-3]
          options:
            tick_every: 300
          config:
            request:
              method: GET
              url: https://example.com
            assertions:
              - type: statusCode
                operator: is
                target: 200
    """))
public class Create extends AbstractWriteTest implements RunnableTask<Create.Output> {
    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var body = definition(context);
            var rReveal = context.render(getIncludeSecrets()).as(Boolean.class).orElse(false);
            var test = client.post("/api/v1/synthetics/tests/" + body.get("type"), body).map();
            return Output.builder().test(Synthetics.summary(test, rReveal)).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Created test")
        private final Map<String, Object> test;
    }
}
