<p align="center">
  <a href="https://www.kestra.io">
    <img src="https://kestra.io/banner.png"  alt="Kestra workflow orchestrator" />
  </a>
</p>

<h1 align="center" style="border-bottom: none">
    Event-Driven Declarative Orchestrator
</h1>

<div align="center">
 <a href="https://github.com/kestra-io/kestra/releases"><img src="https://img.shields.io/github/tag-pre/kestra-io/kestra.svg?color=blueviolet" alt="Last Version" /></a>
  <a href="https://github.com/kestra-io/kestra/blob/develop/LICENSE"><img src="https://img.shields.io/github/license/kestra-io/kestra?color=blueviolet" alt="License" /></a>
  <a href="https://github.com/kestra-io/kestra/stargazers"><img src="https://img.shields.io/github/stars/kestra-io/kestra?color=blueviolet&logo=github" alt="Github star" /></a> <br>
<a href="https://kestra.io"><img src="https://img.shields.io/badge/Website-kestra.io-192A4E?color=blueviolet" alt="Kestra infinitely scalable orchestration and scheduling platform"></a>
<a href="https://kestra.io/slack"><img src="https://img.shields.io/badge/Slack-Join%20Community-blueviolet?logo=slack" alt="Slack"></a>
</div>

<br />

<p align="center">
  <a href="https://twitter.com/kestra_io" style="margin: 0 10px;">
        <img src="https://kestra.io/twitter.svg" alt="twitter" width="35" height="25" /></a>
  <a href="https://www.linkedin.com/company/kestra/" style="margin: 0 10px;">
        <img src="https://kestra.io/linkedin.svg" alt="linkedin" width="35" height="25" /></a>
  <a href="https://www.youtube.com/@kestra-io" style="margin: 0 10px;">
        <img src="https://kestra.io/youtube.svg" alt="youtube" width="35" height="25" /></a>
</p>

<br />
<p align="center">
    <a href="https://go.kestra.io/video/product-overview" target="_blank">
        <img src="https://kestra.io/startvideo.png" alt="Get started in 3 minutes with Kestra" width="640px" />
    </a>
</p>
<p align="center" style="color:grey;"><i>Get started with Kestra in 3 minutes.</i></p>

# Kestra Datadog Plugin

Run Datadog from a flow. Mark a deployment, mute the right monitors for thirty minutes, wait for a synthetic test and stop the release when the SLO budget is low. All with Kestra secrets, no scripts and no hand-built HTTP calls.

The plugin reaches **every one of the 1,941 operations** of the Datadog API (v1 and v2) through one validated task and gives the jobs people actually automate their own typed tasks with real validation, paging and safe defaults.

## What you can do from a flow

**Hold a release until Datadog says it is healthy.** Run synthetic tests, wait for the batch to finish and fail with the failing test and location.

```yaml
- id: smoke
  type: io.kestra.plugin.datadog.synthetics.Run
  apiKey: "{{ secret('DATADOG_API_KEY') }}"
  appKey: "{{ secret('DATADOG_APP_KEY') }}"
  testIds: [abc-def-ghi]
  maxWait: PT10M
```

**Gate on an error budget.** Fail the flow when the remaining budget of an SLO is below a limit.

```yaml
- id: budget
  type: io.kestra.plugin.datadog.slos.Check
  sloId: 0123456789abcdef0123456789abcdef
  target: 99.9
  minRemainingBudget: 20
```

**Keep monitors in Git.** Apply monitor definitions from YAML. The task reports what it would create or change, shows a field-level diff and a second run changes nothing.

```yaml
- id: apply
  type: io.kestra.plugin.datadog.monitors.Apply
  dryRun: true
  definitions: |
    - name: CPU usage
      type: query alert
      query: "avg(last_5m):avg:system.cpu.user{env:prod} > 80"
      tags: [managed_by:kestra:cpu-prod]
```

**Mute for a deployment, then unmute.** A downtime always needs an end or a duration, so a failed flow cannot leave monitors muted for good.

**Stop a flow on bad telemetry.** Count error logs or spans in a window and branch on the result.

```yaml
- id: gate
  type: io.kestra.plugin.datadog.logs.Count
  query: "service:orders status:error"
  from: now-15m
  threshold: 0
  failOnBreach: true
```

**Page a person, open an incident, post a status update.** `oncall.Page` and the incident tasks can preview the exact request first.

**React when Datadog changes.** Triggers start a flow when a monitor enters a watched state, an SLO budget runs low, a log count crosses a limit or a metric crosses a threshold. Each keeps a cursor, so an alert that stays red starts one execution.

Every example above is validated against a real Kestra 2.0.6 in the test suite.

## Where each part lives

| You want to | Package |
|---|---|
| Monitors, downtimes, monitor notification rules | `monitors`, `downtimes` |
| Dashboards, SLOs, notebooks | `dashboards`, `slos` |
| Synthetic tests and deployment gates | `synthetics`, `deployments` |
| Incidents, cases, paging, status pages | `incidents`, `cases`, `oncall`, `statuspages` |
| Search logs, spans, RUM, audit and CI data | `logs`, `spans`, `rum`, `audit`, `civisibility`, `events` |
| Metrics, hosts, usage and cost | `metrics`, `hosts`, `usage`, `cost`, `ddsql` |
| Users, teams, keys, security signals, service catalog | `users`, `teams`, `keys`, `security`, `catalog` |
| Anything else in the Datadog API | `api.Call`, `api.Operations` |

The full list of tasks, grouped by package, is in [`docs/TASKS.md`](docs/TASKS.md).

## How much of Datadog this covers

The generated [coverage report](docs/COVERAGE.md) maps each Datadog operation to the task that implements it.

- 1,941 operations in the official API description.
- 122 of them have a typed task. The typed tasks are the ones a flow author reaches for: they validate input, page results, redact secrets and offer previews.
- The other 1,819 are reachable through `api.Call`. It checks the operation, the path and query parameters and the required body against a built-in catalog before it sends anything and `api.Operations` searches that catalog.

## Safe by default

- Anything that deletes, disables, revokes or overwrites works on **one named object**. There is no "delete everything matching".
- Downtimes and host mutes need an end or a duration.
- Tasks that apply a definition can run as a preview first and show the exact changes.
- Key values, tokens and webhook secrets are redacted from outputs unless you set `includeSecrets: true`. Keys are never written to a log.
- Tasks that page people or open incidents can preview the exact request before sending it.

## Authentication

Every task needs `apiKey`. Tasks that read data or manage things also need `appKey`, created with the scopes that task needs (each task page lists them). Set `site` to the domain of your organization (`datadoghq.com` by default, `datadoghq.eu`, `us3.datadoghq.com` and so on). A key only works on the site that issued it.

## Build and test

```sh
./gradlew check        # unit tests, contract tests and the documentation lint
./gradlew shadowJar    # build/libs/plugin-datadog-*.jar
python3 e2e/run.py --jar build/libs/plugin-datadog-1.0.0-SNAPSHOT.jar --artifact-commit "$(git rev-parse HEAD)" --evidence /tmp/evidence --include-api
```

The last full run: 3,461 tests passed and 0 failed. 31 executions of every task and trigger ran in Kestra 2.0.6 against a mock of the Datadog API and 157 example flows validated.

## What has not been verified

No test has run against a live Datadog organization. The mock server follows the official OpenAPI description and a contract check compares requests against it for the highest-risk tasks (14 test classes so far; [details](docs/CONTRACT-TESTS.md)). Rate-limit behavior, permissions and server-side validation on a real account are covered only by that mock. Known issues from the integration audit are in [`docs/INTEGRATION-FINDINGS.md`](docs/INTEGRATION-FINDINGS.md).

## Documentation

- [Kestra plugin documentation](https://kestra.io/docs/plugin-developer-guide)
- [Datadog API reference](https://docs.datadoghq.com/api/latest/)

## License

Apache 2.0 © [Kestra Technologies](https://kestra.io)
