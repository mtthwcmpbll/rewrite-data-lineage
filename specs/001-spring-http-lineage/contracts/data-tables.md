# Contract: Data Tables (output interface)

The recipe's external interface is two OpenRewrite `DataTable`s. These column contracts are the
consumer-facing API (Principle III); column additions are MINOR, renames/removals/type changes are
breaking and MUST be flagged in review. Columns map directly onto the records in `data-model.md`.

## Table 1: `com.snowfort.recipe.lineage.HttpDataNodes`

**Display name**: "HTTP data nodes (sources & sinks)"
**Description**: Every inbound HTTP endpoint (source) and outbound HTTP call (sink) discovered in the
repository, one row each. Catalog output for User Story 1.
**Row identity**: `nodeId` is unique per row.

| Column (key) | Display name | Type | Description |
|--------------|-------------|------|-------------|
| `nodeId` | Node ID | String | Stable synthetic id; referenced by the chain table. |
| `direction` | Direction | String | `SOURCE` or `SINK`. |
| `framework` | Framework | String | `SPRING_MVC`, `REST_TEMPLATE`, or `WEB_CLIENT`. |
| `httpMethod` | HTTP method | String | `GET`/`POST`/… or `UNKNOWN`. |
| `routeTemplate` | Route template | String | Normalized route, e.g. `/orders/{id}`. |
| `routeResolution` | Route resolution | String | `EXACT` / `PARTIAL` / `UNKNOWN`. |
| `targetAuthority` | Target authority | String | Outbound scheme+host/service (e.g. `inventory`); blank for inbound. |
| `payloadType` | Payload type | String | FQN of the in/out type, or `<unknown>`. |
| `payloadResolved` | Payload resolved | Boolean | False when payload type unresolved. |
| `repository` | Repository | String | Repo/origin identifier. |
| `filePath` | File | String | Source-relative file path. |
| `methodFqn` | Method | String | Declaring method signature. |
| `line` | Line | Integer | Line of the endpoint/call. |

**Guarantees**: no blank `payloadType`/`routeResolution` (SC-002); rows sorted by
`(repository, filePath, line, direction)`; sources with no sink and sinks with no source both present.

## Table 2: `com.snowfort.recipe.lineage.DataFlowChains`

**Display name**: "HTTP data-flow chains (source → sink)"
**Description**: One row per edge on an inbound-source → outbound-sink path where request data reaches
the outbound call. Lineage output for User Story 2. Join to `HttpDataNodes` on `sourceNodeId` /
`sinkNodeId`.

| Column (key) | Display name | Type | Description |
|--------------|-------------|------|-------------|
| `sourceNodeId` | Source node ID | String | FK → `HttpDataNodes.nodeId` (the inbound source). |
| `sinkNodeId` | Sink node ID | String | FK → `HttpDataNodes.nodeId` (the outbound sink). |
| `edgeIndex` | Edge index | Integer | 0-based position along the path. |
| `fromMethodFqn` | From method | String | Caller method signature. |
| `toMethodFqn` | To method | String | Callee method signature. |
| `callSiteFile` | Call-site file | String | File of the call expression. |
| `callSiteLine` | Call-site line | Integer | Line of the call expression. |
| `taintedArgPositions` | Tainted arg positions | String | Comma-joined caller arg indices carrying taint; empty if via receiver. |
| `taintedReturn` | Tainted return | Boolean | Callee return carries taint back. |

**Guarantees**: every `sourceNodeId`/`sinkNodeId` resolves in `HttpDataNodes` (I1); no chain to a sink
without source-derived taint (FR-007); rows sorted by `(sourceNodeId, sinkNodeId, edgeIndex)`
(SC-004).

## Cross-repo forward-compatibility note

`routeTemplate` + `httpMethod` are emitted identically for inbound and outbound nodes so a future
cross-repo phase can join `SINK`→`SOURCE` by equality without re-running this analysis (FR-005,
SC-006). No join is performed by this recipe.
