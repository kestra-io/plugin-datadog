# End-to-end verification

The end-to-end runner loads a built plugin jar into a disposable Kestra 2.0.6 container. It uses H2, basic auth and a local mock Datadog server. It records execution JSON, execution logs, server logs and the jar digest outside the repository.

The initial run covers the base events, metrics, monitors, downtimes and logs tasks plus generic API discovery and calls. It also starts an execution through `monitors.StateChange`. The runner submits full Java and Markdown examples to `flows/validate`.

Add a flow and mock fixtures for each integrated area before running the final all-area check. The runner reports components without end-to-end flows and fails unless a partial run is explicitly selected. See `e2e/README.md` for the command and evidence layout.

This verifies real Kestra task loading, rendering, execution, polling and internal storage against a mock. It does not verify a live Datadog organization.
