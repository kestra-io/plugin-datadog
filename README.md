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

## Why

- What user problem does this solve? Teams that report to Datadog from pipelines or read it from them, do it with ad-hoc `curl` calls and scripts that carry keys, endpoints and error handling of their own.
- Why would a team adopt this plugin in a workflow? It keeps the Datadog steps in the same Kestra flow as the rest of the pipeline, with Kestra secrets, expressions and execution history and with errors that name the HTTP status and what Datadog said.
- What operational/business outcome does it enable? Pipelines can mark deployments and runs on the dashboards, publish business and pipeline metrics, check a metric or the logs before they continue, mute monitors during maintenance and react when a monitor goes into alert.

## What

<!-- datadog-tasks:start -->
| Component | Purpose |
|---|---|
| `api.Call` | Call a Datadog API operation |
| `api.Operations` | Find Datadog API operations |
| `audit.Search` | Search the Datadog audit trail |
| `cases.Assign` | Assign a Datadog case |
| `cases.Comment` | Comment on a Datadog case |
| `cases.Create` | Create a Datadog case |
| `cases.Get` | Get a Datadog case |
| `cases.Search` | Search Datadog cases |
| `cases.SetPriority` | Set a Datadog case priority |
| `cases.SetStatus` | Set a Datadog case status |
| `catalog.Delete` | Delete one Datadog service definition |
| `catalog.UpsertServiceDefinition` | Apply a Datadog service definition |
| `civisibility.SearchPipelines` | Search Datadog CI pipelines |
| `civisibility.SearchTests` | Search Datadog CI tests |
| `cost.Estimated` | Read estimated organization costs |
| `cost.Historical` | Read historical organization costs |
| `dashboards.Create` | Create a Datadog dashboard |
| `dashboards.Delete` | Delete one Datadog dashboard |
| `dashboards.Export` | Export a Datadog dashboard |
| `dashboards.Get` | Get a Datadog dashboard |
| `dashboards.Import` | Apply a Datadog dashboard definition |
| `dashboards.List` | List Datadog dashboards |
| `dashboards.Update` | Update a Datadog dashboard |
| `ddsql.Query` | Run a tabular DDSQL query |
| `deployments.CreateGate` | Create a Datadog deployment gate |
| `deployments.CreateMonitorRule` | Create a monitor-based Datadog gate rule |
| `deployments.Evaluate` | Evaluate a Datadog deployment gate |
| `deployments.GetRules` | Read Datadog deployment gate rules |
| `deployments.Record` | Record a Datadog DORA deployment |
| `downtimes.Cancel` | Cancel a Datadog downtime |
| `downtimes.Create` | Schedule a Datadog downtime that mutes monitor notifications |
| `downtimes.Get` | Get a Datadog downtime |
| `downtimes.List` | List Datadog downtimes |
| `downtimes.Update` | Update one Datadog downtime |
| `events.Create` | Post an event to the Datadog event stream |
| `events.Search` | Search Datadog events |
| `hosts.List` | List Datadog hosts |
| `hosts.Mute` | Mute one host for a bounded period |
| `hosts.Totals` | Read active and up host totals |
| `hosts.Unmute` | Unmute one host |
| `incidents.Create` | Create a Datadog incident |
| `incidents.Delete` | Delete one Datadog incident |
| `incidents.Get` | Get a Datadog incident |
| `incidents.List` | List Datadog incidents |
| `incidents.Search` | Search Datadog incidents |
| `incidents.Update` | Update one Datadog incident |
| `keys.Create` | Create a Datadog key |
| `keys.ListApiKeys` | List Datadog API keys |
| `keys.ListApplicationKeys` | List Datadog application keys |
| `keys.Revoke` | Revoke one Datadog key |
| `keys.Rotate` | Create a replacement Datadog key |
| `logs.Aggregate` | Aggregate Datadog logs |
| `logs.Count` | Count Datadog logs against a threshold |
| `logs.CountThreshold` | Start a flow when a log count crosses a threshold |
| `logs.Search` | Search Datadog logs |
| `logs.Send` | Send log entries to Datadog |
| `metrics.Check` | Check a scalar metric threshold |
| `metrics.Query` | Query timeseries metrics from Datadog |
| `metrics.QueryScalar` | Query scalar metric formulas |
| `metrics.QueryTimeseries` | Query metric timeseries formulas |
| `metrics.Submit` | Submit a custom metric point to Datadog |
| `metrics.Threshold` | Trigger on a metric threshold crossing |
| `monitors.Apply` | Apply Datadog monitors from code |
| `monitors.CanDelete` | Check whether Datadog monitors can be deleted |
| `monitors.Configuration` | Read Datadog monitor configuration |
| `monitors.Create` | Create a Datadog monitor |
| `monitors.Delete` | Delete one Datadog monitor |
| `monitors.Export` | Export Datadog monitor definitions |
| `monitors.Get` | Get one Datadog monitor |
| `monitors.List` | List Datadog monitors |
| `monitors.Search` | Search Datadog monitors with facets |
| `monitors.StateChange` | Start a flow when Datadog monitors change to an alerting state |
| `monitors.Update` | Update one Datadog monitor |
| `monitors.Validate` | Validate a Datadog monitor definition |
| `oncall.Acknowledge` | Acknowledge a Datadog On-Call page |
| `oncall.Escalate` | Escalate a Datadog On-Call page |
| `oncall.Page` | Page a Datadog On-Call team or user |
| `oncall.Resolve` | Resolve a Datadog On-Call page |
| `oncall.WhoIsOnCall` | Read the current On-Call responders for a team |
| `rum.Aggregate` | Aggregate Datadog RUM events |
| `rum.Search` | Search Datadog RUM events |
| `security.SignalsSearch` | Search Datadog security signals |
| `security.TriageSignal` | Triage one Datadog security signal |
| `slos.BudgetLow` | Start a flow when an SLO budget becomes low |
| `slos.Check` | Gate a flow on a Datadog SLO error budget |
| `slos.Create` | Create a Datadog SLO |
| `slos.Delete` | Delete one Datadog SLO |
| `slos.Get` | Get a Datadog SLO |
| `slos.History` | Read Datadog SLO history |
| `slos.List` | List Datadog SLOs |
| `slos.Update` | Update a Datadog SLO |
| `spans.Aggregate` | Aggregate Datadog spans |
| `spans.Count` | Count Datadog spans against a threshold |
| `spans.Search` | Search Datadog spans |
| `statuspages.CreateDegradation` | Create a status page degradation |
| `statuspages.Get` | Get a Datadog status page |
| `statuspages.List` | List Datadog status pages |
| `statuspages.UpdateDegradation` | Update or resolve a status page degradation |
| `synthetics.Create` | Create a Datadog synthetic test |
| `synthetics.Delete` | Delete one Datadog synthetic test |
| `synthetics.Get` | Get a Datadog synthetic test |
| `synthetics.List` | List Datadog synthetic tests |
| `synthetics.Locations` | List Datadog synthetic locations |
| `synthetics.Results` | Read Datadog synthetic test results |
| `synthetics.Run` | Run Datadog synthetic tests as a quality gate |
| `synthetics.SetStatus` | Pause or resume a Datadog synthetic test |
| `synthetics.Trigger` | Trigger Datadog synthetic CI tests |
| `synthetics.Update` | Replace a Datadog synthetic test |
| `teams.List` | List Datadog teams |
| `teams.RemoveMember` | Remove one Datadog team member |
| `teams.SyncMembers` | Sync one Datadog team's members |
| `usage.Check` | Check product usage against a budget |
| `usage.Hourly` | Read hourly product usage |
| `users.Disable` | Disable one Datadog user |
| `users.Get` | Get a Datadog user |
| `users.List` | List Datadog users |
<!-- datadog-tasks:end -->

## Authentication

Provide the keys through Kestra secrets, for example `{{ secret('DATADOG_API_KEY') }}`.

- `apiKey` is needed by every task.
- `appKey` is needed by the calls that read data or manage monitors and downtimes: `metrics.Query`, `monitors.List`, `monitors.StateChange`, `downtimes.Create`, `downtimes.Cancel` and `logs.Search`.
- `site` selects the Datadog site of the organization (`datadoghq.com` by default, `datadoghq.eu`, `us3.datadoghq.com` and so on). A key only works on the site that created it.

See [the plugin documentation](src/main/resources/doc/io.kestra.plugin.datadog.md) for the scopes each call needs and for the behavior of each task and of the trigger.

## Example

```yaml
id: datadog_deploy_window
namespace: company.observability

tasks:
  - id: mute
    type: io.kestra.plugin.datadog.downtimes.Create
    apiKey: "{{ secret('DATADOG_API_KEY') }}"
    appKey: "{{ secret('DATADOG_APP_KEY') }}"
    scope: "env:prod AND service:orders"
    monitorTags: ["service:orders"]
    duration: PT30M

  - id: deploy
    type: io.kestra.plugin.core.log.Log
    message: Deploying the orders service

  - id: mark
    type: io.kestra.plugin.datadog.events.Create
    apiKey: "{{ secret('DATADOG_API_KEY') }}"
    title: "Orders deployed"
    text: "Execution {{ execution.id }}"
    alertType: SUCCESS
    tags: ["env:prod", "source:kestra"]

  - id: unmute
    type: io.kestra.plugin.datadog.downtimes.Cancel
    apiKey: "{{ secret('DATADOG_API_KEY') }}"
    appKey: "{{ secret('DATADOG_APP_KEY') }}"
    downtimeId: "{{ outputs.mute.downtimeId }}"
```

## Tests

`./gradlew test` runs the unit tests against a local mock of the Datadog API. They have not been run against a live Datadog organization.

## Documentation
* Full documentation can be found under: [kestra.io/docs](https://kestra.io/docs)
* Documentation for developing a plugin is included in the [Plugin Developer Guide](https://kestra.io/docs/plugin-developer-guide/)


## License
Apache 2.0 © [Kestra Technologies](https://kestra.io)


## Stay up to date

We release new versions every month. Give the [main repository](https://github.com/kestra-io/kestra) a star to stay up to date with the latest releases and get notified about future updates.
