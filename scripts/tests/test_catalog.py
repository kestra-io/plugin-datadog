import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("catalog", Path(__file__).parents[1] / "gen-catalog.py")
catalog = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(catalog)

FIXTURE = """
openapi: 3.0.0
security: [{apiKeyAuth: [], appKeyAuth: []}]
servers: [{url: 'https://api.{site}', variables: {site: {default: datadoghq.com}}}]
paths:
  /api/test/{id}:
    parameters: [{name: id, in: path, required: true, schema: {type: integer}}]
    get:
      operationId: ReadObject
      tags: [Objects]
      deprecated: true
      parameters:
        - {$ref: '#/components/parameters/filter'}
        - {name: labels, in: query, schema: {type: array, items: {type: string}}, explode: false}
      responses: {'200': {description: OK}}
    post:
      operationId: CreateObject
      security: [{apiKeyAuth: []}]
      servers: [{url: 'https://intake.{site}'}]
      requestBody: {$ref: '#/components/requestBodies/input'}
      responses: {'204': {description: OK}}
components:
  parameters:
    filter:
      name: filter
      in: query
      required: true
      schema: {$ref: '#/components/schemas/Filter'}
  schemas:
    Filter: {type: string, enum: [active, inactive]}
  requestBodies:
    input: {required: true, content: {application/json: {schema: {type: object}}}}
"""


class CatalogTest(unittest.TestCase):
    def test_fixture_inheritance_references_and_determinism(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = [Path(directory) / "v1.yaml", Path(directory) / "v2.yaml"]
            paths[0].write_text(FIXTURE)
            paths[1].write_text("paths: {}")
            first = catalog.generate(paths)
            self.assertEqual(catalog.encode(first), catalog.encode(catalog.generate(paths)))
            self.assertEqual(2, len(first["operations"]))
            self.assertEqual(64, len(first["sources"][0]["sha256"]))
            create, read = first["operations"]
            self.assertEqual("CreateObject", create["operationId"])
            self.assertTrue(create["bodyRequired"])
            self.assertEqual(["application/json"], create["contentTypes"])
            self.assertEqual([{"apiKeyAuth": []}], create["security"])
            self.assertEqual("https://intake.{site}", create["servers"][0]["url"])
            self.assertTrue(read["deprecated"])
            self.assertEqual("integer", read["parameters"][0]["type"])
            self.assertEqual(["active", "inactive"], read["parameters"][1]["enum"])
            self.assertFalse(read["parameters"][2]["explode"])
            self.assertEqual(first, json.loads(catalog.encode(first)))

    def test_external_reference_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Unsupported reference"):
            catalog.resolve({}, {"$ref": "https://invalid.test/schema"})

    def test_operation_parameter_replaces_path_parameter(self):
        doc = {"paths": {"/test": {"parameters": [{"name": "x", "in": "query", "schema": {"type": "string"}}],
            "get": {"operationId": "Read", "parameters": [{"name": "x", "in": "query", "required": True, "schema": {"type": "integer"}}]}}}}
        parameters = list(catalog.operations(doc, "v1"))[0]["parameters"]
        self.assertEqual(1, len(parameters))
        self.assertEqual("integer", parameters[0]["type"])
        self.assertTrue(parameters[0]["required"])


if __name__ == "__main__":
    unittest.main()
