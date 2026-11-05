#!/usr/bin/env python3
"""Run a supplied plugin jar in disposable Kestra with mock Datadog and save evidence."""
import argparse
import base64
import hashlib
import json
import re
import socket
import subprocess
import textwrap
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import yaml

from mock_datadog import MockDatadog, now


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def examples(root):
    found = []
    for file in sorted((root / "src/main/java/io/kestra/plugin/datadog").rglob("*.java")):
        for index, match in enumerate(re.finditer(r'@Example\(.*?full\s*=\s*true.*?code\s*=\s*"""(.*?)"""', file.read_text(), re.S)):
            found.append((f"{file.relative_to(root)}:{index + 1}", textwrap.dedent(match.group(1)).strip() + "\n"))
    files = [root / "README.md"] + sorted((root / "docs").rglob("*.md")) + sorted((root / "src/main/resources/doc").glob("*.md"))
    for file in files:
        if not file.exists():
            continue
        for index, match in enumerate(re.finditer(r"```ya?ml\s*\n(.*?)```", file.read_text(), re.S)):
            text = match.group(1)
            value = yaml.safe_load(text)
            if isinstance(value, dict) and "id" in value and "namespace" in value and ("tasks" in value or "triggers" in value):
                found.append((f"{file.relative_to(root)}:{index + 1}", text))
    return found


def components(root):
    result = set()
    source = root / "src/main/java/io/kestra/plugin/datadog"
    for file in sorted(source.rglob("*.java")):
        text = file.read_text()
        if re.search(r"@Plugin\s*\(", text) and not re.search(r"public\s+abstract\s+class", text):
            result.add("io.kestra.plugin.datadog." + str(file.relative_to(source).with_suffix("")).replace("/", "."))
    return result


def flow_components(value):
    """Only executable task and trigger nodes count; comments and strings do not."""
    found = set()
    if isinstance(value, dict):
        if str(value.get("type", "")).startswith("io.kestra.plugin.datadog."):
            found.add(value["type"])
        for key in ("tasks", "triggers", "then", "else", "errors", "finally"):
            found.update(flow_components(value.get(key)))
    elif isinstance(value, list):
        for child in value:
            found.update(flow_components(child))
    return found


def triggered_executions(client, flow):
    result = client.request("GET", "/executions?" + urlencode({"namespace": flow["namespace"], "flowId": flow["id"], "size": 100}))
    return [entry for entry in result.get("results", []) if entry.get("trigger")]


def missing_components(root):
    covered = set()
    for file in (root / "e2e/flows").glob("*.yaml"):
        covered.update(flow_components(yaml.safe_load(file.read_text())))
    return components(root) - covered


def exercise_triggers(client, flows, mock, evidence, summary):
    for flow in flows:
        name = flow["triggers"][0]["type"].removeprefix("io.kestra.plugin.datadog.")
        prefix = "/triggers/" + name + "/"
        polls = lambda: sum(row["path"].startswith(prefix) for row in mock.requests)
        deadline = time.monotonic() + 60
        while polls() < 2 and time.monotonic() < deadline:
            time.sleep(0.5)
        if polls() < 2:
            summary["errors"].append(name + " did not complete baseline polls")
            continue
        if triggered_executions(client, flow):
            summary["errors"].append(name + " fired before the fixture changed")
        if name == "monitors.StateChange":
            mock.modified = now()
        mock.trigger_active.add(name)
        deadline = time.monotonic() + 60
        executions = []
        while time.monotonic() < deadline:
            executions = triggered_executions(client, flow)
            if executions:
                break
            time.sleep(0.5)
        for triggered in executions:
            execution = client.wait_execution(triggered["id"])
            stem = name.replace(".", "_")
            (evidence / (stem + "-trigger-execution.json")).write_text(json.dumps(execution, indent=2))
            (evidence / (stem + "-trigger-logs.json")).write_text(json.dumps(client.request("GET", "/logs/" + triggered["id"]), indent=2))
            summary["executions"].append({"flow": flow["id"], "id": triggered["id"], "component": name, "triggered": True, "state": execution["state"]["current"]})
            if execution["state"]["current"] != "SUCCESS":
                summary["errors"].append(name + " triggered execution failed")
        baseline = polls()
        deadline = time.monotonic() + 30
        while polls() < baseline + 3 and time.monotonic() < deadline:
            time.sleep(0.5)
        executions = triggered_executions(client, flow)
        summary.setdefault("triggers", []).append({"component": name, "executions": len(executions), "followupPolls": polls() - baseline})
        if len(executions) != 1 or polls() < baseline + 3:
            summary["errors"].append(name + " did not fire exactly once across follow-up polls")


class Kestra:
    def __init__(self, port):
        self.url = f"http://127.0.0.1:{port}/api/v1/main"
        self.auth = "Basic " + base64.b64encode(b"e2e@example.test:MockKestra123!").decode()

    def request(self, method, path, body=None, content_type="application/x-yaml"):
        data = body.encode() if isinstance(body, str) else body
        req = Request(self.url + path, data=data, method=method, headers={"Authorization": self.auth, "Content-Type": content_type})
        try:
            with urlopen(req, timeout=60) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except HTTPError as error:
            raise RuntimeError(f"Kestra {method} {path}: HTTP {error.code}: {error.read().decode()[:2000]}") from None

    def wait(self):
        deadline = time.monotonic() + 180
        error = None
        while time.monotonic() < deadline:
            try:
                self.request("GET", "/flows/search")
                return
            except (RuntimeError, URLError, TimeoutError) as failure:
                error = failure; time.sleep(2)
        raise RuntimeError(f"Kestra did not become ready: {error}")

    def wait_execution(self, execution_id):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            result = self.request("GET", "/executions/" + execution_id)
            if result["state"]["current"] in {"SUCCESS", "FAILED", "KILLED", "CANCELLED", "WARNING"}:
                return result
            time.sleep(0.5)
        raise RuntimeError(f"Execution {execution_id} did not finish")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--source", type=Path, default=Path("."))
    parser.add_argument("--image", default="kestra/kestra:v2.0.6")
    parser.add_argument("--include-api", action="store_true", help="Compatibility flag; generic API flows are always included")
    parser.add_argument("--allow-partial", action="store_true", help="Report tasks lacking e2e flows without rejecting the run")
    parser.add_argument("--artifact-commit", required=True, help="Commit from which the jar was built")
    args = parser.parse_args()
    root = args.source.resolve()
    jar = args.jar.resolve()
    evidence = args.evidence.resolve(); evidence.mkdir(parents=True, exist_ok=True)
    summary = {"image": args.image, "artifactCommit": args.artifact_commit, "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
               "started": now(), "executions": [], "validations": [], "errors": []}
    port, management, controller = free_port(), free_port(), free_port()
    name = "dd-d09-e2e-complete-e2e-" + str(port)
    client = Kestra(port)
    mock = MockDatadog().start()
    configuration = {
        "datasources": {"h2": {"url": "jdbc:h2:mem:e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;TIME ZONE=UTC", "username": "sa", "password": "", "driverClassName": "org.h2.Driver"}},
        "kestra": {"repository": {"type": "h2"}, "queue": {"type": "h2"}, "storage": {"type": "local", "local": {"base-path": "/tmp/e2e-storage"}},
                   "server": {"basic-auth": {"enabled": True, "username": "e2e@example.test", "password": "MockKestra123!"}},
                   "controller": {"port": controller}, "worker": {"controllers": {"type": "STATIC", "static": {"endpoints": [{"host": "localhost", "port": controller}]}}}},
        "endpoints": {"all": {"port": management}},
    }
    started = False
    try:
        missing = missing_components(root)
        if missing and not args.allow_partial:
            summary["uncoveredComponents"] = sorted(missing)
            raise ValueError("Some implemented components have no e2e flow: " + ", ".join(sorted(missing)))
        subprocess.run(["docker", "run", "--detach", "--rm", "--name", name, "--memory", "3g", "--network", "host",
                        "--env", "KESTRA_CONFIGURATION=" + yaml.safe_dump(configuration), "--env", "JAVA_OPTS=-Xms256m -Xmx1536m",
                        "--volume", f"{jar}:/app/plugins/plugin-datadog.jar:ro", args.image,
                        "server", "standalone", "--worker-thread=4", "--no-tutorials", f"--port={port}"], check=True, capture_output=True, text=True)
        started = True
        client.wait()
        for origin, text in examples(root):
            result = client.request("POST", "/flows/validate", text)
            summary["validations"].append({"source": origin, "result": result})
            if not isinstance(result, list) or any(row.get("constraints") for row in result):
                summary["errors"].append(f"Example validation failed: {origin}")
        covered = set()
        trigger_flows = []
        for file in sorted((root / "e2e/flows").glob("*.yaml")):
            text = file.read_text().replace("__MOCK_URL__", mock.url)
            flow = yaml.safe_load(text)
            covered.update(flow_components(flow))
            result = client.request("POST", "/flows/validate", text)
            summary["validations"].append({"source": str(file.relative_to(root)), "result": result})
            if any(row.get("constraints") for row in result):
                summary["errors"].append(f"E2e flow validation failed: {file.name}"); continue
            client.request("POST", "/flows", text)
            if flow.get("triggers"):
                trigger_flows.append(flow)
                continue
            boundary = "e2e-empty-inputs"
            created = client.request("POST", f"/executions/{flow['namespace']}/{flow['id']}",
                                     f"--{boundary}--\r\n", f"multipart/form-data; boundary={boundary}")
            execution = client.wait_execution(created["id"])
            (evidence / f"{file.stem}-execution.json").write_text(json.dumps(execution, indent=2))
            (evidence / f"{file.stem}-logs.json").write_text(json.dumps(client.request("GET", "/logs/" + created["id"]), indent=2))
            summary["executions"].append({"flow": flow["id"], "id": created["id"], "state": execution["state"]["current"]})
            if execution["state"]["current"] != "SUCCESS":
                summary["errors"].append(f"Execution failed: {flow['id']}")
        exercise_triggers(client, trigger_flows, mock, evidence, summary)
        for request in mock.requests:
            if not request["path"].startswith("/scenarios/"):
                continue
            scenario = request["path"].split("/")[2]
            if scenario.endswith("-dry") or scenario.endswith("-plan") and request["method"] != "GET":
                summary["errors"].append("Preview safety violation: " + request["method"] + " " + request["path"])
        summary["coveredComponents"] = sorted(covered)
        summary["uncoveredComponents"] = sorted(components(root) - covered)
        if summary["uncoveredComponents"] and not args.allow_partial:
            summary["errors"].append("Some implemented components have no e2e flow")
    except Exception as error:
        summary["errors"].append(str(error))
    finally:
        if started:
            logs = subprocess.run(["docker", "logs", name], capture_output=True, text=True)
            (evidence / "kestra.log").write_text(logs.stdout + logs.stderr)
            subprocess.run(["docker", "stop", "--time", "10", name], check=False, capture_output=True)
        mock.stop()
        (evidence / "mock-requests.json").write_text(json.dumps(mock.requests, indent=2))
        summary["finished"] = now()
        summary["requestCount"] = len(mock.requests)
        (evidence / "summary.json").write_text(json.dumps(summary, indent=2))
    print(json.dumps({"executions": len(summary["executions"]), "validations": len(summary["validations"]), "requests": summary["requestCount"], "errors": summary["errors"]}, indent=2))
    return 1 if summary["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
