#!/usr/bin/env python3
"""Generate a deterministic Datadog catalog. Requires the spec tool's PyYAML."""
import argparse
import gzip
import hashlib
import json
from collections import Counter
from pathlib import Path

import yaml

METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}


def resolve(doc, value):
    seen = set()
    while isinstance(value, dict) and "$ref" in value:
        ref = value["$ref"]
        if not ref.startswith("#/") or ref in seen:
            raise ValueError(f"Unsupported reference: {ref}")
        seen.add(ref)
        target = doc
        for part in ref[2:].split("/"):
            target = target[part.replace("~1", "/").replace("~0", "~")]
        value = {**target, **{k: v for k, v in value.items() if k != "$ref"}}
    return value


def primitive(doc, raw):
    schema = resolve(doc, raw)
    result = {k: schema[k] for k in ("type", "format", "enum", "default", "minimum", "maximum", "minLength", "maxLength", "pattern", "minItems", "maxItems") if k in schema}
    for part in schema.get("allOf", []):
        result.update(primitive(doc, part))
    if "items" in schema:
        result["items"] = primitive(doc, schema["items"])
    result.setdefault("type", "string")
    return result


def operations(doc, api):
    for path, raw_item in doc.get("paths", {}).items():
        item = resolve(doc, raw_item)
        for method, op in item.items():
            if method not in METHODS:
                continue
            params = {}
            for raw in item.get("parameters", []) + op.get("parameters", []):
                p = resolve(doc, raw)
                location = p["in"]
                params[(location, p["name"])] = {
                    "name": p["name"], "in": location, "required": p.get("required", False),
                    **primitive(doc, p.get("schema", {})),
                    "style": p.get("style", "form" if location in ("query", "cookie") else "simple"),
                    "explode": p.get("explode", location in ("query", "cookie")),
                }
            body = resolve(doc, op.get("requestBody", {}))
            yield {
                "operationId": op["operationId"], "api": api,
                "tag": (op.get("tags") or ["Other"])[0], "method": method.upper(), "path": path,
                "deprecated": op.get("deprecated", False),
                "parameters": sorted(params.values(), key=lambda p: (p["in"], p["name"])),
                "bodyRequired": body.get("required", False),
                "contentTypes": sorted(body.get("content", {})),
                "security": op.get("security", doc.get("security", [])),
                "permissions": op.get("x-permission", {}),
                "servers": op.get("servers", item.get("servers", doc.get("servers", []))),
                "pagination": op.get("x-pagination", {}),
            }


def generate(paths):
    sources, rows = [], []
    for api, path in zip(("v1", "v2"), paths):
        raw = Path(path).read_bytes()
        sources.append({"api": api, "sha256": hashlib.sha256(raw).hexdigest()})
        rows.extend(operations(yaml.load(raw, Loader=yaml.CSafeLoader), api))
    rows.sort(key=lambda o: (o["operationId"], o["api"]))
    return {"formatVersion": 1, "sources": sources, "operations": rows}


def encode(catalog):
    return (json.dumps(catalog, ensure_ascii=True, sort_keys=True, separators=(",", ":")) + "\n").encode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("v1", type=Path)
    parser.add_argument("v2", type=Path)
    parser.add_argument("--output", type=Path, default=Path("src/main/resources/datadog/operations.json"))
    parser.add_argument("--stats", action="store_true")
    parser.add_argument("--check", action="store_true", help="Check the checked-in bytes without writing")
    args = parser.parse_args()
    catalog = generate([args.v1, args.v2])
    raw = encode(catalog)
    target = args.output
    if len(raw) > 400_000 and target.suffix != ".gz":
        target = target.with_suffix(target.suffix + ".gz")
    encoded = gzip.compress(raw, mtime=0) if target.suffix == ".gz" else raw
    if args.check:
        if target.read_bytes() != encoded:
            raise SystemExit(f"Catalog differs: {target}")
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(encoded)
    print(f"{len(catalog['operations'])} operations; {len(raw)} JSON bytes; {target.stat().st_size} stored bytes; {target}")
    if args.stats:
        rows = catalog["operations"]
        print("Duplicate IDs:", {k: v for k, v in Counter(r["operationId"] for r in rows).items() if v > 1})
        print("Content types:", Counter(c for r in rows for c in r["contentTypes"]))
        print("Servers:", Counter(s["url"] for r in rows for s in r["servers"]))
        print("Parameter types/styles:", Counter((p["in"], p["type"], p["style"]) for r in rows for p in r["parameters"]))
        print("Pagination:", json.dumps([{"id": r["operationId"], "pagination": r["pagination"]} for r in rows if r["pagination"]][:12]))


if __name__ == "__main__":
    main()
