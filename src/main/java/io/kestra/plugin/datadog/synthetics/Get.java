package io.kestra.plugin.datadog.synthetics;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/** Datadog operationIds: v1:GetAPITest, v1:GetBrowserTest and v1:GetMobileTest. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Get a Datadog synthetic test", description = "Reads an API, browser or mobile test by its public ID. Needs synthetics_read. Secret-bearing configuration is omitted unless includeSecrets is true.")
@Plugin(examples = @Example(title = "Read an API test", full = true, code = """
    id: get_synthetic
    namespace: company.delivery
    tasks:
      - id: test
        type: io.kestra.plugin.datadog.synthetics.Get
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        publicId: abc-def-ghi
        testKind: API
    """))
public class Get extends AbstractDatadogTask implements RunnableTask<Get.Output> {
    @NotNull
    @Schema(title = "Public test ID")
    private Property<String> publicId;
    @Schema(title = "Test kind", defaultValue = "API")
    private Property<TestKind> testKind;
    @Schema(title = "Include secrets", description = "Return the full test. False returns summary fields and omits configuration, steps, options and notification messages.", defaultValue = "false")
    private Property<Boolean> includeSecrets;

    @Override
    public Output run(RunContext context) throws Exception {
        String id = Synthetics.id(context.render(publicId).as(String.class).orElse(null), "publicId");
        var kind = context.render(testKind).as(TestKind.class).orElse(TestKind.API);
        boolean reveal = context.render(includeSecrets).as(Boolean.class).orElse(false);
        var test = client(context, true).get("/api/v1/synthetics/tests/" + kind.apiValue + "/" + id, Map.of()).map();
        return Output.builder().test(Synthetics.summary(test, reveal)).build();
    }

    public enum TestKind {
        API("api"), BROWSER("browser"), MOBILE("mobile");
        final String apiValue;
        TestKind(String apiValue) {
            this.apiValue = apiValue;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Test definition", description = "Summary by default. Full definition when includeSecrets is true.")
        private final Map<String, Object> test;
    }
}
