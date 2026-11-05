"""Reviewable task scenarios derived from pinned operation schemas and source examples.

Fixtures are isolated per task. Unknown requests fail instead of receiving a generic 200.
"""
import copy
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ID = "00000000-0000-0000-0000-000000000001"
OTHER = "00000000-0000-0000-0000-000000000002"
SLO = "0123456789abcdef0123456789abcdef"
PUBLIC = "abc-def-ghi"
VALUE = "fixture-credential-value"


def resource(kind, attributes=None, identifier=ID):
    result = {"id": identifier, "type": kind}
    if attributes is not None:
        result["attributes"] = attributes
    return result


def data(kind, attributes=None, identifier=ID):
    return {"data": resource(kind, attributes, identifier)}


def build():
    spec = importlib.util.spec_from_file_location("inventory", ROOT / "scripts/task-inventory.py")
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    inventory = module.inventory(ROOT / "src/main/java/io/kestra/plugin/datadog")
    index = json.loads((ROOT / "src/test/resources/openapi/index.json").read_text())
    operations = {}
    for file in set(index.values()):
        operations.update(json.loads((ROOT / "src/test/resources/openapi" / file).read_text())["operations"])
    scenarios = {}

    def reply(operation, body=None, status=None, **params):
        op = operations[operation]
        import re
        path = re.sub(r"\{([^}]+)\}", lambda m: str(params.get(m[1], ID)), op["path"])
        return {"operation": operation, "method": op["method"], "path": path,
                "status": status or int(next(iter(op["responses"]))), "body": body,
                "contentType": next(iter(op["responses"][str(status or int(next(iter(op["responses"]))))]["content"]), "application/json") if status is None or status < 300 else "application/json"}

    def add(name, replies, expected, **overrides):
        props = copy.deepcopy(inventory[name]["example"])
        props.update(overrides)
        props.pop("site", None)
        # Fixed inputs make the same scenario executable by Java and real Kestra.
        for key in ("message", "comment", "text"):
            if key in props:
                props[key] = "e2e fixture"
        if "includeSecrets" in props:
            props["includeSecrets"] = False
        if "store" in props:
            props["store"] = False
        if "dryRun" in inventory[name]["properties"]:
            props["dryRun"] = False
        def literal(value):
            if isinstance(value, dict):
                return {k: literal(v) for k, v in value.items()}
            if isinstance(value, list):
                return [literal(v) for v in value]
            if isinstance(value, str) and "{{" in value:
                return "e2e"
            return value
        props = literal(props)
        scenarios[name] = {"task": name, "properties": props, "replies": replies, "expected": expected,
                           "dryRun": "dryRun" in inventory[name]["properties"]}

    # Incident response and case-management JSON:API resources.
    incident = data("incidents", {"title": "e2e incident", "customer_impacted": True})
    for task, op in {"Create": "CreateIncident", "Get": "GetIncident", "Update": "UpdateIncident"}.items():
        add("incidents." + task, [reply("v2:" + op, incident)], {"id": ID, "dryRun": False})
    add("incidents.Delete", [reply("v2:DeleteIncident")], {"statusCode": 204})
    add("incidents.List", [reply("v2:ListIncidents", {"data": [incident["data"]]})], {"count": 1})
    add("incidents.Search", [reply("v2:SearchIncidents", data("incidents_search_results", {"incidents": [incident], "facets": {}, "total": 1}))], {"count": 1})
    case = data("case", {"title": "e2e case", "priority": "P2", "status_name": "Open"})
    for task, op in {"Create": "CreateCase", "Get": "GetCase", "Assign": "AssignCase", "SetStatus": "UpdateStatus", "SetPriority": "UpdatePriority"}.items():
        add("cases." + task, [reply("v2:" + op, case)], {"id": ID})
    add("cases.Search", [reply("v2:SearchCases", {"data": [case["data"]]})], {"count": 1})
    comment = resource("timeline_cell", {"type": "COMMENT", "cell_content": {"message": "e2e fixture"}, "created_at": "2026-01-01T00:00:00Z"})
    add("cases.Comment", [reply("v2:CommentCase", {"data": [comment]})], {"statusCode": 200})
    add("oncall.Page", [reply("v2:CreateOnCallPage", data("pages"))], {"id": ID})
    for task in ("Acknowledge", "Escalate", "Resolve"):
        add("oncall." + task, [reply("v2:" + task + "OnCallPage")], {"statusCode": 202})
    oncall = data("team_oncall_responders")
    oncall["data"]["relationships"] = {"responders": {"data": [resource("users", identifier=OTHER)]}}
    add("oncall.WhoIsOnCall", [reply("v2:GetTeamOnCallUsers", oncall)], {"id": ID})
    page = data("status_pages", {"name": "Orders", "domain_prefix": "orders", "enabled": True})
    add("statuspages.Get", [reply("v2:GetStatusPage", page)], {"id": ID})
    add("statuspages.List", [reply("v2:ListStatusPages", {"data": [page["data"]]})], {"count": 1})
    for task, op in (("CreateDegradation", "CreateDegradation"), ("UpdateDegradation", "UpdateDegradation")):
        add("statuspages." + task, [reply("v2:" + op, data("degradations", {"title": "e2e", "status": "investigating"}), degradation_id=OTHER)], {"id": ID})

    # Dashboard definitions and read-before-write import.
    definition = {"title": "Orders", "layout_type": "ordered", "widgets": []}
    dashboard = {"id": PUBLIC, **definition}
    for task, op in (("Create", "CreateDashboard"), ("Get", "GetDashboard"), ("Update", "UpdateDashboard")):
        add("dashboards." + task, [reply("v1:" + op, dashboard, dashboard_id=PUBLIC)], {"dashboard.id": PUBLIC})
    add("dashboards.Delete", [reply("v1:DeleteDashboard", {"deleted_dashboard_id": PUBLIC}, dashboard_id=PUBLIC)], {"deleted": True})
    add("dashboards.List", [reply("v1:ListDashboards", {"dashboards": [dashboard]})], {"size": 1})
    add("dashboards.Export", [reply("v1:GetDashboard", dashboard, dashboard_id=PUBLIC)], {"uri": "$defined"})
    add("dashboards.Import", [reply("v1:GetDashboard", dashboard, dashboard_id=PUBLIC), reply("v1:UpdateDashboard", dashboard, dashboard_id=PUBLIC)],
        {"action": "UPDATED"}, dashboardId=PUBLIC, definition={**definition, "title": "Updated"})
    scenarios["dashboards.Import"]["properties"].pop("managingTag", None)

    # Existing base operations and monitor extras.
    monitor = {"id": 12345, "name": "e2e", "type": "query alert", "query": "avg(last_5m):avg:system.cpu.user{*} > 90", "tags": ["env:e2e"]}
    add("events.Create", [reply("v1:CreateEvent", {"status": "ok", "event": {"id": 123, "id_str": "123", "title": "e2e", "text": "e2e"}})], {"eventId": "123"})
    add("metrics.Submit", [reply("v2:SubmitMetrics", {"errors": []})], {"points": 1, "series": 1})
    add("metrics.Query", [reply("v1:QueryMetrics", {"status": "ok", "series": [{"metric": "e2e", "pointlist": [[1767225600000, 1.0]]}]})], {"status": "ok"})
    add("logs.Send", [reply("v2:SubmitLog", {})], {"sent": 1}, intakeUrl="__MOCK_URL__")
    downtime = data("downtime", {"status": "active", "scope": "env:e2e"})
    for task, op in (("Create", "CreateDowntime"), ("Get", "GetDowntime"), ("Update", "UpdateDowntime")):
        overrides = {} if task == "Create" else {"downtimeId": ID}
        add("downtimes." + task, [reply("v2:" + op, downtime)], {"downtimeId": ID} if task == "Create" else {"downtime.data.id": ID}, **overrides)
    add("downtimes.List", [reply("v2:ListDowntimes", {"data": [downtime["data"]]})], {"size": 1})
    add("downtimes.Cancel", [reply("v2:CancelDowntime")], {"cancelled": True}, downtimeId=ID)
    for task, op in (("Create", "CreateMonitor"), ("Get", "GetMonitor"), ("Update", "UpdateMonitor")):
        add("monitors." + task, [reply("v1:" + op, monitor, monitor_id=12345)], {"monitor.id": 12345}, **({} if task == "Create" else {"monitorId": 12345}))
    add("monitors.List", [reply("v1:ListMonitors", [{**monitor, "overall_state": "Alert"}])], {"size": 1})
    add("monitors.Export", [reply("v1:ListMonitors", [monitor])], {"uri": "$defined"})
    add("monitors.Delete", [reply("v1:DeleteMonitor", monitor, monitor_id=12345)], {"deleted": True}, monitorId=12345)
    add("monitors.Validate", [reply("v1:ValidateMonitor", {})], {"valid": True})
    add("monitors.CanDelete", [reply("v1:CheckCanDeleteMonitor", {"data": {"ok": [3000000000]}})], {"result.data.ok.0": 3000000000}, monitorIds=[3000000000])
    add("monitors.Search", [reply("v1:SearchMonitors", {"monitors": [monitor], "metadata": {"total_count": 1, "page_count": 1, "page": 0, "per_page": 100}, "counts": {}})], {"size": 1})
    add("monitors.Configuration", [reply("v2:GetMonitorNotificationRules", {"data": []})], {"size": 0})
    add("monitors.Apply", [reply("v1:ListMonitors", []), reply("v1:ValidateMonitor", {}), reply("v1:CreateMonitor", monitor)], {"results.0.action": "CREATED"})

    # Telemetry search schemas differ by product; each uses its documented resource type.
    searches = {"logs.Search": ("ListLogs", "/logs", "log"), "spans.Search": ("ListSpans", "/spans", "spans"),
                "rum.Search": ("SearchRUMEvents", "/rum", "rum"), "audit.Search": ("SearchAuditLogs", "/audit", "audit"),
                "events.Search": ("SearchEvents", "/events", "event"), "civisibility.SearchPipelines": ("SearchCIAppPipelineEvents", "", "cipipeline"),
                "civisibility.SearchTests": ("SearchCIAppTestEvents", "", "citest")}
    for name, (op, _, kind) in searches.items():
        row = resource(kind, {"message": "e2e", "timestamp": "2026-01-01T00:00:00Z"})
        add(name, [reply("v2:" + op, {"data": [row]})], {"size": 1})
    for area, op in (("logs", "AggregateLogs"), ("spans", "AggregateSpans"), ("rum", "AggregateRUMEvents")):
        buckets = {"by": {}, "computes": {"c0": 1}}
        answer = {"data": [resource("bucket", buckets)]} if area == "spans" else {"data": {"buckets": [buckets]}}
        add(area + ".Aggregate", [reply("v2:" + op, answer)], {"size": 1})
        if area != "rum":
            add(area + ".Count", [reply("v2:" + op, answer)], {"count": 1, "breached": False}, threshold=10)
    scalar = data("scalar_response", {"columns": [{"type": "number", "name": "a", "values": [42]}]})
    add("metrics.QueryScalar", [reply("v2:QueryScalarData", scalar)], {"columnCount": 1})
    add("metrics.Check", [reply("v2:QueryScalarData", scalar)], {"value": 42, "breached": False})
    timeseries = data("timeseries_response", {"series": [{"query_index": 0, "group_tags": ["env:e2e"]}], "times": [1767225600000], "values": [[42]]})
    add("metrics.QueryTimeseries", [reply("v2:QueryTimeseriesData", timeseries)], {"seriesCount": 1})

    # SLO schemas use v1 data envelopes.
    slo = {"id": SLO, "name": "Orders", "type": "metric", "thresholds": [{"timeframe": "7d", "target": 99.9}],
           "query": {"numerator": "sum:orders.good{*}.as_count()", "denominator": "sum:orders.total{*}.as_count()"}}
    for task, op in (("Create", "CreateSLO"), ("Update", "UpdateSLO")):
        add("slos." + task, [reply("v1:" + op, {"data": [slo], "errors": []}, slo_id=SLO)], {"slos.0.id": SLO})
    add("slos.Get", [reply("v1:GetSLO", {"data": slo, "errors": []}, slo_id=SLO)], {"slo.id": SLO})
    add("slos.List", [reply("v1:ListSLOs", {"data": [slo], "errors": []})], {"size": 1})
    add("slos.Delete", [reply("v1:DeleteSLO", {"data": [SLO], "errors": {}}, slo_id=SLO)], {"deleted": True})
    history = {"data": {"from_ts": 100, "to_ts": 200, "type": "metric", "overall": {"sli_value": 99.95, "span_precision": 3, "errors": []}}, "errors": []}
    for task in ("History", "Check"):
        add("slos." + task, [reply("v1:GetSLOHistory", history, slo_id=SLO)], {"sli": 99.95} if task == "Check" else {"history.overall.sli_value": 99.95}, **{"from": 100, "to": 200})

    # Organization access and service catalog.
    key = data("api_keys", {"name": "e2e", "key": VALUE, "last4": "1234"})
    appkey = data("application_keys", {"name": "e2e", "key": VALUE, "scopes": ["user_access_read"]})
    add("keys.Create", [reply("v2:CreateCurrentUserApplicationKey", appkey)], {"keyId": ID, "key": "[REDACTED]"})
    add("keys.Rotate", [reply("v2:GetAPIKey", key), reply("v2:CreateAPIKey", key)], {"keyId": ID, "key": "[REDACTED]"})
    add("keys.Revoke", [reply("v2:DeleteAPIKey")], {"revoked": True})
    add("keys.ListApiKeys", [reply("v2:ListAPIKeys", {"data": [key["data"]]})], {"size": 1, "rows.0.attributes.key": "[REDACTED]"})
    add("keys.ListApplicationKeys", [reply("v2:ListApplicationKeys", {"data": [appkey["data"]]})], {"size": 1})
    users = [resource("users", {"handle": "admin@example.com", "disabled": False}), resource("users", {"handle": "engineer@example.com", "disabled": False}, OTHER)]
    add("users.List", [reply("v2:ListUsers", {"data": users})], {"size": 2})
    add("users.Get", [reply("v2:GetUser", {"data": users[0]})], {"user.id": ID})
    add("users.Disable", [reply("v2:DisableUser")], {"disabled": True})
    add("teams.List", [reply("v2:ListTeams", {"data": [resource("team", {"name": "e2e", "handle": "e2e"})]})], {"size": 1})
    def membership(identifier, role):
        row = resource("team_memberships", {"role": role}, "membership-" + identifier)
        row["relationships"] = {"user": {"data": resource("users", identifier=identifier)}}
        return row
    admin, engineer = membership(ID, "admin"), membership(OTHER, None)
    add("teams.SyncMembers", [reply("v2:ListUsers", {"data": users}), reply("v2:GetTeamMemberships", {"data": [admin]}), reply("v2:CreateTeamMembership", {"data": engineer})], {"status": "UPDATED", "added.0": "engineer@example.com"})
    add("teams.RemoveMember", [reply("v2:GetTeamMemberships", {"data": [admin, engineer]}), reply("v2:DeleteTeamMembership", user_id=OTHER)], {"removed": True})
    add("security.SignalsSearch", [reply("v2:SearchSecurityMonitoringSignals", {"data": [resource("signal", {"message": "e2e"})]})], {"size": 1})
    add("security.TriageSignal", [reply("v2:EditSecurityMonitoringSignal", data("signal_metadata", {"state": "under_review", "assignee": {"uuid": ID}, "incident_ids": []}), signal_id="signal-id")], {"updated": True})
    desired = inventory["catalog.UpsertServiceDefinition"]["example"]["definition"]
    add("catalog.UpsertServiceDefinition", [reply("v2:GetServiceDefinition", {"errors": ["missing"]}, 404, service_name="checkout"),
        reply("v2:CreateOrUpdateServiceDefinitions", {"data": [resource("service-definition", {"schema": desired}, "checkout")]})], {"status": "CREATED", "serviceName": "checkout"})
    add("catalog.Delete", [reply("v2:DeleteServiceDefinition", service_name="retired-service")], {"deleted": True})

    # Hosts and account usage.
    add("hosts.List", [reply("v1:ListHosts", {"host_list": [{"host_name": "worker-01", "is_muted": False}], "total_matching": 1, "total_returned": 1})], {"count": 1})
    add("hosts.Totals", [reply("v1:GetHostTotals", {"total_active": 2, "total_up": 1})], {"totalActive": 2})
    for task, action in (("Mute", "Muted"), ("Unmute", "Unmuted")):
        add("hosts." + task, [reply("v1:" + task + "Host", {"action": action, "hostname": "worker-01"}, host_name="worker-01")], {"action": action, "found": True})
    usage = {"data": [resource("usage_timeseries", {"timestamp": "2026-01-01T00:00:00Z", "product_family": "infra_hosts", "public_id": "parent", "measurements": [{"usage_type": "infra_host_count", "value": 20}]})]}
    add("usage.Hourly", [reply("v2:GetHourlyUsage", usage)], {"count": 1})
    add("usage.Check", [reply("v2:GetHourlyUsage", usage)], {"value": 20, "breached": False})
    for task, op in (("Estimated", "GetEstimatedCostByOrg"), ("Historical", "GetHistoricalCostByOrg")):
        add("cost." + task, [reply("v2:" + op, {"data": [resource("cost_by_org", {"date": "2026-01-01T00:00:00Z", "org_name": "e2e", "public_id": "parent", "charges": []})]})], {"count": 1}, startMonth="2026-01")
    sql = data("ddsql_query_response", {"state": "completed", "columns": [{"name": "host_count", "type": "BIGINT", "values": [42]}]})
    sql["meta"] = {"elapsed": 10, "request_id": "e2e"}
    add("ddsql.Query", [reply("v2:ExecuteDdsqlTabularQuery", sql)], {"state": "COMPLETED", "rowCount": 1}, query="SELECT 42", pollDelay="PT0.01S")

    # Synthetic API test lifecycle and CI batches.
    synthetic = {"public_id": PUBLIC, **inventory["synthetics.Create"]["example"]["definition"]}
    for task, op in (("Create", "CreateSyntheticsAPITest"), ("Get", "GetAPITest"), ("Update", "UpdateAPITest")):
        add("synthetics." + task, [reply("v1:" + op, synthetic, public_id=PUBLIC)], {"test.public_id": PUBLIC})
    add("synthetics.Delete", [reply("v1:DeleteTests", {"deleted_tests": [{"public_id": PUBLIC, "deleted_at": "2026-01-01T00:00:00Z"}]})], {"deleted": True})
    add("synthetics.List", [reply("v1:SearchTests", {"tests": [synthetic], "total": 1})], {"size": 1})
    add("synthetics.Locations", [reply("v1:ListLocations", {"locations": [{"id": "aws:eu-west-3", "name": "Paris"}]})], {"locations.0.id": "aws:eu-west-3"})
    add("synthetics.Results", [reply("v1:GetAPITestLatestResults", {"results": [{"result_id": "result-1", "status": 0, "check_time": 1767225600}]}, public_id=PUBLIC)], {"size": 1})
    add("synthetics.SetStatus", [reply("v1:UpdateTestPauseStatus", True, public_id=PUBLIC)], {"accepted": True})
    triggered = {"batch_id": ID, "results": [{"public_id": PUBLIC, "result_id": "result-1", "location": 1}]}
    add("synthetics.Trigger", [reply("v1:TriggerCITests", triggered)], {"batchId": ID})
    batch = {"data": {"status": "passed", "results": [{"test_public_id": PUBLIC, "result_id": "result-1", "test_type": "api", "location": "aws:eu-west-3", "status": "passed", "execution_rule": "blocking"}]}}
    add("synthetics.Run", [reply("v1:TriggerCITests", triggered), reply("v1:GetSyntheticsCIBatch", batch)], {"passed": True, "timedOut": False}, maxWait="PT5S", pollInterval="PT0.01S")

    # Deployment gates use a server-side dry_run attribute rather than a local preview.
    created = {"created_at": "2026-01-01T00:00:00Z", "created_by": {"id": ID}, "dry_run": False}
    add("deployments.CreateGate", [reply("v2:CreateDeploymentGate", data("deployment_gate", {**created, "service": "checkout", "env": "production", "identifier": "default"}))], {"gateId": ID})
    add("deployments.CreateMonitorRule", [reply("v2:CreateDeploymentRule", data("deployment_rule", {**created, "gate_id": ID, "name": "e2e", "options": {"query": "service:checkout"}, "type": "monitor"}), gate_id=ID)], {"ruleId": ID}, gateId=ID)
    add("deployments.GetRules", [reply("v2:GetDeploymentGateRules", data("list_deployment_rules", {"rules": []}), gate_id=ID)], {"size": 0}, gateId=ID)
    accepted = data("deployment_gates_evaluation_response", {"evaluation_id": ID})
    result = data("deployment_gates_evaluation_result_response", {"evaluation_id": ID, "evaluation_url": "https://example.test/evaluation", "gate_id": ID, "gate_status": "pass", "dry_run": False, "rules": []})
    add("deployments.Evaluate", [reply("v2:TriggerDeploymentGatesEvaluation", accepted), reply("v2:GetDeploymentGatesEvaluationResult", result)], {"status": "PASS", "timedOut": False}, maxWait="PT5S", pollInterval="PT0.01S")
    add("deployments.Record", [reply("v2:CreateDORADeployment", {"data": {"id": ID, "type": "dora_deployment"}})], {"deploymentId": ID})
    # Generic helpers are also executed. Catalog enumeration has no network fixture.
    add("api.Call", [reply("v1:GetMonitor", monitor, monitor_id=12345)], {"statusCode": 200})
    add("api.Operations", [], {"count": "$defined"})
    # Exercise 64-bit identifiers. Small literal array IDs have a separate regression finding.
    scenarios["slos.Update"]["properties"]["monitorIds"] = [3000000000]
    missing = set(inventory) - set(scenarios) - {name for name, row in inventory.items() if row["trigger"]}
    if missing:
        raise ValueError("Tasks missing scenarios: " + ", ".join(sorted(missing)))
    return scenarios, inventory
