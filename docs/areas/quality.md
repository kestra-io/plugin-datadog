# API contracts and coverage

The plugin tests use a pinned Datadog OpenAPI description and a local mock server. The generic catalog records the source digests. No tests use a live Datadog organization.

`docs/COVERAGE.md` lists every version-qualified operation and maps typed tasks through operationId tags in Javadoc. `docs/CONTRACT-TESTS.md` explains request and response assertions. `scripts/gen-contracts.py` vendors only the schema closures used by typed tasks.

`scripts/assemble-docs.py` builds a bounded root overview and a README component table from area summaries and implemented classes. The orchestrator supplies the root document and README paths. The script updates only the marked task table after its first run. It fails if the root summary exceeds its byte limit.
