# Organization access, keys, teams and security

These tasks use the Datadog v2 API with `apiKey` and `appKey`. Store the connection values as Kestra secrets. Set `site` to the Datadog region that owns the keys. `apiUrl` supports a proxy or a local test server.

## Tasks and API operations

| Task | What it does | v2 operationId | Required permissions |
|---|---|---|---|
| `users.List` | Pages through user and service account access metadata | `ListUsers` | `user_access_read` |
| `users.Get` | Reads one user by ID | `GetUser` | `user_access_read` |
| `users.Disable` | Disables one identified user | `DisableUser` | `user_access_manage` or `service_account_write` for the target account |
| `teams.List` | Pages through teams with an optional keyword filter | `ListTeams` | `teams_read` |
| `teams.SyncMembers` | Reconciles one team's membership from user handles | `ListUsers`, `GetTeamMemberships`, `CreateTeamMembership`, `DeleteTeamMembership` | `user_access_read`, `teams_read` and permission to manage the team |
| `teams.RemoveMember` | Removes one identified user from a team | `GetTeamMemberships`, `DeleteTeamMembership` | `teams_read` and permission to manage the team |
| `keys.ListApiKeys` | Lists API key IDs and metadata | `ListAPIKeys` | `api_keys_read` |
| `keys.ListApplicationKeys` | Lists application key IDs and scopes | `ListApplicationKeys` | `org_app_keys_read` |
| `keys.Create` | Creates an API key or a current-user application key | `CreateAPIKey`, `CreateCurrentUserApplicationKey` | `api_keys_write` or `user_app_keys` |
| `keys.Rotate` | Checks an old key then creates a replacement | `GetAPIKey`, `GetApplicationKey`, `CreateAPIKey`, `CreateCurrentUserApplicationKey` | Read permission for the old key plus create permission for the replacement |
| `keys.Revoke` | Revokes one identified key | `DeleteAPIKey`, `DeleteApplicationKey` | `api_keys_delete` or `org_app_keys_write` |
| `security.SignalsSearch` | Searches an explicit time window with cursor paging | `SearchSecurityMonitoringSignals` | `security_monitoring_signals_read` |
| `security.TriageSignal` | Changes one signal's state, assignee or archive fields | `EditSecurityMonitoringSignal` | `security_monitoring_signals_write` |
| `catalog.UpsertServiceDefinition` | Applies supplied fields to one v2.2 service definition | `GetServiceDefinition`, `CreateOrUpdateServiceDefinitions` | `apm_service_catalog_read` and `apm_service_catalog_write` |
| `catalog.Delete` | Deletes one service definition by its service name | `DeleteServiceDefinition` | `apm_service_catalog_write` |

`GetAPIKey` needs `api_keys_read`. `GetApplicationKey` needs `org_app_keys_read`. The team membership operations declare `teams_read` in the OpenAPI description. Datadog also enforces the caller's ability to manage the target team.

## Key rotation and output handling

`keys.Create` and `keys.Rotate` return `keyId`, `name`, `scopes`, `preview` and `key`. `key` is `[REDACTED]` by default. The preview contains a mask and Datadog's `last4` value when returned. Set `includeSecrets: true` to return the new key value for delivery to a credential store. Do not log that output.

For `keyKind: APPLICATION`, `scopes` must be explicit. An empty list requests no scopes. Application key creation belongs to the authenticated user. Rotation does not copy ownership or scopes from the old key. Supply the intended scopes for the replacement.

Rotation always leaves the old key active. Deploy the replacement and verify its consumers before calling `keys.Revoke` with the old resource ID. Both creation tasks create a new key on every run. A retry after a lost response can create another key. These tasks do not claim idempotency.

The list and user outputs redact credential fields recursively unless `includeSecrets` is true. This covers key, API key, application key, password, token, authorization, credential and webhook fields. URL fields are redacted conservatively because they can contain webhook credentials. Redaction happens before writing ION files. Free-form text is not a general-purpose secret scanner.

Organization task errors retain the HTTP status and suppress response bodies. A failed response can echo a newly created key. The tasks do not write key values or response bodies to logs.

## Team membership sync

`teams.SyncMembers` requires an explicit `teamId` and `handles` list. Handles are exact Datadog handles after trimming. Duplicate desired handles, ambiguous inventory handles, unknown users and disabled users fail before writes. The task reads all user and membership pages within the configured limits. An incomplete inventory fails instead of being used for reconciliation.

Existing roles are retained. New users become regular members by omitting the optional `role` field. The API's only non-null role is `admin`. Members absent from `handles` are removed. An explicit empty list requests removal of every member.

The task returns `added`, `removed`, `unchanged` and a per-user membership `diff`. `status` is `UPDATED` or `UNCHANGED`. With `plan: true` the task uses GET requests to compute changes without writing. The last-admin guard still runs. A second run against the resulting membership returns `UNCHANGED` and makes no writes.

The membership and service definition previews use `plan` as both the input and output flag. Replace former `dryRun` references in flows. Across the plugin `dryRun` means zero HTTP requests and `plan` permits reads only.

`allowRemoveLastAdmin` defaults to false. The planned removal set is checked before any writes. Each removal also rereads the membership and checks the guard again. Datadog does not provide a conditional membership delete, so this is not an atomic lock against another writer. Serialize membership writers for the same team. Changes are sequential. A later failure does not roll back earlier additions or removals. Rerun to reconcile the remaining difference.

`teams.RemoveMember` applies the same guard to one user ID. It does not accept a membership ID in place of the user ID.

## Lists, storage and limits

Number-paged lists accept `pageSize` from 1 to 100 and `maxResults` from 1 to 10000. Defaults are 100 and 1000. They start at page zero. Responses use total counts when present. A short final page ends a list without totals. Repeated resource IDs fail the request because they prevent a reliable inventory.

List outputs contain `rows`, `size`, `truncated` and `uri`. Check `truncated` before treating a bounded result as a full inventory. With `store: true`, rows are written as ION to internal storage and `rows` is empty. Collection is bounded in memory by the result limit; storage reduces the execution output size rather than making the API reader unbounded.

Sync inventory limits default to 10000 users and 10000 memberships. Lower limits are supported. Reaching a limit before the inventory ends fails the sync.

`security.SignalsSearch` requires RFC 3339 `from` and `to` timestamps with an offset. `from` must precede `to`. `OLDEST` maps to `timestamp`; `NEWEST` maps to `-timestamp`. It follows `meta.page.after` and never follows a server-provided URL. Repeated cursors, an empty page with a cursor or a non-complete `meta.status` fail the task. A result limit sets `truncated` when more signals remain.

## Signal triage

`security.TriageSignal` addresses one signal. It maps `OPEN`, `UNDER_REVIEW` and `ARCHIVED` to the API's state values. `assigneeId` is a user UUID. Archive reason and comment require `state: ARCHIVED`.

The endpoint exposes `archive_comment`, not a general note operation. The task returns the signal ID and an update flag without echoing the signal body or comment.

## Service definition apply

`catalog.UpsertServiceDefinition` accepts one `definition` map in the v2.2 schema. It requires `schema-version: v2.2` and `dd-service`. It validates supported fields and reads the current definition with `schema_version=v2.2`.

`dd-service` is the stable key. A missing definition produces `CREATED`. Supplied fields are compared recursively. Omitted fields are retained. Supplied arrays replace whole arrays. Changed fields produce `UPDATED`; an empty diff produces `UNCHANGED` without a write. With `plan: true` the task reads the current definition and reports the planned status and field diff without writing.

Diff paths use JSON Pointer syntax. Credential and URL values are redacted unless `includeSecrets` is true. The task compares raw values before redaction so a credential change still appears in the diff. The read and write are separate requests. Serialize concurrent writers for the same service definition.

`catalog.Delete`, `users.Disable`, `keys.Revoke` and `teams.RemoveMember` identify a single object and support `ignoreNotFound`. Only HTTP 404 is ignored. Other errors fail the task.

## Coverage boundaries and verification

The typed tasks focus on access inventory, safe key lifecycle, team reconciliation, signal triage and service ownership. Other organization operations use the generic `api.Call` layer. This includes user creation and invitation, roles, team creation and links, service account key management, security rules and suppressions, CSM and application-security lists, software catalog entities and scorecards. No `security.NewSignal` trigger is included in this milestone. Scheduled signal searches are available; a trigger needs separate cursor and late-arrival semantics.

The mock fixtures use fields from the official v2 schemas. Redaction tests also inject credential fields to check defensive handling of unexpected response fields. Tests cover task happy paths, validation, HTTP 400/403/404/429/500, paging, ION storage, captured logs, typed flow examples and repeated reconciliation. Mutation evidence is recorded with the milestone publication.

No task has been run against a live Datadog organization. Datadog propagation delays, account-specific permission policy, concurrent writes and real key delivery have not been verified.
