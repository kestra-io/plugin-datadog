# Contract tests

The helper checks recorded requests and success fixtures against trimmed copies of the official Datadog OpenAPI schemas. It uses no live account and adds no dependencies.

## Add an operation

Put a version-qualified operation ID in the task Javadoc:

```java
/** Datadog operationId: v2:CreateDowntime. */
```

Generate schema bundles and the coverage report from the offline spec paths:

```bash
python3 -B scripts/gen-contracts.py "$SPEC/v1.yaml" "$SPEC/v2.yaml"
python3 -B scripts/coverage.py "$SPEC/v1.yaml" "$SPEC/v2.yaml"
```

The generator retains local schema references and their complete dependency closure. It drops descriptions and examples without dropping properties named `description` or `example`. Each bundle records source digests. Commit the affected `src/test/resources/openapi/` files and regenerated coverage report.

## Assert both sides

```java
var contract = OpenApiContract.of("v2:CreateDowntime");
String response = "{\"data\":{\"id\":\"dt-1\",\"type\":\"downtime\",\"attributes\":{\"status\":\"active\"}}}";
contract.assertResponse(200, response);
server.reply("POST", "/api/v2/downtime", 200, response);
task.build().run(runContext());
contract.assertRequest(server.only());
```

Import `io.kestra.plugin.datadog.contract.OpenApiContract`. For paging, assert every recorded page request. Keep the normal output assertions too: a valid schema does not prove output mapping or business behavior.

The request assertion checks method, path template, required parameters, unknown query names, array serialization, primitive types and the JSON body. The response assertion checks the declared 2xx status and JSON schema. Use the three-argument overload for another Content-Type. No-content responses must have an empty body.

`MockDatadogServer.Received.path` is already URL decoded. The helper allows decoded slashes inside a placeholder and cannot prove percent-encoding from that record. Keep a transport-level raw-URI assertion for escaping behavior. `OpenApiContract.assertRequests(server.requests, "v2:CreateDowntime", "v2:CancelDowntime")` routes a test's recorded requests to their declared contracts.

Supported schema checks: local references, required fields, objects, arrays, primitives, enums, nullable values, additional properties, allOf, anyOf, oneOf, not, ranges, lengths, item counts, uniqueness, multiples, regex patterns and date formats. Required read-only fields are excluded from request requirements. Required write-only fields are excluded from response requirements. Other string formats are annotations. The helper rejects non-JSON media rather than claiming to validate binary or multipart payloads.

Run `./gradlew test --tests '*YourTest*'`, then the full gate. Add a negative test when introducing a new schema feature. The coverage table distinguishes a vendored schema from an actual contract assertion. Mock correctness is not proof of live API behavior.

## Integrated adoption

`scripts/coverage.py` counts typed-task test classes that call helper assertions. It excludes helper self-tests and generic API tests. This is a count of adopting test classes, not operations exercised or test methods. The initial integrated tree had seven adopting classes.

The high-risk adopters include monitor Apply, dashboard Import, service-definition upsert, incident Create and Update, On-Call Page, key creation and rotation, team membership sync, host mute and downtime creation. Existing output and idempotency assertions remain alongside contract assertions.

`e2e/scenarios.py` owns the shared scenario fixtures. Run `python3 -B e2e/generate_flows.py` after changes. `ScenarioTest` validates success response fixtures and executes the same task configurations with request and output assertions. Real Kestra executes the generated area flows.

## Strict findings

`SafetyInvariantsTest` discovers plugin classes by reflection. It checks reserved property names, destructive selectors, dry-run requests and named credential output fields. Its named-field redaction check does not prove that arbitrary map payloads are safe. Existing area tests cover nested payload redaction.

Known findings are executable assertions of current behavior. Set `DD_STRICT_SAFETY=1` in the test process to turn them into failures. This lets the integrated gate detect new regressions while retaining reproductions of behavior this test-only change cannot repair. See `docs/INTEGRATION-FINDINGS.md`.
