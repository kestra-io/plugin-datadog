package io.kestra.plugin.datadog.oncall;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.incidents.ResponseOutput;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import java.util.Map;

/** Single-page lifecycle actions use the same regional server as page creation. */
@SuperBuilder
@Getter
@NoArgsConstructor
public abstract class PageAction extends PagingTask {
    @NotNull
    @Schema(title = "Page id", description = "UUID returned by oncall.Page.")
    private Property<String> pageId;

    @Schema(title = "Dry run", description = "Render the action without changing the page. True by default.")
    private Property<Boolean> dryRun;

    @Schema(title = "Ignore missing page", description = "Treats only HTTP 404 as success. False by default.")
    private Property<Boolean> ignoreNotFound;

    protected ResponseOutput act(RunContext context, String action) throws Exception {
        try (var client = client(context, true)) {
            String id = uuid(context, pageId, "pageId");
            boolean preview = dryRun(context, dryRun);
            boolean ignore = context.render(ignoreNotFound).as(Boolean.class).orElse(false);
            try {
                return send(context, client, pagingBase(context, client), "POST", "/api/v2/on-call/pages/" + id + "/" + action,
                    Map.of(), null, preview).toBuilder().id(id).build();
            } catch (DatadogClient.DatadogException e) {
                if (e.getStatus() == 404 && ignore) {
                    return ResponseOutput.builder().id(id).statusCode(404).body(Map.of()).build();
                }
                throw e;
            }
        }
    }
}
