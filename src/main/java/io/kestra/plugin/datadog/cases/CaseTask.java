package io.kestra.plugin.datadog.cases;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.incidents.ResponseOutput;
import io.kestra.plugin.datadog.incidents.ResponseSupport;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Map;

@SuperBuilder
@Getter
@NoArgsConstructor
public abstract class CaseTask extends ResponseSupport {
    @NotNull
    @Schema(title = "Case id")
    @PluginProperty(group = "main")
    private Property<String> caseId;

    protected String casePath(RunContext context) throws Exception {
        return "/api/v2/cases/" + identifier(context, caseId, "caseId");
    }

    protected ResponseOutput update(RunContext context, String action, Map<String, Object> attributes) throws Exception {
        return send(context, "POST", casePath(context) + "/" + action, Map.of(),
            Map.of("data", resource("case", null, attributes)), false);
    }
}
