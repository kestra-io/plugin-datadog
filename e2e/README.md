# Real Kestra verification

`run.py` loads a supplied plugin jar into the local `kestra/kestra:v2.0.6` image. It uses H2, local storage, basic auth and a local mock Datadog server. It deploys the flows under `e2e/flows`, waits for execution results and saves JSON evidence and logs. It also asks `flows/validate` to validate full examples extracted from Java annotations and Markdown docs.

Build a jar with `./gradlew shadowJar` through the shared Gradle slot. Run:

```bash
python3 -B e2e/run.py \
  --jar build/libs/plugin-datadog-1.0.0-SNAPSHOT.jar \
  --artifact-commit "$(git rev-parse HEAD)" \
  --evidence "$HQ/agents/d08-generic-contract-qa/e2e" \
  --include-api
```

The runner records the artifact commit and jar digest. Use `--include-api` only when the jar includes the generic tasks. It fails if an implemented task has no end-to-end flow unless `--allow-partial` is set. Partial runs list the uncovered components explicitly. Add a flow and mock fixtures for each newly integrated area before claiming all-area coverage.

The mock records request methods, paths, queries and bodies. It records only whether connection headers were present. It never contacts Datadog. The mock rejects unknown endpoints. Execution output and server logs stay in the evidence directory, outside version control. The runner stops its own container and mock server in a finally block.

The runner uses Docker host networking so the container reaches the local mock. Ports are chosen from free loopback ports. Dummy credentials are fixed only for this disposable local instance. Live Datadog behavior and production permissions are not verified.
