# Synthetic tests and deployment quality gates

Use these tasks to maintain synthetic tests, run a CI batch, evaluate a deployment gate and record a completed deployment for DORA metrics. All calls use the shared Datadog client and the configured Datadog site.

## Tasks and API trace

| Task | What it does | Datadog operation IDs |
|---|---|---|
| `synthetics.List` | Lists tests or searches them with bounded paging. Supports `fetchType: STORE` for ION output. | `ListTests`, `SearchTests` |
| `synthetics.Get` | Reads an API, browser or mobile test by public ID. | `GetAPITest`, `GetBrowserTest`, `GetMobileTest` |
| `synthetics.Create` | Creates an API or browser test from a full definition. | `CreateSyntheticsAPITest`, `CreateSyntheticsBrowserTest` |
| `synthetics.Update` | Replaces one identified API or browser test. | `UpdateAPITest`, `UpdateBrowserTest` |
| `synthetics.Delete` | Deletes one test without forced dependency removal. | `DeleteTests` |
| `synthetics.SetStatus` | Pauses or resumes the schedule of one test. | `UpdateTestPauseStatus` |
| `synthetics.Trigger` | Starts a CI batch and returns its ID without polling. | `TriggerCITests`, `SearchTests` |
| `synthetics.Run` | Starts a CI batch, polls to completion and enforces blocking failures. | `TriggerCITests`, `SearchTests`, `GetSyntheticsCIBatch`, `GetAPITestResult`, `GetBrowserTestResult` |
| `synthetics.Results` | Reads the latest result window or one result by ID. Supports ION output. | `GetAPITestLatestResults`, `GetBrowserTestLatestResults`, `GetAPITestResult`, `GetBrowserTestResult` |
| `synthetics.Locations` | Lists public and private location IDs and names. | `ListLocations` |
| `deployments.Evaluate` | Triggers evaluation of a configured gate for a service and environment and waits for its result. | `TriggerDeploymentGatesEvaluation`, `GetDeploymentGatesEvaluationResult` |
| `deployments.CreateGate` | Creates a service and environment gate. | `CreateDeploymentGate` |
| `deployments.CreateMonitorRule` | Adds a monitor-query rule to a gate. | `CreateDeploymentRule` |
| `deployments.GetRules` | Reads the rules of one gate. | `GetDeploymentGateRules` |
| `deployments.Record` | Records a completed deployment with Unix nanosecond timestamps. | `CreateDORADeployment` |

The task prefix is `io.kestra.plugin.datadog.`. `SetStatus` uses a typed `LIVE` or `PAUSED` property. `Get` supports `API`, `BROWSER` and `MOBILE`. `Results` supports `API` and `BROWSER`.

## Keys and scopes

| Tasks | Keys | Application-key scope from the API description |
|---|---|---|
| Synthetic reads | API and application key | `synthetics_read` |
| Synthetic writes and CI trigger | API and application key | `synthetics_write` |
| `synthetics.Run` | API and application key | `synthetics_write` and `synthetics_read` |
| `synthetics.Trigger` with search selection | API and application key | `synthetics_write` and `synthetics_read` |
| `synthetics.Locations` | API and application key | `synthetics_private_location_read` |
| Deployment gate tasks | API and application key | No named OAuth scope is specified for these operations in the offline description. The account must have access to Deployment Gates. |
| `deployments.Record` | API key | No application key required |

Use Kestra secrets for keys. The tasks never log request bodies or credentials. The default output of synthetic definition tasks contains only `public_id`, `name`, `type`, `subtype`, `status`, `tags`, `locations` and `monitor_id` when present. It omits configurations, headers, URLs, variables, certificates, steps, options and notification messages. Set `includeSecrets: true` to return unfiltered API records. Search responses use `include_full_config=false` and do not replace a full `Get`.

`Results` returns only `check_time`, `probe_dc`, `result_id` and `status` unless `includeSecrets` is true. `Run` returns failure codes but redacts raw failure messages by default because messages can contain request content. Its `includeSecrets` option exposes these messages. Redaction also applies before writing ION files.

## Run a quality gate

```yaml
id: release_quality_gate
namespace: company.delivery

tasks:
  - id: smoke
    type: io.kestra.plugin.datadog.synthetics.Run
    apiKey: "{{ secret('DATADOG_API_KEY') }}"
    appKey: "{{ secret('DATADOG_APP_KEY') }}"
    testIds:
      - abc-def-ghi
    maxWait: PT10M
    pollInterval: PT5S
    overrides:
      startUrl: https://staging.example.com

  - id: gate
    type: io.kestra.plugin.datadog.deployments.Evaluate
    apiKey: "{{ secret('DATADOG_API_KEY') }}"
    appKey: "{{ secret('DATADOG_APP_KEY') }}"
    service: checkout
    env: staging
    deploymentVersion: v1.2.3
    identifier: pre-deploy
    maxWait: PT10M
```

Run `deployments.Record` after the actual deployment has completed. Pass its start and finish as ISO-8601 instants. `deploymentVersion` maps to Datadog's `version` field. Kestra reserves the task-level `version` property for plugin version selection.

### Selection and overrides

Set exactly one of `testIds` or `searchQuery` on `Trigger` and `Run`. Search uses `SearchTests` before the CI trigger call. It does not send a made-up search field to `TriggerCITests`.

Search follows `start` and `count`. List paging uses `page_number` and `page_size`. Empty selections fail before triggering. A search that reaches `maxTests` is rejected to avoid running a partial quality gate. Narrow the search or raise the limit. Duplicate explicit IDs are submitted once.

`overrides` uses the CI API's field names such as `startUrl`, `locations`, `variables`, `headers` and `retry`. Overrides apply to every selected test. They cannot replace `public_id`. Datadog validates nested override fields. Synthetic create and update definitions require the top-level `name`, `message`, `type`, `config`, `locations` and `options` with their API types. Browser config also requires `request` and `assertions`. Other nested fields remain API pass-through.

### Polling decisions

`Run` and `Evaluate` use a monotonic deadline. The budget includes the trigger request, HTTP transport retries, polls and sleeps. `Run` also includes search selection and best-effort failure-detail reads. An in-flight request is cancelled at the deadline. Cancellation does not cancel the remote Datadog batch or evaluation. If the trigger response does not arrive in time, the output can have no ID even though Datadog accepted the request.

| Synthetic state | Decision |
|---|---|
| All reported results passed and the batch passed | `passed=true` |
| A blocking result failed | Fail by default with test ID, location and failure code |
| A nonblocking result failed | Report failure without failing the task |
| A result or the whole batch was skipped | Preserve `SKIPPED`; a skipped batch has `passed=false` |
| Missing or unknown state | Keep polling; never infer success |
| A passed batch has no result records | Keep polling; an empty answer is not evidence of success |
| HTTP 400, 403 or 404 during polling | Fail with the Datadog status |
| HTTP 429 or 5xx during polling | Retry within the existing deadline |
| Deadline reached | Fail by default; `failOnTimeout: false` returns `timedOut=true` with the last known state |

The synthetic batch status schema lists `passed`, `skipped` and `failed`. Its description mentions progress without defining a progress enum value. The task treats absent or unknown statuses as unfinished. A failed batch with no failed result explaining it is a failure. Missing execution rules are treated as blocking. Failed API and browser results receive a best-effort detail read for their failure code. An unavailable detail endpoint does not turn a failed batch into success.

`Evaluate` uses `in_progress`, `pass` and `fail`. It returns each rule's name, status, reason and dry-run flag. A failed gate fails the task unless `failOnFailure` is false. `dryRun` in the output describes Datadog's persisted gate mode: Datadog can return `pass` for a dry-run gate even when rules fail. Inspect that field when the release policy requires an enforced gate.

`CreateMonitorRule` supports the monitor-query rule variant. It defaults both `fail_on_no_data` and `fail_on_no_groups_found` to true. `healthySeconds` and `warmupSeconds` are nonnegative. `CreateGate.evaluationDryRun` configures Datadog's stored `dry_run` flag. Both values send a POST request that creates the gate. The default is false.

Replace the former `CreateGate.dryRun` input with `evaluationDryRun`. The `Evaluate.dryRun` output still reports the server's persisted evaluation mode. Across the plugin a `dryRun` input means zero HTTP requests and `plan` permits reads only. CreateGate has neither preview mode.

## Limits and verification

- No task claims an upsert or idempotency guarantee. Task retries can create another synthetic batch, gate, rule or deployment. The shared HTTP transport also retries HTTP 429, including POST requests. These tasks do not add a POST retry loop.
- `maxTests` is bounded from 1 to 10000. `List.limitReached` signals a capped result. `fetchType: STORE` moves list or result records to an ION file and leaves the inline collection empty.
- `Results` returns the API's latest window and `lastTimestampFetched`. It does not claim to export all historical results. Use an identified result for full details.
- `Locations` and `GetRules` use unpaged API operations.
- `Delete`, `Update` and `SetStatus` accept `ignoreNotFound`. It defaults to false. Delete sends one public ID and `force_delete_dependencies=false`.
- Polling bounds the local wait. Interrupted HTTP work can still complete remotely. No cancellation endpoint is invoked on timeout.
- Tests use `MockDatadogServer` with fixtures based on the offline OpenAPI schemas. They cover selection, paging, request bodies, validation, HTTP errors, redaction, ION storage and polling decisions. They do not call a live Datadog organization.
- Live entitlement, real CI scheduling, private-location availability, gate timing and eventual DORA ingestion were not verified.
- No `TestFailed` trigger is included. A reliable state transition needs a defined aggregation policy across locations and a historical polling window. This milestone exposes CI-run decisions and result reads instead.

## Operations left to the generic API layer

The following operations have no typed wrapper in this area. They remain assigned to `api.Call` rather than duplicating its catalog. Each row states the reason for every operation in that row.

| Operation IDs | Reason |
|---|---|
| `CreatePrivateLocation`, `DeletePrivateLocation`, `GetPrivateLocation`, `UpdatePrivateLocation`, `GetSyntheticsDefaultLocations` | Private-location provisioning and organization settings are outside the deployment quality-gate path. |
| `ListGlobalVariables`, `CreateGlobalVariable`, `DeleteGlobalVariable`, `GetGlobalVariable`, `EditGlobalVariable`, `PatchGlobalVariable` | Global credential lifecycle needs its own typed redaction and change-management contract. |
| `CreateSyntheticsMobileTest`, `UpdateMobileTest`, `CreateSyntheticsNetworkTest`, `GetSyntheticsNetworkTest`, `UpdateSyntheticsNetworkTest` | Mobile and network authoring are outside the API and browser lifecycle wrappers. Mobile reads are supported by `Get`. |
| `GetTest`, `PatchTest`, `TriggerTests`, `FetchUptimes` | Kind-specific retrieval and the CI trigger provide the release path. Patching and uptime analysis remain generic. |
| `GetApiMultistepSubtests`, `GetApiMultistepSubtestParents`, `GetTestParentSuites` | Dependency inspection is not needed for the bounded CI gate. |
| `ListSyntheticsDowntimes`, `CreateSyntheticsDowntime`, `DeleteSyntheticsDowntime`, `GetSyntheticsDowntime`, `UpdateSyntheticsDowntime`, `RemoveTestFromSyntheticsDowntime`, `AddTestToSyntheticsDowntime` | Synthetics downtime management is separate from test schedule status. |
| `GetOnDemandConcurrencyCap`, `SetOnDemandConcurrencyCap` | Organization-wide capacity settings remain generic. |
| `CreateSyntheticsSuite`, `DeleteSyntheticsSuites`, `SearchSuites`, `GetSyntheticsSuite`, `EditSyntheticsSuite`, `PatchTestSuite`, `DeleteSyntheticsTests` | Suite lifecycle and bulk removal are outside the single-object typed lifecycle. |
| `ListSyntheticsBrowserTestLatestResults`, `GetSyntheticsBrowserTestResult`, `GetSyntheticsFastTestResult`, `PollSyntheticsTestResults`, `ListSyntheticsTestLatestResults`, `GetSyntheticsTestResult`, `ListSyntheticsTestVersions`, `GetSyntheticsTestVersion` | CI batch reads and v1 result reads supply the typed gate. Additional v2 result and version APIs remain generic. |
| `GetTestFileDownloadUrl`, `GetTestFileMultipartPresignedUrls`, `AbortTestFileMultipartUpload`, `CompleteTestFileMultipartUpload` | File transfer and presigned URL handling need a separate storage contract. |
| `ListDeploymentGates`, `ListDeploymentGateEvaluations`, `ListDeploymentRuleEvaluations`, `DeleteDeploymentRule`, `GetDeploymentRule`, `UpdateDeploymentRule`, `DeleteDeploymentGate`, `GetDeploymentGate`, `UpdateDeploymentGate` | The typed release path creates a gate and monitor rule, reads its rules and evaluates it. Inventory, history and destructive gate lifecycle remain generic. |
| `CreateChangeRequest`, `GetChangeRequest`, `UpdateChangeRequest`, `CreateChangeRequestBranch`, `DeleteChangeRequestDecision`, `UpdateChangeRequestDecision` | Change approval lifecycle is separate from evaluating a deployment gate. |
| `CreateAIImpactUserActivity`, `DeleteDORADeployment`, `PatchDORADeploymentByVersion`, `ListDORADeployments`, `GetDORADeployment`, `PatchDORADeployment`, `CreateDORAFailure`, `DeleteDORAFailure`, `ListDORAFailures`, `GetDORAFailure` | Typed delivery support records completed deployments. Analytics, corrections and failure-event lifecycle remain generic. |
| `CreateDORAIncident` | Deprecated operation; no new typed wrapper. |
| `UpdateFlakyTestsManagementPolicies`, `GetFlakyTestsManagementPolicies`, `DeleteTestOptimizationServiceSettings`, `UpdateTestOptimizationServiceSettings`, `GetTestOptimizationServiceSettings`, `UpdateFlakyTests`, `SearchFlakyTests` | Test Optimization settings are separate from Synthetic Monitoring release tests. |
| `ListAppKeyRegistrations`, `UnregisterAppKey`, `GetAppKeyRegistration`, `RegisterAppKey`, `CreateActionConnection`, `DeleteActionConnection`, `GetActionConnection`, `UpdateActionConnection` | Action-connection and application-key lifecycle remain generic to avoid a second credential-management surface. |
| `ListFleetAgentTracers`, `CreateFleetSchedule`, `DeleteFleetSchedule`, `UpdateFleetSchedule`, `TriggerFleetSchedule`, `ListFleetTracers`, `ListFleetAgentVersionsV2`, `ListFleetAgentsV2`, `GetFleetAgentDetailV2`, `ListFleetDeploymentsV2`, `CreateFleetDeploymentConfigureV2`, `CreateFleetDeploymentUpgradeV2`, `GetFleetDeploymentV2`, `CancelFleetDeploymentV2`, `ListFleetSchedulesV2`, `GetFleetScheduleV2` | Fleet inventory and agent rollouts are separate from application deployment quality gates. |
| `ListWorkflows`, `CreateWorkflow`, `DeleteWorkflow`, `GetWorkflow`, `UpdateWorkflow`, `ListWorkflowInstances`, `CreateWorkflowInstance`, `GetWorkflowInstance`, `CancelWorkflowInstance` | The workflow-instance response schema defines IDs and arbitrary additional fields but no terminal-state contract. A typed wait task would invent state semantics. Workflow lifecycle remains generic. |

## References

- [Datadog API reference](https://docs.datadoghq.com/api/latest/)
- [Kestra plugin developer guide](https://kestra.io/docs/plugin-developer-guide)
