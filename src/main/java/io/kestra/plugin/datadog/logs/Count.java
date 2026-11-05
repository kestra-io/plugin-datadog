package io.kestra.plugin.datadog.logs;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Map;

/** Datadog v2 operationId: AggregateLogs. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Count Datadog logs against a threshold", description = "Counts matching logs in a window with the aggregate API. Needs logs_read_data. Returns count and breached or fails on breach when requested. Incomplete counts fail the task.")
@Plugin(examples = @Example(title = "Stop a deployment when errors appear", full = true, code = """
    id: datadog_log_gate
    namespace: company.observability
    tasks:
      - id: gate
        type: io.kestra.plugin.datadog.logs.Count
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        query: "service:orders status:error"
        from: now-15m
        threshold: 0
        failOnBreach: true
    """))
public class Count extends AbstractCount implements RunnableTask<AbstractCount.Output> {
    @Schema(title = "Indexes", description = "Log indexes to count. All indexes when omitted.")
    @PluginProperty(group = "processing")
    private Property<List<String>> indexes;

    @Override
    protected Map<String, Object> filter(RunContext context) throws Exception {
        var filter = super.filter(context);
        var rValues = context.render(indexes).asList(String.class);
        if (!rValues.isEmpty()) {
            filter.put("indexes", rValues);
        }
        return filter;
    }

    @Override
    public Output run(RunContext context) throws Exception {
        return count(context, false);
    }
}
