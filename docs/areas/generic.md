# Generic API access

## api.Call

Calls any operation in the pinned Datadog OpenAPI catalog by `operationId`. Use `api.Operations` to find its parameters, security requirements and content types. A duplicate ID must include its version, for example `v2:SubmitLog` or `v1:SubmitLog`. An ambiguous ID fails before a request.

The catalog contains 1941 version-qualified operations from the supplied Datadog v1 and v2 descriptions. Its header records a SHA-256 digest for each source. `scripts/gen-catalog.py` generates the catalog from two spec paths. The full OpenAPI documents are not bundled.

`pathParams`, `query` and `headers` use the exact API names. Parameters are checked for required values, primitive types, enums and numeric bounds. Path values are encoded as single segments. Query arrays follow the spec's explode setting. A required request body must be present. JSON syntax is checked but arbitrary body fields are not validated by this task.

`body` accepts a JSON map or array, a raw string or an internal storage URI. Select another supported content type with `headers.Content-Type`. For multipart operations, use a map of text fields and internal storage file URIs. Gzip and deflate content encodings compress the body. Request bodies and individual response pages are buffered in memory.

The API key is required. Operations with application-key security also require `appKey` unless an Authorization header is supplied. The catalog exposes the OAuth scopes and permission metadata from the spec. A key still needs the corresponding scopes in Datadog. The selected operation controls whether data is read, created, changed or deleted. There is no implicit bulk operation or upsert behavior added by this task.

`site` selects the regional server. Intake server overrides are taken from the catalog. `apiUrl` replaces the server for a proxy or a mock. Redirects are not followed. Use the endpoint's supported region. Some operations have a fixed server in the spec.

Responses are not automatically redacted. `redact` is a list of RFC 6901 JSON pointers relative to each complete response, for example `/data/attributes/key`. Missing pointers are ignored. An empty pointer redacts the whole document. No wildcard syntax is supported. Redaction runs before JSON reaches outputs or internal storage. Non-JSON replies fail if redaction is requested. Response headers and error bodies are not emitted.

With `paginate: true`, the task detects these query styles:

- `page[cursor]` with the cursor response path from the catalog, defaulting to `meta.page.after`.
- `page[offset]` with `page[limit]`.
- `page[number]` with `page[size]`.

The task preserves a supplied starting position. A repeated cursor fails. It stops at a short numeric page, an exhausted cursor or `maxItems`. Body-based pagination and other parameter names require explicit calls. `store: true` writes JSON as ION rows instead of retaining the full list in task outputs. Binary responses are always written to internal storage. Text responses are returned as strings unless stored.

Only idempotent HTTP methods retry on 429 or 5xx. POST and PATCH never retry. `X-RateLimit-Reset` is read as seconds. If it exceeds `maxRetryDelay`, the task fails without retrying early. Without a valid reset header, delays use exponential backoff. Network errors are not retried.

```yaml
id: datadog_generic_read
namespace: company.observability
tasks:
  - id: roles
    type: io.kestra.plugin.datadog.api.Call
    apiKey: "{{ secret('DATADOG_API_KEY') }}"
    appKey: "{{ secret('DATADOG_APP_KEY') }}"
    operationId: ListRoles
    paginate: true
    maxItems: 500
    store: true
    query:
      page[size]: 100
```

## api.Operations

Searches catalog entries by tag substring, HTTP method or text in the operation ID, tag and path. No connection or keys are needed. Output rows include `qualifiedId`, method, path, parameters, body requirements, security and server definitions. This is discovery, not a live API call.

## Verification limits

The generated test prepares a request for every catalog entry and checks its method and path. Mock tests cover validation, response handling, all three paging styles, retry behavior and redaction. These checks do not verify a live Datadog organization, permission assignments, availability of unstable endpoints or production API limits. A prepared request proves catalog reach, not server acceptance of an arbitrary request body.
