# Kestra Datadog Plugin

## What

- Provides plugin components under `io.kestra.plugin.datadog`.
- Tasks: `events.Create`, `metrics.Submit`, `metrics.Query`, `monitors.List`, `downtimes.Create`, `downtimes.Cancel`, `logs.Send`, `logs.Search`.
  Monitor management: `monitors.Create`, `monitors.Update`, `monitors.Get`, `monitors.Delete`, `monitors.Apply`, `monitors.Validate`, `monitors.CanDelete`, `monitors.Search`, `monitors.Export` and `monitors.Configuration`.
- Trigger: `monitors.StateChange`.

## Why

- What user problem does this solve? Teams that report to Datadog from pipelines or read it from them, do it with ad-hoc `curl` calls and scripts that carry keys, endpoints and error handling of their own.
- Why would a team adopt this plugin in a workflow? It keeps the Datadog steps in the same Kestra flow as the rest of the pipeline, with Kestra secrets, expressions and execution history.
- What operational/business outcome does it enable? Pipelines can mark deployments on the dashboards, publish metrics, check a metric or the logs before they continue, mute monitors during maintenance and react when a monitor goes into alert.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin.datadog`: `events`, `metrics`, `monitors`, `downtimes`, `logs`.

The tasks call the public Datadog API (v1 and v2) with Kestra's HTTP client. There is no Datadog SDK dependency.

### Key Plugin Classes

- `io.kestra.plugin.datadog.DatadogClient`: renders and validates the connection (`apiKey`, `appKey`, `site`, `apiUrl`), adds the `DD-API-KEY` and `DD-APPLICATION-KEY` headers, builds the regional URLs and turns an error answer into a message with the HTTP status and the `errors` Datadog returns.
- `io.kestra.plugin.datadog.AbstractDatadogTask`: base class of the tasks. It holds the connection properties.
- `io.kestra.plugin.datadog.monitors.Apply`: reads `Data.From` inputs, discovers monitors by management tag, validates the whole batch and then creates or updates changed monitors. Dry runs still call validation endpoints but do not persist changes.
- `io.kestra.plugin.datadog.monitors.Export`: exports definitions to ION for Apply or YAML for editing. Known server-only fields are removed.
- `io.kestra.plugin.datadog.monitors.StateChange`: polling trigger. It extends `AbstractTrigger`, not `AbstractDatadogTask` and keeps a cursor (the newest `overall_state_modified` it returned) in the namespace KV store.

### Project Structure

```
plugin-datadog/
├── src/main/java/io/kestra/plugin/datadog/
├── src/main/resources/ (metadata, doc and icons)
├── src/test/java/io/kestra/plugin/datadog/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.
- Run `./gradlew test` and `./gradlew lintPluginDocs` before pushing; CI runs `./gradlew check`.
- A task property cannot be named `type`: it is the task type in the flow. `metrics.Submit` calls its metric type `metricType`.
- The tests use `MockDatadogServer`, which follows the Datadog OpenAPI description. No test has run against a live Datadog organization.
- Monitor reads use `monitors_read`. Create, Update and Delete use `monitors_write`; Apply uses both. Dry runs use `monitors_read` and do not persist changes.
- Preserve Datadog fields and Apply diffs in monitor outputs, including messages and URLs used by Export/Apply round trips. Keep authentication keys out of task string output.
- Use type-use constraints on `Property` values and a task-bound `RunContext` in tests that exercise their render-time validation.
- New task classes and their abstract bases need `@ToString` and `@EqualsAndHashCode` with `callSuper = true`. Exclude definition inputs and authentication keys from string output.
- Let Datadog validate monitor types and fields. Locally enforce only the input shape, bounds and Apply management keys.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://docs.datadoghq.com/api/latest/
