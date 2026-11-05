package io.kestra.plugin.datadog.synthetics;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Map;

/** Datadog operation: ListLocations. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "List Datadog synthetic locations", description = "Returns public and private location IDs and names. Needs synthetics_private_location_read. This endpoint has no pagination.")
@Plugin(examples = @Example(title = "Find test locations", full = true, code = """
    id: synthetic_locations
    namespace: company.delivery
    tasks:
      - id: locations
        type: io.kestra.plugin.datadog.synthetics.Locations
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
    """))
public class Locations extends AbstractDatadogTask implements RunnableTask<Locations.Output> {
    @Override
    public Output run(RunContext context) throws Exception {
        var body = client(context, true).get("/api/v1/synthetics/locations", Map.of()).map();
        var locations = Synthetics.rows(body.get("locations"), "locations").stream()
            .map(row -> Map.<String, Object>of("id", row.get("id"), "name", row.get("name"))).toList();
        return Output.builder().locations(locations).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Locations", description = "Location IDs and names.")
        private final List<Map<String, Object>> locations;
    }
}
