#!/usr/bin/env python3
"""Vendor schema closures for typed Datadog operations. Full specs stay outside git."""
import argparse
import json
from pathlib import Path

from spec_tools import dereference, load, operations, slug, typed_operations

SCALARS = {"type", "format", "enum", "nullable", "required", "readOnly", "writeOnly", "minimum", "maximum",
           "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minLength", "maxLength", "pattern",
           "minItems", "maxItems", "uniqueItems", "minProperties", "maxProperties", "default"}


class Bundle:
    def __init__(self, docs):
        self.docs = docs
        self.schemas = {}

    def schema(self, api, raw):
        if isinstance(raw, bool):
            return raw
        result = {k: v for k, v in raw.items() if k in SCALARS}
        if "$ref" in raw:
            ref = raw["$ref"]
            if not ref.startswith("#/components/schemas/"):
                return self.schema(api, dereference(self.docs[api], raw))
            name = ref.rsplit("/", 1)[1]
            key = api + "_" + name
            result["$ref"] = "#/components/schemas/" + key
            if key not in self.schemas:
                self.schemas[key] = {}
                self.schemas[key] = self.schema(api, self.docs[api]["components"]["schemas"][name])
        if "properties" in raw:
            result["properties"] = {name: self.schema(api, value) for name, value in raw["properties"].items()}
        for keyword in ("items", "additionalProperties", "not"):
            if keyword in raw:
                result[keyword] = self.schema(api, raw[keyword])
        for keyword in ("allOf", "anyOf", "oneOf"):
            if keyword in raw:
                result[keyword] = [self.schema(api, item) for item in raw[keyword]]
        return result

    def content(self, api, raw):
        return {media: {"schema": self.schema(api, definition.get("schema", {}))} for media, definition in raw.items()}

    def operation(self, row):
        api, op, item = row["api"], row["op"], row["item"]
        doc = self.docs[api]
        parameters = {}
        for raw in item.get("parameters", []) + op.get("parameters", []):
            p = dereference(doc, raw)
            parameters[(p["in"], p["name"])] = {"name": p["name"], "in": p["in"], "required": p.get("required", False),
                "explode": p.get("explode", p.get("style", "form" if p["in"] == "query" else "simple") == "form"),
                "schema": self.schema(api, p.get("schema", {}))}
        body = dereference(doc, op.get("requestBody", {}))
        responses = {}
        for code, raw in op["responses"].items():
            if str(code).startswith("2"):
                responses[str(code)] = {"content": self.content(api, dereference(doc, raw).get("content", {}))}
        return {"operationId": row["operationId"], "api": api, "path": row["path"], "method": row["method"],
                "parameters": list(parameters.values()), "request": {"required": body.get("required", False), "content": self.content(api, body.get("content", {}))},
                "responses": responses}


def generate(docs, sources, rows, selected):
    groups = {}
    for key in sorted(selected):
        row = rows[key]
        tag = slug(row["tag"])
        bundle, entries = groups.setdefault(tag, (Bundle(docs), {}))
        entries[key] = bundle.operation(row)
    return {tag: {"formatVersion": 1, "sources": sources, "operations": entries, "components": {"schemas": bundle.schemas}}
            for tag, (bundle, entries) in sorted(groups.items())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("v1", type=Path); parser.add_argument("v2", type=Path)
    parser.add_argument("--source", type=Path, default=Path("src/main/java/io/kestra/plugin/datadog"))
    parser.add_argument("--output", type=Path, default=Path("src/test/resources/openapi"))
    parser.add_argument("--operations", nargs="*", help="Explicit qualified IDs instead of source discovery")
    args = parser.parse_args()
    docs, sources = load([args.v1, args.v2]); rows = operations(docs)
    selected, _, unresolved = typed_operations(args.source, rows)
    if unresolved:
        print("Tasks without operationId tags:", ", ".join(unresolved))
    bundles = generate(docs, sources, rows, args.operations if args.operations is not None else selected)
    args.output.mkdir(parents=True, exist_ok=True)
    for tag, bundle in bundles.items():
        (args.output / (tag + ".json")).write_text(json.dumps(bundle, sort_keys=True, separators=(",", ":")) + "\n")
    index = {key: tag + ".json" for tag, bundle in bundles.items() for key in bundle["operations"]}
    (args.output / "index.json").write_text(json.dumps(index, sort_keys=True, separators=(",", ":")) + "\n")
    print(f"Vendored {len(index)} operations in {len(bundles)} schema bundles")


if __name__ == "__main__":
    main()
