# Integrated verification

## Integration audit baseline

| Measure | Before this change | After this change |
|---|---:|---:|
| Typed operation mappings | 76 | 122 |
| Unmapped typed components | 32 | 0 |
| Vendored operation schemas | 8 | 122 |
| Typed-task test classes using OpenApiContract | 7 | 14 |
| Checked-in e2e flow files | 6 | 31 |
| Java tests passing in the full gate | 3088 | 3461 |

The current source contains 110 typed runnable tasks, four polling triggers and two generic helpers. The 31 flow files cover every component: 27 area flows and four trigger flows. The runner includes generic helpers by default and fails before container startup if a component lacks a flow.

The mock-backed Kestra 2.0.6 run completed 31 successful executions and 156 flow validations without `--allow-partial`. Each trigger fired once and stayed at one execution across three follow-up polls. The final artifact commit, jar SHA-256, per-flow execution JSON, logs and request trace are saved by the runner outside the repository.

## Test layers

- `ScenarioTest`: shared e2e inputs, output expectations and pinned response schemas. Request contracts apply to every task, including usage and cost date-time parameters.
- `SafetyInvariantsTest`: reflection inventory, reserved property names, single-object destructive selectors, every declared dry-run input and named credential output redaction. Plans use GET requests only. Numeric ID arrays preserve small and 64-bit values.
- Existing task tests: required fields, error responses, paging, storage, redaction and second-run idempotency. High-risk write tests now also assert through the contract helper.
- Python: seven script tests and eight e2e runner/mock tests, including preview route enforcement.
- Mutation: replacing component discovery with an empty set made three e2e tests fail. Restoring discovery returned all seven tests to green. Full red and green logs are retained in the agent evidence directory.

## Evidence limits

The repair reproduced the audit's 11 failures before changing task behavior. Safety and request contracts now run without an environment switch or known-failure bypass. The baseline measurements above describe the preceding integration audit. Repair red and green logs are retained with its publication evidence. See [integration findings](INTEGRATION-FINDINGS.md).

The mock verifies workflow integration and pinned contracts. It does not establish live Datadog permissions, regional availability, account-specific responses or production behavior. The Kestra documentation website was not built locally. Notebook operations have generic routing but no typed tasks in this source.
