#!/usr/bin/env python3
"""Assemble bounded root docs and the README task table from area docs and source."""
import argparse
import re
from pathlib import Path

START = "<!-- datadog-tasks:start -->"
END = "<!-- datadog-tasks:end -->"


def task_rows(source):
    rows = []
    for file in sorted(Path(source).rglob("*.java")):
        text = file.read_text()
        if not re.search(r"(?:@Plugin\s*\(|implements\s+(?:RunnableTask|PollingTriggerInterface))", text) or re.search(r"public\s+abstract\s+class", text):
            continue
        task = str(file.relative_to(source).with_suffix("")).replace("/", ".")
        title = re.search(r"@Schema\(\s*title\s*=\s*\"([^\"]+)\"", text)
        rows.append((task, title.group(1) if title else task))
    return rows


def assemble(areas, rows, max_bytes=40000):
    table = "| Component | Purpose |\n|---|---|\n" + "\n".join(f"| `{task}` | {title.replace('|', '/')} |" for task, title in rows)
    sections = ["# How to use the Datadog plugin", "Use Kestra connection properties for Datadog keys and the regional site. See each task page for its full flow examples.", "## Components\n\n" + table]
    for path in sorted(Path(areas).glob("*.md")):
        text = path.read_text().strip()
        text = re.sub(r"```.*?```", "", text, flags=re.S)
        # Keep heading summaries. Full area docs retain details and examples.
        summaries = []
        for section in re.split(r"(?m)(?=^#{1,3} )", text):
            paragraphs = [p.strip() for p in section.split("\n\n") if p.strip()]
            if paragraphs:
                selected = paragraphs[:2] if paragraphs[0].startswith("#") else paragraphs[:1]
                summaries.extend(selected)
        overview = "\n\n".join(summaries)
        overview = re.sub(r"(?m)^(#{1,3}) ", r"#\1 ", overview)
        sections.append(overview)
    sections.append("## Verification\n\nTests use a local mock and the pinned Datadog OpenAPI schemas. No test has run against a live Datadog organization.")
    root = "\n\n".join(sections) + "\n"
    if len(root.encode()) > max_bytes:
        raise ValueError(f"Assembled root doc exceeds {max_bytes} bytes; shorten area summaries")
    return root, table


def update_readme(readme, table):
    block = START + "\n" + table + "\n" + END
    if START in readme or END in readme:
        if readme.count(START) != 1 or readme.count(END) != 1 or readme.index(START) > readme.index(END):
            raise ValueError("README task table markers are malformed")
        return re.sub(re.escape(START) + ".*?" + re.escape(END), lambda _: block, readme, flags=re.S)
    if "## What\n" in readme:
        return re.sub(r"(?ms)^## What\n.*?(?=^## |\Z)", "## What\n\n" + block + "\n\n", readme, count=1)
    return readme.rstrip() + "\n\n## Components\n\n" + block + "\n"


def main():
    raise SystemExit(
        "assemble-docs.py no longer writes the README or the root documentation page: both are written by hand as a story, not generated from the area parts.\n"
        "Run scripts/gen-tasks.py to regenerate docs/TASKS.md, the grouped index of every task and trigger."
    )


if __name__ == "__main__":
    main()
