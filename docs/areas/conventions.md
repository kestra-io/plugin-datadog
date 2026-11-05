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

## Fetch modes

List, search, query, aggregate and export tasks use `fetchType`.
Replace `store: true` with `fetchType: STORE` and `store: false` with `fetchType: FETCH`.
`FETCH` keeps the task's named collection. `FETCH_ONE` keeps only `row`.
`STORE` keeps only `uri`. `NONE` omits result data. Counts and pagination diagnostics remain available.
`DatadogFetch` owns result selection and streaming ION output.
Dashboard JSON exports and monitor YAML exports retain their import-compatible document format through the same helper.
`FetchConventionsTest` executes every fetch-capable task in all four modes.

## Limits and ignored properties

Result caps have `@Min` and `@Max` on the `Property` type argument.
`DatadogLimitsValidator` also checks numeric literals during flow validation on Kestra 1.3.
It uses those annotations without rendering template expressions.
Runtime checks still validate rendered limits. Result caps cannot exceed 10000.
`BoundedLimitsTest` validates flows with zero limits for every bounded component property.

Explicit `from` rejects `lookback`. A monitor plan rejects `validateFirst`.
A single configuration `resourceId` rejects `maxResults`.
An unpaginated generic call rejects `maxItems`.
`ModeValidationTest` checks each rejection names the property and sends no HTTP request.

## Client and trigger state

Factory-owning task methods close their clients with try-with-resources.
The generic transport shares one Kestra HTTP client across pages and retries.
Every trigger skips network failures, HTTP 429 and HTTP 5xx without advancing state.
Initial state uses the Datadog `Date` header. A missing header skips the poll.
Monitor events retain timestamp and seen ids. Aggregate triggers retain their threshold state.
Aggregate APIs expose no event ids. They must still process changed values in the same clock second.
`TriggerConventionsTest` uses a fixed server clock for same-second late changes and stops its mock server to test network failures.
