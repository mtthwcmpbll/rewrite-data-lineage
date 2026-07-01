# Phase 1 Data Model: Spring Boot HTTP Data Lineage (MVP)

These are the serializable schema records — the Principle III contract. They are the stable interface
every stage depends on; changing a field is a versioned event. In the MVP they are realized as Java
records under `com.snowfort.recipe.lineage.model` and projected onto the data tables in
`contracts/data-tables.md`. This is a scoped subset of the `DataFlowNode` / `CallChainEdge` schemas in
`docs/DESIGN.md` (no transform rows, no library summary cards yet).

## Entity: `ExternalIdentifier`

The normalized, comparable key that makes a node matchable across services (Story 3).

| Field | Type | Notes |
|-------|------|-------|
| `httpMethod` | enum `HttpMethod` {GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS, UNKNOWN} | Derived from the mapping annotation (inbound) or client call (outbound). |
| `routeTemplate` | string | Path-only template form, e.g. `/orders/{id}` — scheme, host, and query string excluded. Class-level + method-level path joined for inbound; URI *path* extracted for outbound. |
| `targetAuthority` | string (nullable) | Outbound only: scheme + host / service authority (e.g. `inventory`) parsed from the URL, or `null` for inbound. Kept separate so the path-based join stays exact while callee identity is preserved. |
| `resolution` | enum `Resolution` {EXACT, PARTIAL, UNKNOWN} | EXACT = fully known template; PARTIAL = known prefix + placeholder for dynamic segments; UNKNOWN = could not resolve. |

**Rules**:
- Inbound and outbound MUST use identical construction rules so `(httpMethod, routeTemplate)` compares
  equal for the same logical route (FR-004, FR-005, SC-006).
- `routeTemplate` is path-only for BOTH directions; outbound scheme/host goes in `targetAuthority`,
  never folded into `routeTemplate`. This is what lets `(httpMethod, routeTemplate)` compare equal
  across services (SC-006) while `targetAuthority` retains which service was called.
- A dynamic outbound URL MUST yield PARTIAL or UNKNOWN — never a guessed concrete literal (FR-009, edge
  case "dynamically constructed outbound URLs").
- Path-variable placeholders are normalized to `{name}` form; the *name* is preserved.

## Entity: `DataFlowNode`

One point where external data enters (SOURCE) or leaves (SINK) the service over HTTP. Backs the
`HttpDataNodeTable` catalog (Story 1).

| Field | Type | Notes |
|-------|------|-------|
| `direction` | enum `Direction` {SOURCE, SINK} | SOURCE = inbound endpoint; SINK = outbound call. |
| `framework` | enum `Framework` {SPRING_MVC, REST_TEMPLATE, WEB_CLIENT} | Which detector matched. |
| `externalIdentifier` | `ExternalIdentifier` | Route + method + resolution (above). |
| `payloadType` | string | FQN of the in/out Java type, or `<unknown>` if unresolved (FR-009). |
| `payloadResolved` | boolean | False when `payloadType` is the unknown marker. |
| `nodeId` | string | Stable synthetic id = hash of `(sourceSetPath, methodFqn, direction, expressionCoords)`; used to reference the node from chain edges. |
| `locator.repository` | string | Repo/origin identifier from the LST source. |
| `locator.filePath` | string | Source-relative path. |
| `locator.methodFqn` | string | Declaring method fully-qualified signature. |
| `locator.line` | int | Line of the endpoint/call expression. |

**Rules**:
- Exactly **one SOURCE node per inbound handler method** and **one SINK node per outbound call
  expression**. A handler's `payloadType` is the request-body type when present, else the first
  inbound parameter's type; all inbound params (`@RequestBody`/`@RequestParam`/`@PathVariable`/
  `@RequestHeader`) are recorded as taint origins seeding the flow analysis, not as separate nodes.
- A SOURCE with no downstream SINK, and a SINK fed only by local/constant data, are BOTH still
  cataloged as nodes (FR-001, FR-002; edge case "source with no sink / sink with no source").
- `payloadType` MUST be present or explicitly `<unknown>` — never blank (SC-002).
- Non-HTTP entry/exit MUST NOT produce a `DataFlowNode` in this MVP (FR-013).

## Entity: `CallChainEdge`

One edge on an ordered source→sink path — the payload moving from one method to the next. Backs the
`DataFlowChainTable` (Story 2). A full chain is the ordered set of edges sharing a `(sourceNodeId,
sinkNodeId)` pair.

| Field | Type | Notes |
|-------|------|-------|
| `sourceNodeId` | string | `DataFlowNode.nodeId` of the inbound source anchoring this chain. |
| `sinkNodeId` | string | `DataFlowNode.nodeId` of the outbound sink anchoring this chain. |
| `edgeIndex` | int | 0-based position of this edge along the source→sink path (ordering). |
| `fromMethodFqn` | string | Caller method FQN. |
| `toMethodFqn` | string | Callee method FQN (the method the tainted value flows into). |
| `callSite.filePath` | string | File of the call expression. |
| `callSite.line` | int | Line of the call expression. |
| `taintedArgPositions` | string | Comma-joined caller argument positions carrying tainted values (e.g. `0,2`); empty if taint flows via receiver only. |
| `taintedReturn` | boolean | Whether the callee's return carries taint back to the caller. |

**Rules**:
- An edge MUST reference a real `DataFlowNode` at each end via `nodeId` (referential integrity with the
  node table — decoupled join, Principle III).
- No chain is emitted to a sink whose argument positions carry no source-derived taint (FR-007).
- Edges MUST be emitted in `edgeIndex` order and the whole table sorted deterministically (FR-012,
  SC-004).
- The MVP records edges at call/value granularity, not field granularity (spec Assumptions).

## Relationships

```text
DataFlowNode (SOURCE) ──anchors──┐
                                 ├──> CallChainEdge[0..n] (ordered by edgeIndex) ──> DataFlowNode (SINK)
DataFlowNode (SINK)  ──anchors──┘

DataFlowNode.externalIdentifier : ExternalIdentifier   (shared inbound/outbound form → future cross-repo join)
```

- Node ↔ chain join is by `nodeId` only; chain extraction has **no** knowledge of framework specifics
  or (future) transform kinds. This decoupling is the Principle III contract that lets summarization
  strategies attach later without re-running analysis.

## Derived / validation invariants (testable)

- **I1**: Every `CallChainEdge.sourceNodeId` / `sinkNodeId` resolves to a row in `HttpDataNodeTable`.
- **I2**: Every emitted `DataFlowNode` has non-blank `payloadType` (value or `<unknown>`) and a
  non-null `ExternalIdentifier` with a `resolution` value.
- **I3**: For identical input LSTs, both tables are byte-identical across runs (sorted emission).
- **I4**: No `DataFlowNode` is emitted for non-HTTP inbound/outbound constructs.
- **I5**: A chain exists between a source and sink **iff** position-aware taint connects them across
  the repo-local call graph.
