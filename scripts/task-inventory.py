#!/usr/bin/env python3
"""Print source-derived component examples and inherited properties for e2e review."""
import argparse
import json
import re
import textwrap
from pathlib import Path

import yaml


def inventory(source):
    files = {str(p.relative_to(source).with_suffix("")).replace("/", "."): p for p in source.rglob("*.java")}
    result = {}
    for name, file in sorted(files.items()):
        text = file.read_text()
        if not re.search(r"@Plugin\s*\(", text) or "public abstract class" in text:
            continue
        match = re.search(r'@Example\(.*?full\s*=\s*true.*?code\s*=\s*"""(.*?)"""', text, re.S)
        flow = yaml.safe_load(textwrap.dedent(match.group(1))) if match else {}
        properties = []
        seen = set()
        current = file
        while current and current not in seen:
            seen.add(current)
            content = current.read_text()
            properties += re.findall(r"private\s+(?:final\s+)?(?:Property<([^;]+?)>|(Duration|Map<[^;]+?>|String|boolean))\s+(\w+)(?=\s*[;=])", content)
            parent = re.search(r"public\s+(?:abstract\s+)?class\s+\w+\s+extends\s+(\w+)", content)
            if not parent:
                break
            local = current.with_name(parent.group(1) + ".java")
            imported = re.search(r"import io\.kestra\.plugin\.datadog\.([\w.]*" + parent.group(1) + r");", content)
            current = local if local.exists() else files.get(imported.group(1) if imported else parent.group(1))
        examples = flow.get("tasks", []) + flow.get("triggers", [])
        example = next((task for task in examples if task.get("type") == "io.kestra.plugin.datadog." + name), {})
        result[name] = {"trigger": "PollingTriggerInterface" in text, "properties": sorted(set(p[2] for p in properties)),
                        "example": {k: v for k, v in example.items() if k not in {"apiKey", "appKey", "type", "id"}}}
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path("src/main/java/io/kestra/plugin/datadog"))
    parser.add_argument("--area", nargs="*")
    args = parser.parse_args()
    tasks = inventory(args.source)
    for name, row in tasks.items():
        if not args.area or name.split(".")[0] in args.area:
            print(name, json.dumps(row, default=str))
    print(f"{len(tasks)} components; {sum(row['trigger'] for row in tasks.values())} triggers")
