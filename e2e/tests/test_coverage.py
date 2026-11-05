import sys
import tempfile
import unittest
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).parents[1]))
from generate_flows import FlowDumper
from run import components, flow_components, missing_components


class CoverageTest(unittest.TestCase):
    def test_comments_and_plain_strings_cannot_claim_task_coverage(self):
        text = """
tasks:
  - id: log
    type: io.kestra.plugin.core.log.Log
    message: 'type: io.kestra.plugin.datadog.fake.Missing'
# type: io.kestra.plugin.datadog.fake.Missing
"""
        self.assertEqual(set(), flow_components(yaml.safe_load(text)))

    def test_nested_executable_nodes_are_counted(self):
        actual = "io.kestra.plugin.datadog.events.Create"
        self.assertEqual({actual}, flow_components({"tasks": [{"type": "flow.If", "then": [{"type": actual}]}]}))

    def test_missing_flow_is_a_strict_inventory_gap(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "src/main/java/io/kestra/plugin/datadog/events"
            source.mkdir(parents=True)
            (source / "Create.java").write_text("@Plugin(examples={}) public class Create {}")
            flows = root / "e2e/flows"
            flows.mkdir(parents=True)
            name = "io.kestra.plugin.datadog.events.Create"
            self.assertEqual({name}, components(root))
            self.assertEqual({name}, missing_components(root))
            (flows / "events.yaml").write_text("tasks:\n  - type: " + name)
            self.assertEqual(set(), missing_components(root))

    def test_shared_dry_run_inputs_emit_no_yaml_aliases(self):
        definition = {"tags": ["env:e2e"]}
        text = yaml.dump({"tasks": [{"definition": definition}, {"definition": definition}]}, Dumper=FlowDumper)
        self.assertNotIn("&id", text)
        self.assertNotIn("*id", text)
        self.assertEqual(definition, yaml.safe_load(text)["tasks"][1]["definition"])

    def test_integrated_flows_cover_every_component(self):
        self.assertEqual(set(), missing_components(Path(__file__).resolve().parents[2]))


if __name__ == "__main__":
    unittest.main()
