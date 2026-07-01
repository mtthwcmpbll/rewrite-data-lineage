# Feature Specification: Spring Boot HTTP Data Lineage (MVP)

**Feature Branch**: `001-spring-http-lineage`

**Created**: 2026-07-01

**Status**: Draft

**Input**: User description: "I'd like to be able to track data coming into HTTP endpoints and leaving as HTTP calls in Spring Boot applications as my first MVP."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Catalog a service's HTTP data entry and exit points (Priority: P1)

An engineer runs the analysis against a Spring Boot repository and gets a queryable inventory of
every place external data **enters** the service over HTTP (inbound endpoints) and every place data
**leaves** the service over HTTP (outbound calls). Each entry records what external identifier it is
associated with (the request path for inbound, the target URL/route for outbound), the type of data
carried, and the exact code location.

**Why this priority**: The catalog is the foundation everything else builds on. On its own it answers
"what is this service's HTTP data surface?" — useful immediately for audit, security review, and
onboarding — and it produces the nodes that later stories connect into chains. It is the smallest
slice that delivers standalone value.

**Independent Test**: Run the analysis against a sample Spring Boot service with a known set of
controller endpoints and outbound HTTP calls; verify every inbound endpoint and outbound call appears
as a catalog row with the correct external identifier, payload type, and location, and that
non-HTTP methods produce no rows.

**Acceptance Scenarios**:

1. **Given** a controller method that receives request data (e.g. a request body, path variable, or
   query parameter), **When** the analysis runs, **Then** an inbound entry is recorded with the
   endpoint's route as its external identifier and the received type as its payload type.
2. **Given** a method that issues an outbound HTTP call to another service, **When** the analysis
   runs, **Then** an outbound entry is recorded with the target route/URL as its external identifier
   and the sent type as its payload type.
3. **Given** a plain business method with no HTTP inbound or outbound behavior, **When** the analysis
   runs, **Then** no catalog entries are produced for it.
4. **Given** an inbound endpoint that receives data but makes no outbound call, **When** the analysis
   runs, **Then** the inbound entry is still cataloged (a source with no downstream sink is valid).

---

### User Story 2 - Trace data from an inbound endpoint to an outbound call (Priority: P1)

An engineer wants to know, within a single service, when data received at an HTTP endpoint actually
flows to an outbound HTTP call — not just that both exist. The analysis connects an inbound source to
an outbound sink when the received data (or a value derived from it) reaches the outbound call,
producing an ordered chain that links the two through the methods on the path.

**Why this priority**: Connecting entry to exit is the actual "lineage" the feature promises;
cataloging alone is only an inventory. This is the payoff story. It is P1 alongside Story 1 because
"track data coming in and leaving" is explicitly a tracing request, not just a listing request.

**Independent Test**: Run against a sample service where a known inbound payload is passed (directly
and via a helper/service method) into an outbound call; verify a chain is reported linking that
inbound source to that outbound sink, and that an endpoint whose data does **not** reach any outbound
call produces no chain.

**Acceptance Scenarios**:

1. **Given** a controller that receives a payload and passes it (or a field/derivative of it) to an
   outbound HTTP call, **When** the analysis runs, **Then** a chain is reported linking that inbound
   source to that outbound sink.
2. **Given** an outbound call whose arguments come only from locally-constructed or constant values
   (no inbound-derived data), **When** the analysis runs, **Then** no chain is reported to it from an
   inbound source, though the outbound call is still cataloged (Story 1).
3. **Given** an inbound payload that flows through one or more intermediate methods (e.g. controller →
   service → HTTP client) before reaching an outbound call, **When** the analysis runs, **Then** the
   chain includes the ordered path between source and sink, following the data across method and class
   boundaries within the repository.

---

### User Story 3 - Produce cross-repo-joinable identifiers (Priority: P2)

An engineer intends to eventually connect this service's outbound calls to the inbound endpoints of
*other* services. For that to be possible later, each inbound and outbound entry must carry an
external identifier captured in a normalized, comparable form — so an outbound call to `POST /orders`
in this service can be matched against an inbound `POST /orders` endpoint in another service.

**Why this priority**: This does not build the cross-repo join (out of scope for the MVP), but it
protects the future by ensuring the identifiers emitted now are join-ready. Getting identifier
normalization wrong now would force re-analysis later. It is P2 because the MVP is still valuable
without cross-repo joins, but the cost of ignoring it is high.

**Independent Test**: Run against two sample services where one calls an endpoint the other exposes;
verify the outbound entry's external identifier and the inbound entry's external identifier are
captured in a form that matches (same normalized route and method), without yet performing the join.

**Acceptance Scenarios**:

1. **Given** an inbound endpoint mapped to a route and HTTP method, **When** the analysis runs,
   **Then** its external identifier captures both the normalized route (template form, e.g.
   `/orders/{id}`) and the HTTP method.
2. **Given** an outbound call to a route and HTTP method, **When** the analysis runs, **Then** its
   external identifier captures the normalized target route and HTTP method in the same form used for
   inbound endpoints.

---

### Edge Cases

- **Source with no sink / sink with no source**: An inbound endpoint that makes no outbound call, and
  an outbound call fed only by local/constant data, are both still cataloged as nodes; only the chain
  (Story 2) is absent.
- **Composed / meta-annotated mappings**: Custom annotations composed from the standard request
  mappings must still be recognized as inbound endpoints.
- **Dynamically constructed outbound URLs**: When the target route is built from variables at runtime,
  the external identifier may be only partially known; it MUST be recorded as partial/templated rather
  than dropped or guessed.
- **Multiple inbound parameters**: An endpoint may receive data through several parameters (body +
  path + query); each is a taint origin contributing to the **single** SOURCE node for that endpoint
  (one node per handler, not one per parameter).
- **Multiple outbound calls in one method**: Each outbound call is its own sink.
- **Non-HTTP inbound/outbound in the same code** (messaging, database, files): out of scope for this
  MVP and MUST NOT be emitted as HTTP nodes.
- **Unresolved types**: When a payload type cannot be resolved, the entry MUST be recorded with an
  explicit "unknown type" marker rather than omitted.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST identify inbound HTTP entry points in a Spring Boot service (controller
  handler methods that receive external request data) and record each as a catalog entry.
- **FR-002**: The system MUST identify outbound HTTP calls a Spring Boot service makes (calls that
  send data to another HTTP endpoint) and record each as a catalog entry.
- **FR-003**: Each catalog entry MUST record: whether it is a source (inbound) or sink (outbound), the
  external identifier (route/URL + HTTP method), the payload type carried, and the precise code
  location (repository, file, method, expression).
- **FR-004**: The system MUST record the external identifier for inbound endpoints in normalized
  template form including the HTTP method (e.g. `POST /orders/{id}`).
- **FR-005**: The system MUST record the outbound external identifier with a path-only route template
  (scheme/host/query excluded) in the same normalized form used for inbound endpoints, and MUST record
  the outbound target's scheme+host/service authority separately, so a path-based match across
  services is exact while the called service remains identifiable.
- **FR-006**: The system MUST connect an inbound source to an outbound sink when data received at the
  source (or a value derived from it) reaches the outbound call, tracing the data across method and
  class boundaries within the repository (e.g. controller → service → HTTP client), and MUST record
  that connection as an ordered chain referencing the source, the sink, and the methods on the path.
- **FR-007**: The system MUST NOT report a chain to an outbound call whose arguments carry no
  inbound-derived data.
- **FR-008**: The system MUST detect outbound HTTP calls made through Spring's imperative HTTP clients
  — `RestTemplate` and `WebClient`. Declarative Feign clients are out of scope for this MVP (deferred
  to a later phase).
- **FR-009**: When a payload type or an external identifier cannot be resolved, the system MUST emit
  the entry with an explicit unknown/partial marker rather than omitting it.
- **FR-010**: The output MUST be queryable as structured data (filterable by source, sink, route,
  service) without requiring the consumer to read source code.
- **FR-011**: The analysis MUST be non-destructive — it MUST NOT modify the code under analysis.
- **FR-012**: The analysis MUST be deterministic — repeated runs against unchanged code MUST produce
  identical output.
- **FR-013**: The system MUST NOT emit non-HTTP data movement (messaging, persistence, files) as HTTP
  catalog entries in this MVP.

### Key Entities *(include if feature involves data)*

- **HTTP data node**: A single point where external data enters (source/inbound) or leaves
  (sink/outbound) the service over HTTP. Attributes: direction (source or sink), external identifier
  (normalized route + HTTP method), payload type, code location. This is the catalog row from Story 1.
- **Data flow chain**: An ordered connection from an inbound source to an outbound sink, listing the
  methods the data passes through. References the source node, the sink node, and the intermediate
  path. This is the lineage from Story 2.
- **External identifier**: The normalized, comparable key (path-only route template + HTTP method) that
  makes a node matchable across services, plus — for outbound calls — the target service authority
  recorded separately. Shared representation between inbound and outbound entries so they can be joined
  later (Story 3).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a representative Spring Boot service, the analysis identifies at least 95% of the
  inbound endpoints and outbound HTTP calls that a manual audit of the same service finds (recall),
  with no more than 5% of reported entries being incorrect (precision).
- **SC-002**: 100% of catalog entries carry a populated external identifier and payload type, or an
  explicit unknown/partial marker where these cannot be resolved — no entry is silently blank.
- **SC-003**: For a sample service with a known set of inbound-to-outbound data paths, the analysis
  reports each expected path as a chain with the correct source and sink, and reports no chain for
  outbound calls that carry no inbound-derived data.
- **SC-004**: Repeated runs against unchanged code produce byte-identical output (deterministic).
- **SC-005**: An analyst can answer "which inbound endpoints' data reaches an outbound call to service
  X?" using only the structured output, without reading the service's source code.
- **SC-006**: Inbound and outbound external identifiers for the same logical route across two services
  are captured in a form that matches exactly, demonstrated on a paired sample (caller + callee).

## Assumptions

- **Framework**: Target services use Spring Boot with Spring MVC-style annotated controllers for
  inbound endpoints. Reactive WebFlux endpoints are out of scope for this MVP.
- **Granularity**: Data is tracked at the call/value level ("this inbound payload reached this outbound
  call"), not field level ("this specific field flowed to that specific field"). Field-level tracking
  is a documented future extension.
- **Scope of the graph**: This MVP analyzes a single repository at a time. The cross-repo *join* is
  out of scope; only the join-ready identifiers (Story 3) are produced now.
- **Novel data**: Data that originates inside the application (clocks, random values, literals, env
  vars) is not treated as tracked provenance, consistent with the project design's non-goals.
- **Consumers**: The primary users are engineers and analysts running the analysis over their own or
  their org's repositories and querying the resulting structured output.
- **Inbound sources include** request bodies, path variables, query parameters, and headers received
  by controller handler methods.

## Out of Scope (MVP)

- Cross-repository joining of outbound calls to inbound endpoints (identifiers are made join-ready, but
  the join is not performed).
- Non-HTTP sources and sinks (messaging/Kafka, database/JDBC, files, cache).
- Semantic transformation summaries (project/filter/join/aggregate classification) — this MVP produces
  the catalog and the raw chain, not the human-readable semantic narration.
- Reactive WebFlux endpoints and non-Spring HTTP clients (e.g. raw `java.net.http`).
- Declarative Feign clients as outbound sinks (deferred; RestTemplate + WebClient only for the MVP).
- Field-level data tracking.
