"""Shared readers for the offline Datadog OpenAPI descriptions."""
import hashlib
import re
from pathlib import Path

import yaml

METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}
LEGACY = {
    "events.Create": ["v1:CreateEvent"], "metrics.Submit": ["v2:SubmitMetrics"],
    "metrics.Query": ["v1:QueryMetrics"], "monitors.List": ["v1:ListMonitors"],
    "monitors.StateChange": ["v1:ListMonitors"], "downtimes.Create": ["v2:CreateDowntime"],
    "downtimes.Cancel": ["v2:CancelDowntime"], "logs.Send": ["v2:SubmitLog"], "logs.Search": ["v2:ListLogs"],
}


def load(paths):
    docs, sources = {}, []
    for api, path in zip(("v1", "v2"), paths):
        raw = Path(path).read_bytes()
        docs[api] = yaml.load(raw, Loader=yaml.CSafeLoader)
        sources.append({"api": api, "sha256": hashlib.sha256(raw).hexdigest().upper()})
    return docs, sources


def dereference(doc, value):
    seen = set()
    while isinstance(value, dict) and "$ref" in value:
        ref = value["$ref"]
        if not ref.startswith("#/") or ref in seen:
            raise ValueError(f"Unsupported reference: {ref}")
        seen.add(ref)
        target = doc
        for key in ref[2:].split("/"):
            target = target[key.replace("~1", "/").replace("~0", "~")]
        value = {**target, **{k: v for k, v in value.items() if k != "$ref"}}
    return value


def operations(docs):
    rows = {}
    for api, doc in docs.items():
        for path, raw in doc.get("paths", {}).items():
            item = dereference(doc, raw)
            for method, op in item.items():
                if method in METHODS:
                    key = api + ":" + op["operationId"]
                    if key in rows:
                        raise ValueError(f"Duplicate operation {key}")
                    rows[key] = {"api": api, "operationId": op["operationId"], "path": path,
                                 "method": method.upper(), "tag": op.get("tags", ["Other"])[0], "op": op, "item": item}
    return rows


def typed_operations(source, rows):
    """Read operationId tags in Javadoc. Keep explicit mappings for pre-tag base tasks."""
    result, legacy, unresolved = {}, [], []
    by_id = {}
    for key, row in rows.items():
        by_id.setdefault(row["operationId"], []).append(key)
    for file in sorted(Path(source).rglob("*.java")):
        text = file.read_text()
        if not re.search(r"(?:@Plugin\s*\(|implements\s+RunnableTask|implements\s+PollingTriggerInterface)", text) or re.search(r"public\s+abstract\s+class", text):
            continue
        task = str(file.relative_to(source).with_suffix("")).replace("/", ".")
        comments = [c for c in re.findall(r"/\*\*.*?\*/", text, re.S) if re.search(r"operationIds?", c, re.I)]
        found = set()
        for comment in comments:
            for key, row in rows.items():
                oid = row["operationId"]
                qualified = bool(re.search(r"(?<![\w:])" + re.escape(key) + r"\b", comment))
                if not re.search(r"(?<![\w:])" + re.escape(oid) + r"\b", comment) and not qualified:
                    continue
                if len(by_id[oid]) == 1 or qualified:
                    found.add(key)
                elif row["path"] in text or (f"/api/{row['api']}/" in text and sum(f"/api/{v}/" in text for v in ("v1", "v2")) == 1):
                    found.add(key)
        if not found and task in LEGACY:
            found.update(LEGACY[task]); legacy.append(task)
        if not found and not task.startswith("api."):
            unresolved.append(task)
        for key in found:
            if key not in rows:
                raise ValueError(f"Unknown operation {key} in {task}")
            result.setdefault(key, []).append(task)
    return result, legacy, unresolved


def slug(tag):
    return re.sub(r"[^a-z0-9]+", "-", tag.lower()).strip("-")
