"""Local Datadog fixture server. Never records credentials or contacts Datadog."""
import json
import threading
from collections import Counter
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


def now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class MockDatadog:
    def __init__(self):
        self.requests = []
        self.modified = now()
        self.downtimes = {}
        from scenarios import build
        self.scenarios, _ = build()
        self.scenario_calls = Counter()
        self.trigger_active = set()
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                self.handle_request()

            do_POST = do_PUT = do_PATCH = do_DELETE = do_GET

            def handle_request(self):
                if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
                    chunks = []
                    while True:
                        size = int(self.rfile.readline().split(b";", 1)[0], 16)
                        if size == 0:
                            self.rfile.readline(); break
                        chunks.append(self.rfile.read(size)); self.rfile.read(2)
                    raw = b"".join(chunks)
                else:
                    raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                path = urlsplit(self.path).path
                try:
                    body = json.loads(raw) if raw else None
                except ValueError:
                    body = None
                owner.requests.append({"method": self.command, "path": path, "query": urlsplit(self.path).query,
                                       "body": body, "apiKeyPresent": bool(self.headers.get("DD-API-KEY")),
                                       "appKeyPresent": bool(self.headers.get("DD-APPLICATION-KEY"))})
                status, answer = owner.answer(self.command, path, body)
                data = json.dumps(answer).encode() if answer is not None else b""
                self.send_response(status)
                media = "application/json"
                if path.startswith("/scenarios/"):
                    name = path.split("/")[2].removesuffix("-dry").removesuffix("-plan")
                    endpoint = "/" + "/".join(path.split("/")[3:])
                    matches = [row for row in owner.scenarios.get(name, {}).get("replies", []) if row["method"] == self.command and row["path"] == endpoint]
                    if matches:
                        media = matches[0]["contentType"]
                self.send_header("Content-Type", media)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                if data:
                    self.wfile.write(data)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    @property
    def url(self):
        return f"http://127.0.0.1:{self.server.server_port}"

    def monitor(self):
        return {"id": 12345, "name": "e2e monitor", "type": "query alert", "query": "avg(last_5m):avg:system.cpu.user{*} > 90",
                "overall_state": "Alert", "overall_state_modified": self.modified, "tags": ["env:e2e"]}

    def answer(self, method, path, body):
        if path.startswith("/scenarios/"):
            _, _, name, *parts = path.split("/")
            task = name.removesuffix("-dry").removesuffix("-plan")
            if name.endswith("-dry") or name.endswith("-plan") and method != "GET":
                return 400, {"errors": ["Preview request violated the safety contract"]}
            endpoint = "/" + "/".join(parts)
            fixtures = [row for row in self.scenarios.get(task, {}).get("replies", []) if row["method"] == method and row["path"] == endpoint]
            if fixtures:
                key = (name, method, endpoint)
                fixture = fixtures[min(self.scenario_calls[key], len(fixtures) - 1)]
                self.scenario_calls[key] += 1
                return fixture["status"], fixture["body"]
            return 404, {"errors": ["No fixture for " + task + " " + method + " " + endpoint]}
        if path.startswith("/triggers/"):
            _, _, name, *parts = path.split("/")
            active = name in self.trigger_active
            if name == "monitors.StateChange":
                return 200, [self.monitor()]
            if name == "logs.CountThreshold":
                return 200, {"data": {"buckets": [{"by": {}, "computes": {"c0": 20 if active else 0}}]}}
            if name == "metrics.Threshold":
                return 200, {"data": {"type": "scalar_response", "attributes": {"columns": [{"type": "number", "name": "a", "values": [90 if active else 0]}]}}}
            if name == "slos.BudgetLow":
                return 200, {"data": {"from_ts": 100, "to_ts": 200, "type": "metric", "overall": {"sli_value": 99.8 if active else 100, "span_precision": 3, "errors": []}}, "errors": []}
        if (method, path) == ("POST", "/api/v1/events"):
            return 202, {"status": "ok", "event": {"id": 123, "id_str": "123", "title": body["title"], "text": body["text"]}}
        if (method, path) == ("POST", "/api/v2/series"):
            return 202, {"errors": []}
        if (method, path) == ("GET", "/api/v1/query"):
            return 200, {"status": "ok", "series": [{"metric": "e2e.value", "pointlist": [[1767225600000, 1.0]]}]}
        if (method, path) == ("GET", "/api/v1/monitor"):
            return 200, [self.monitor()]
        if (method, path) == ("GET", "/api/v1/monitor/12345"):
            return 200, self.monitor()
        if (method, path) == ("POST", "/api/v2/downtime"):
            result = {"data": {"id": "e2e-downtime", "type": "downtime", "attributes": {**body["data"]["attributes"], "status": "active"}}}
            self.downtimes["e2e-downtime"] = result
            return 200, result
        if method == "DELETE" and path == "/api/v2/downtime/e2e-downtime":
            self.downtimes.pop("e2e-downtime", None)
            return 204, None
        if (method, path) == ("POST", "/api/v2/logs"):
            return 202, {}
        if (method, path) == ("POST", "/api/v2/logs/events/search"):
            return 200, {"data": [{"id": "e2e-log", "type": "log", "attributes": {"message": "e2e line", "status": "info", "service": "e2e", "timestamp": now()}}]}
        if (method, path) == ("GET", "/api/v2/roles"):
            return 200, {"data": [{"id": "e2e-role", "type": "roles", "attributes": {"name": "e2e"}}]}
        return 404, {"errors": ["No e2e fixture for this operation"]}

    def start(self):
        self.thread.start()
        return self

    def stop(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(timeout=5)
