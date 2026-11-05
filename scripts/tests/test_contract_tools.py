import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).parents[1]
sys.path.insert(0, str(SCRIPTS))
from spec_tools import operations, typed_operations


def module(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), SCRIPTS / (name + ".py"))
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result)
    return result


contracts = module("gen-contracts")
coverage = module("coverage")
assembly = module("assemble-docs")


class ToolsTest(unittest.TestCase):
    def test_schema_closure_preserves_recursion_and_property_names(self):
        doc = {"components": {"schemas": {"Node": {"type": "object", "description": "not validation",
            "properties": {"description": {"type": "string"}, "next": {"$ref": "#/components/schemas/Node"}}}}}}
        bundle = contracts.Bundle({"v1": doc})
        schema = bundle.schema("v1", {"$ref": "#/components/schemas/Node"})
        self.assertEqual("#/components/schemas/v1_Node", schema["$ref"])
        self.assertIn("description", bundle.schemas["v1_Node"]["properties"])
        self.assertNotIn("description", bundle.schemas["v1_Node"])
        self.assertEqual(schema, bundle.schemas["v1_Node"]["properties"]["next"])

    def test_mapping_and_totals_come_from_javadoc_not_examples(self):
        docs = {"v1": {"paths": {"/objects": {"get": {"operationId": "ListObjects", "tags": ["Objects"]}}}}}
        rows = operations(docs)
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp)
            (source / "Read.java").write_text("/** operationId: v1:ListObjects */ class Read implements RunnableTask<X> {}")
            (source / "Other.java").write_text('class Other implements RunnableTask<X> { String x = "operationId: ListObjects"; }')
            typed, legacy, missing = typed_operations(source, rows)
            self.assertEqual({"v1:ListObjects": ["Read"]}, typed)
            self.assertEqual(["Other"], missing)
            text = coverage.report(rows, typed, legacy, missing, {}, [])
            self.assertIn("Operations mapped to typed tasks: **1**", text)
            self.assertIn("Generic-only operations: **0**", text)

    def test_docs_assembly_is_bounded_and_readme_update_is_idempotent(self):
        with tempfile.TemporaryDirectory() as temp:
            areas = Path(temp)
            (areas / "objects.md").write_text("# Objects\n\nRead objects.\n\n## objects.Read\n\nFetch one object.\n\n```yaml\nid: example\n```\n")
            root, table = assembly.assemble(areas, [("objects.Read", "Read an object")])
            self.assertIn("Fetch one object.", root)
            self.assertNotIn("id: example", root)
            readme = "# Plugin\n\n## What\n\nOld list\n\n## License\n\nApache\n"
            once = assembly.update_readme(readme, table)
            self.assertEqual(once, assembly.update_readme(once, table))
            self.assertIn("## License\n\nApache", once)
            self.assertNotIn("Old list", once)
            with self.assertRaises(ValueError):
                assembly.assemble(areas, [], 5)
            with self.assertRaises(ValueError):
                assembly.update_readme(assembly.START, table)

    def test_contract_adoption_requires_assertions_and_excludes_helper_self_tests(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp)
            (source / "WriteTest.java").write_text('class WriteTest { void test() { OpenApiContract.of("v2:Write").assertRequest(request); } }')
            (source / "UnusedTest.java").write_text('import OpenApiContract; // OpenApiContract.assertRequests(requests);')
            (source / "contract").mkdir()
            (source / "contract/HelperTest.java").write_text('OpenApiContract.assertRequests(requests);')
            self.assertEqual(["WriteTest"], coverage.contract_adoption(source))


if __name__ == "__main__":
    unittest.main()
