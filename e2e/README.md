# Real Kestra verification

`run.py` loads a supplied plugin jar into the local `kestra/kestra:v2.0.6` image. It uses H2, local storage, basic auth and a local mock Datadog server. It deploys the flows under `e2e/flows`, waits for execution results and saves JSON evidence and logs. It also asks `flows/validate` to validate full examples extracted from Java annotations and Markdown docs.

Build a jar with `./gradlew shadowJar` through the shared Gradle slot. Run:

```bash
python3 -B e2e/run.py \
  --jar build/libs/plugin-datadog-1.0.0-SNAPSHOT.jar \
  --artifact-commit "$(git rev-parse HEAD)" \
  --evidence "$HQ/agents/d09-e2e-complete/e2e"
```

The runner records the artifact commit and jar SHA-256 digest. Generic API flows are included by default. It fails if an implemented component has no end-to-end flow unless `--allow-partial` is set. Partial runs list the uncovered components explicitly.

`scenarios.py` contains inputs, pinned operation IDs, response fixtures and expected outputs. Run `python3 -B e2e/generate_flows.py` to regenerate the area flows and `fixtures.json`. `ScenarioTest` verifies the same fixtures through the Java task runtime and the contract helper. Each task has its own mock route so dry-run and live cases cannot interfere with another task's state.

Each trigger has a separate flow. The runner waits for baseline polls, changes only that trigger's fixture and checks for one successful execution. It then observes more polls and rejects duplicate executions. Trigger flows are not manually executed.

The generator emits concrete YAML values without anchors. Kestra's Property deserializer reads YAML aliases as identifier strings in this server version. Both dry-run branches therefore need independent values.

The mock records request methods, paths, queries and bodies. It records only whether connection headers were present. It never contacts Datadog. The mock rejects unknown endpoints. Execution output and server logs stay in the evidence directory, outside version control. The runner stops its own container and mock server in a finally block.

The runner uses Docker host networking so the container reaches the local mock. Ports are chosen from free loopback ports. Dummy credentials are fixed only for this disposable local instance. Live Datadog behavior and production permissions are not verified.
