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
    parser.add_argument("--include-api", action="store_true")
    parser.add_argument("--allow-partial", action="store_true", help="Report tasks lacking e2e flows without rejecting the run")
    parser.add_argument("--artifact-commit", required=True, help="Commit from which the jar was built")
    args = parser.parse_args()
    root = args.source.resolve()
    jar = args.jar.resolve()
    evidence = args.evidence.resolve(); evidence.mkdir(parents=True, exist_ok=True)
    summary = {"image": args.image, "artifactCommit": args.artifact_commit, "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
               "started": now(), "executions": [], "validations": [], "errors": []}
    port, management, controller = free_port(), free_port(), free_port()
    name = "dd-d08-generic-contract-qa-e2e-" + str(port)
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
        monitor_flow = None
        for file in sorted((root / "e2e/flows").glob("*.yaml")):
            if file.stem == "api" and not args.include_api:
                continue
            text = file.read_text().replace("__MOCK_URL__", mock.url)
            flow = yaml.safe_load(text)
            covered.update(re.findall(r"type:\s*(io\.kestra\.plugin\.datadog\.[A-Za-z.]+)", text))
            result = client.request("POST", "/flows/validate", text)
            summary["validations"].append({"source": str(file.relative_to(root)), "result": result})
            if any(row.get("constraints") for row in result):
                summary["errors"].append(f"E2e flow validation failed: {file.name}"); continue
            client.request("POST", "/flows", text)
            boundary = "e2e-empty-inputs"
            created = client.request("POST", f"/executions/{flow['namespace']}/{flow['id']}",
                                     f"--{boundary}--\r\n", f"multipart/form-data; boundary={boundary}")
            execution = client.wait_execution(created["id"])
            (evidence / f"{file.stem}-execution.json").write_text(json.dumps(execution, indent=2))
            (evidence / f"{file.stem}-logs.json").write_text(json.dumps(client.request("GET", "/logs/" + created["id"]), indent=2))
            summary["executions"].append({"flow": flow["id"], "id": created["id"], "state": execution["state"]["current"]})
            if execution["state"]["current"] != "SUCCESS":
                summary["errors"].append(f"Execution failed: {flow['id']}")
            if flow.get("triggers"):
                monitor_flow = flow
        if monitor_flow:
            # The initial poll only writes a cursor. A second monitor request proves
            # polling has progressed beyond it (the first request is the manual list).
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline and sum(row["path"] == "/api/v1/monitor" for row in mock.requests) < 2:
                time.sleep(0.5)
            mock.modified = now()
            deadline = time.monotonic() + 45
            triggered = None
            while time.monotonic() < deadline:
                result = client.request("GET", "/executions?" + urlencode({"namespace": monitor_flow["namespace"], "flowId": monitor_flow["id"], "size": 50}))
                triggered = next((entry for entry in result.get("results", []) if entry.get("trigger")), None)
                if triggered:
                    break
                time.sleep(1)
            if triggered:
                execution = client.wait_execution(triggered["id"])
                (evidence / "trigger-execution.json").write_text(json.dumps(execution, indent=2))
                (evidence / "trigger-logs.json").write_text(json.dumps(client.request("GET", "/logs/" + triggered["id"]), indent=2))
                summary["executions"].append({"flow": monitor_flow["id"], "id": triggered["id"], "triggered": True, "state": execution["state"]["current"]})
                if execution["state"]["current"] != "SUCCESS":
                    summary["errors"].append("Triggered execution failed")
            else:
                summary["errors"].append("StateChange did not create an execution")
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
