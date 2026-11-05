#!/usr/bin/env python3
"""Generate area flows and the shared Java scenario manifest from reviewed fixtures."""
import json
from collections import defaultdict

import yaml

from scenarios import ROOT, build


class FlowDumper(yaml.SafeDumper):
    # Kestra Property values must be concrete values, not YAML alias identifiers.
    def ignore_aliases(self, data):
        return True


def access(prefix, field):
    return prefix + "".join("[" + part + "]" if part.isdigit() else "." + part for part in field.split("."))


def main():
    scenarios, inventory = build()
    flows = defaultdict(list)
    for name, scenario in sorted(scenarios.items()):
        area, task = name.split(".")
        identifier = task.lower()
        props = dict(scenario["properties"])
        base = "__MOCK_URL__/scenarios/" + name
        props.update(id=identifier, type="io.kestra.plugin.datadog." + name)
        if name != "api.Operations":
            props.update(apiKey="e2e-api-key", appKey="e2e-app-key", apiUrl=base)
        if name == "logs.Send":
            props["intakeUrl"] = base
        mode = "plan" if scenario["plan"] else "dryRun" if scenario["dryRun"] else None
        if mode:
            suffix = "_plan" if mode == "plan" else "_dry"
            preview = {**props, "id": identifier + suffix, mode: True, "apiUrl": base + suffix.replace("_", "-")}
            flows[area].append(preview)
            checks = ["{{ outputs." + preview["id"] + "." + mode + " == true }}"]
            flows[area].append({"id": preview["id"] + "_assert", "type": "io.kestra.plugin.core.execution.Assert", "conditions": checks})
        if name == "deployments.CreateGate":
            evaluation = {**props, "id": identifier + "_evaluation_dry", "evaluationDryRun": True}
            flows[area].append(evaluation)
            flows[area].append({"id": evaluation["id"] + "_assert", "type": "io.kestra.plugin.core.execution.Assert",
                                "conditions": ["{{ outputs." + evaluation["id"] + ".gateId == '00000000-0000-0000-0000-000000000001' }}"]})
        flows[area].append(props)
        conditions = []
        for field, value in scenario["expected"].items():
            expression = access("outputs." + identifier, field)
            expression += " is defined" if value == "$defined" else " == " + json.dumps(value)
            conditions.append("{{ " + expression + " }}")
        flows[area].append({"id": identifier + "_assert", "type": "io.kestra.plugin.core.execution.Assert", "conditions": conditions})
    for area, tasks in flows.items():
        flow = {"id": "datadog_e2e_" + area, "namespace": "hq.datadog.e2e", "tasks": tasks}
        (ROOT / "e2e/flows" / (area + ".yaml")).write_text(yaml.dump(flow, Dumper=FlowDumper, sort_keys=False, width=120))
    for name, row in inventory.items():
        if not row["trigger"]:
            continue
        props = {**row["example"], "id": "watch", "type": "io.kestra.plugin.datadog." + name,
                 "apiKey": "e2e-api-key", "appKey": "e2e-app-key", "apiUrl": "__MOCK_URL__/triggers/" + name, "interval": "PT1S"}
        field = {"monitors.StateChange": "count", "logs.CountThreshold": "count", "metrics.Threshold": "value", "slos.BudgetLow": "remainingBudget"}[name]
        flow = {"id": "datadog_e2e_" + name.replace(".", "_").lower(), "namespace": "hq.datadog.e2e",
                "triggers": [props], "tasks": [{"id": "assert_trigger", "type": "io.kestra.plugin.core.execution.Assert",
                "conditions": ["{{ trigger." + field + " is defined }}"]}]}
        (ROOT / "e2e/flows" / ("trigger_" + name.replace(".", "_").lower() + ".yaml")).write_text(yaml.dump(flow, Dumper=FlowDumper, sort_keys=False))
    (ROOT / "e2e/fixtures.json").write_text(json.dumps(scenarios, sort_keys=True, separators=(",", ":")) + "\n")
    print(f"Generated {len(flows)} area flows, {sum(row['trigger'] for row in inventory.values())} trigger flows and {len(scenarios)} task scenarios")


if __name__ == "__main__":
    main()
