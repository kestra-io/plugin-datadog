package io.kestra.plugin.datadog.synthetics;

import io.kestra.plugin.datadog.ValidDatadogLimits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@ValidDatadogLimits
@SuperBuilder
@NoArgsConstructor
public abstract class AbstractTriggerTask extends AbstractDatadogTask {
    @Schema(title = "Test identifiers", description = "Public test IDs. Set exactly one of testIds or searchQuery.")
    private Property<List<String>> testIds;

    @Schema(title = "Test search query", description = "Datadog Synthetic Monitoring search text such as tag:env:prod. Resolved before triggering.")
    private Property<String> searchQuery;

    @Schema(title = "Maximum selected tests", description = "Maximum tests selected, from 1 to 10000. A search reaching this limit is rejected to avoid a partial quality gate.", defaultValue = "100")
    private Property<@Min(1) @Max(10000) Integer> maxTests;

    @Schema(title = "CI overrides", description = "Overrides applied to every test using the TriggerCITests fields, such as startUrl, locations, variables or retry. public_id cannot be overridden.")
    @ToString.Exclude
    private Property<Map<String, Object>> overrides;

    protected Map<String, Object> triggerBody(RunContext context, DatadogClient client) throws Exception {
        var ids = context.render(testIds).asList(String.class);
        String search = context.render(searchQuery).as(String.class).orElse("").trim();
        if (ids.isEmpty() == search.isEmpty()) {
            throw new IllegalArgumentException("Set exactly one of `testIds` or `searchQuery`");
        }
        int limit = Synthetics.limit(context.render(maxTests).as(Integer.class).orElse(100));
        Map<String, Object> options = context.render(overrides).asMap(String.class, Object.class);
        if (options.containsKey("public_id")) {
            throw new IllegalArgumentException("`overrides` cannot contain public_id");
        }
        if (ids.size() > limit) {
            throw new IllegalArgumentException("`testIds` exceeds maxTests");
        }
        for (String id : ids) {
            Synthetics.id(id, "testIds");
        }
        if (!search.isEmpty()) {
            var selected = Synthetics.collect(client, search, limit);
            if (selected.size() == limit) {
                throw new IllegalArgumentException("Search reached maxTests; narrow searchQuery or raise maxTests to avoid a partial gate");
            }
            ids = selected.stream().map(row -> Synthetics.id((String) row.get("public_id"), "public_id")).toList();
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("No synthetic tests matched the selection");
        }
        return Map.of("tests", ids.stream().distinct().map(id -> {
            Map<String, Object> test = new LinkedHashMap<>(options);
            test.put("public_id", id);
            return test;
        }).toList());
    }
}
