# Reviewed Kestra conventions

## Redacted query outputs

`monitors.List` and `logs.Search` redact credential fields and HTTP URLs before applying `fetchType`.
This covers `FETCH`, `FETCH_ONE` and `STORE` outputs.
Set `includeSecrets: true` to preserve the original values.
`NONE` returns the number of matches without rows.
Telemetry searches and aggregates also filter HTTP URLs in free-text fields.
URL filtering includes ordinary links. Arbitrary credentials embedded in prose are not parsed.

`OutputRedactionTest` checks inline rows, the first row, stored ION rows and explicit opt-in.
The generated log search scenario uses the reviewed task inputs.
`ScenarioTest` checks those inputs against the task and the pinned API contract.

No live Datadog organization was used.
