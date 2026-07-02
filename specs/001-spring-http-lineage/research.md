# Phase 0 Research: Spring Boot HTTP Data Lineage (MVP)

All Technical Context unknowns are resolved below. Each item follows Decision / Rationale /
Alternatives considered.

## R1 — Inter-procedural taint without the Moderne platform call graph

**Decision**: Implement repo-local inter-procedural propagation inside a single OpenRewrite
`ScanningRecipe`. The scan phase accumulates, per method, the facts needed to stitch a call graph
(`MethodFlowFacts`); the generate phase builds the graph and propagates taint across method
boundaries. Do **not** depend on Moderne's platform-only call graph.

**Rationale**: The spec requires cross-method tracing (controller → service → client). OpenRewrite's
`rewrite-analysis` `Dataflow`/`Taint` is *intra*-procedural only. Moderne's call graph exists but is
not available under `RewriteTest`, and Constitution Principle II makes `RewriteTest` coverage
non-negotiable — so the analysis must run without the platform. A `ScanningRecipe` sees the whole
repo LST across both phases, which is exactly enough to build a repo-local call graph and propagate
summaries. This is the procedure-summary pattern from the design doc, scoped to a single repo.

**Alternatives considered**:
- *Moderne call graph API* — rejected for the MVP: not exercisable in unit tests, couples the core to
  the platform, violates the testability the constitution demands. Revisit for the cross-repo phase.
- *Intra-procedural only* — rejected: the user explicitly chose cross-method tracing; controller →
  service → client is the dominant Spring shape and single-method taint would miss almost all of it.
- *Whole-program third-party analyzer (e.g. external SAST)* — rejected: violates Principle I
  (recipe-first, LST-native) and adds a heavyweight dependency.

**R1 refinement (found during implementation):** `rewrite-analysis` already ships an inter-procedural
engine — `org.openrewrite.analysis.dataflow.global.GlobalDataFlow.accumulator(DataFlowSpec)`. Its
`Accumulator` exposes `scanner()` (drive it from the `ScanningRecipe` scan phase) and `summary(Cursor)`.
This removes the need to hand-build a call graph. **However**, the public `Summary` API is coarse —
`isSource()` / `isSink()` / `isFlowParticipant()` booleans per cursor (i.e. reachability). Producing the
`CallChainEdge` rows the contract specifies (ordered `edgeIndex`, `fromMethodFqn`/`toMethodFqn`,
`taintedArgPositions`) requires traversing the accumulator's internal `FlowGraph` structures
(`getSourceFlowGraphs`, `getMethodCallFlowGraphs`, … over functional-java `fj.data` types). That
FlowGraph traversal is the remaining, non-trivial work for User Story 2 and should be prototyped
against these structures before committing to the CallChainEdge shape.

**Prototype outcome (landed):** A working US2 prototype wires `GlobalDataFlow.accumulator(HttpFlowSpec)`
into the scan phase (composed with US1 node detection) and emits `DataFlowChains` rows in the edit
phase from `accumulator.isSink(cursor)` reachability. Validated by `HttpFlowChainTest`: it traces
`@RequestBody` -> `service.forward(order)` -> `RestTemplate.postForObject(url, o, ...)` across method
boundaries, pairs the source and sink nodes, records the tainted argument position, and correctly
emits no chain for a constant-fed sink (FR-007). Scoped deliberately: sources = inbound handler
params, sinks = RestTemplate arguments (WebClient's reactive generics don't type-resolve for
dataflow). Known prototype limitations, still to productionize (T025-T028): (1) chain rows are
reachability-level single edges, not the full ordered per-method `CallChainEdge` sequence — that needs
the FlowGraph traversal above; (2) source/sink pairing over-approximates when multiple sources reach
one sink (the prototype pairs every source with every reached sink); (3) RestTemplate only.

**Productionized outcome (US2 complete, T022-T028).** Investigation confirmed the rich FlowGraph
accessors (`getSourceFlowGraphs`, `getMethodCallFlowGraphs`, …) live on the **package-private**
`GlobalDataFlowAccumulator`; the public `Summary` is only `isSource/isSink/isFlowParticipant`
booleans. Reaching the per-edge structure would require reflection into OpenRewrite internals over
`fj.data` types — brittle. Per the chosen design, the ordered chain is instead reconstructed with a
**repo-local call graph + parameter-reference (local) taint**, using only public APIs:
- `flow/ParamRefs.java` — intra-procedural taint approximation: per method, which parameter positions
  each expression references (direct references + simple local aliasing to a fixed point). This is the
  `LocalTaint` role (T025).
- `flow/CallGraph.java` — every in-repo call and outbound sink accumulated in the scan phase as
  position-annotated edges (T026); position-aware fixed-point propagation from each source's inbound
  parameters, with first-reach parent tracking to reconstruct the ordered per-hop path (T027). This is
  the `CallGraphReachability` role.
- Generate phase emits ordered `DataFlowChains` rows, sorted by `(sourceNodeId, sinkNodeId, edgeIndex)`,
  referentially intact with node rows, with no chain to untainted sinks (T028).

All three prototype limitations are resolved: (1) full ordered `CallChainEdge` sequence with per-edge
`fromMethodFqn`/`toMethodFqn`/`taintedArgPositions`; (2) multi-source disambiguation (only sources
whose taint actually reaches a sink are paired); (3) WebClient chains are traced (the call graph does
not depend on `GlobalDataFlow` resolving reactive generics — the payload is taken from the
`bodyValue`/`body` link). `GlobalDataFlow` remains wired as the retained inter-procedural oracle.

## R2 — Propagation model (method summaries)

**Decision**: Model each method as a summary: which parameter positions (and receiver) are tainted-in
sources of interest, whether the method *contains* a sink, whether the method *is* a source, and for
each outgoing call which caller-argument positions flow into which callee parameters (from local
taint). Propagate with a fixed-point over the call graph: a value is source-tainted at a sink if
there is a call path from a source-containing method to a sink-containing method along which tainted
argument positions line up. Recursion/cycles handled by worklist to a fixed point (monotone, so it
terminates).

**Rationale**: Summaries keep propagation near-linear in call-graph size and avoid re-analyzing
callees per call site. Aligns with the design doc's "modular analysis with library summaries," here
restricted to in-repo methods (no library cards yet). Fixed-point over a monotone lattice is
deterministic — supports Principle IV.

**Alternatives considered**:
- *Inline expansion of callees* — rejected: exponential on deep call trees, non-modular.
- *Ignore argument positions (method→method only)* — rejected: produces false chains when only an
  untainted argument reaches the sink. The design doc flags parameter-position awareness as needed for
  `taintedArgPositions`; we compute it locally.

## R3 — Inbound source detection (Spring MVC)

**Decision**: A handler method is an inbound source if its declaring class is a controller
(`@Controller` / `@RestController`, including meta-annotations) and the method carries a request
mapping (`@RequestMapping` / `@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping` /
`@PatchMapping`, including composed/meta-annotations). Each parameter annotated `@RequestBody`,
`@RequestParam`, `@PathVariable`, or `@RequestHeader` (and the request body param) is a source value;
these seed intra-procedural taint. The external identifier is the combined class-level +
method-level path template plus the HTTP method derived from the mapping annotation.

**Rationale**: Type-and-annotation matching via `AnnotationMatcher`/`TypeUtils` is LST-native
(Principle I) and robust to formatting. Meta-annotation resolution catches custom composed mappings
(spec edge case). Seeding taint from the annotated params is what lets the payload be followed.

**Alternatives considered**:
- *Text search for `@GetMapping`* — rejected (Principle I; misses meta-annotations, false-matches in
  comments).
- *Treat the whole handler return as the only source* — rejected: inbound data arrives via params, not
  the return; the return is the response (a different, out-of-scope sink direction for the MVP).

## R4 — Outbound sink detection (RestTemplate + WebClient)

**Decision**: Match outbound calls with `MethodMatcher` against `RestTemplate` exchange/HTTP methods
(`getForObject`, `postForObject`, `exchange`, `getForEntity`, `postForEntity`, `put`, `delete`,
`patchForObject`, …) and the `WebClient` fluent chain (`WebClient` → `method(...)`/`get()`/`post()`
→ `uri(...)` → `bodyValue(...)`/`body(...)` → `retrieve()`). The sink's external identifier is the
URI argument (string literal or `UriComponentsBuilder` template) + HTTP method; body/argument values
are the tainted-in positions. WebClient's URI and body live on different links of the chain, so the
detector walks the fluent chain to a single logical sink.

**Rationale**: `MethodMatcher` resolves through the type system (Principle I). Covering both the
blocking (`RestTemplate`) and reactive-client (`WebClient`) APIs matches the user's chosen scope.

**Alternatives considered**:
- *Feign `@FeignClient`* — explicitly out of scope for the MVP (deferred). Noted so the sink matcher
  is structured to add it later without reshaping the schema.
- *Raw `java.net.http` / OkHttp / Apache HttpClient* — out of scope; Spring clients only.

## R5 — External identifier normalization (cross-repo join key)

**Decision**: `ExternalIdentifier` = `{ httpMethod, routeTemplate, resolution }` where
`routeTemplate` is the path in template form (`/orders/{id}`, path variable *names* normalized but
kept), and `resolution ∈ {EXACT, PARTIAL, UNKNOWN}`. Inbound and outbound use the **same**
representation so a later phase can join outbound→inbound by equality on `(httpMethod, routeTemplate)`.
Dynamically-built outbound URLs yield `PARTIAL` (known prefix + placeholder) or `UNKNOWN`, never a
guessed literal.

**Rationale**: Story 3 / FR-004 / FR-005 require join-ready identifiers now even though the join is
deferred. A shared normalized form is the cheapest insurance against re-analysis later (Principle
III). The `resolution` marker satisfies "confidence as data" (Principle IV) and FR-009.

**Alternatives considered**:
- *Store raw URL strings* — rejected: `/orders/42` (outbound, concrete) would never match
  `/orders/{id}` (inbound, template). Normalization to templates is what makes the join possible.
- *Defer identifier design to the cross-repo phase* — rejected: retrofitting keys forces re-running
  analysis; the constitution treats schema as a versioned contract to get right up front.

## R6 — Output as data tables

**Decision**: Two OpenRewrite `DataTable`s: `HttpDataNodeTable` (one row per catalogued source/sink)
and `DataFlowChainTable` (one row per edge on a source→sink path). Columns defined in
`contracts/data-tables.md`. Rows are sorted by a stable key (repo, path, method, expression position)
before emission.

**Rationale**: Data tables are the OpenRewrite-native, queryable output surface (Principle I) and are
first-class on the Moderne platform and CLI. Two tables keep the catalog (Story 1) decoupled from the
chain (Story 2), matching the design doc's decoupled-outputs stance (Principle III). Sorting gives
determinism (Principle IV / SC-004).

**Alternatives considered**:
- *Single combined table* — rejected: couples node catalog to chain, and a node with no chain (spec
  edge case) has no natural row.
- *`SearchResult` markers / printed output* — rejected: not queryable as structured data (FR-010) and
  markers would visually mutate output (Principle IV spirit).

## R7 — Test fixtures need Spring web types on the classpath

**Decision**: Add `spring-web` and `spring-webflux` (for `WebClient`) to the recipe test classpath
and to the parser classpath used by fixtures, alongside the existing `spring-core`/`spring-context`.
Use `RewriteTest` `.parser(...classpath("spring-web", "spring-webflux", ...))` or
`@BeforeTemplate`-style classpath-from-resources so `RestTemplate`, `WebClient`, and the MVC
annotations resolve as real types in fixtures.

**Rationale**: `MethodMatcher`/`AnnotationMatcher` only resolve against typed LSTs; fixtures must
compile against real Spring types or detection silently no-ops. Principle II requires the tests to
exercise real idioms.

**Alternatives considered**:
- *Hand-written stub Spring types in fixtures* — rejected: brittle, diverges from real signatures,
  weakens the negative-test guarantee.

## R8 — Determinism strategy

**Decision**: All accumulation uses insertion-ordered or explicitly sorted collections; the generate
phase sorts every emitted row list by `(sourceLocation, sinkLocation, edgeIndex)`; taint fixed-point
iterates a sorted worklist. No use of `Date.now`/randomness/hash-ordered iteration in emitted output.

**Rationale**: SC-004 requires byte-identical output across runs; Principle IV requires order-stable
output. `ScanningRecipe` accumulator visitation order is deterministic for a fixed LST, but map
iteration is not — hence explicit sorting before emit.

**Alternatives considered**:
- *Rely on incidental ordering* — rejected: `HashMap`/`HashSet` iteration order is unstable across
  JVMs and would break SC-004.

## Resolved unknowns summary

| Unknown | Resolution |
|---------|------------|
| Cross-method taint without platform call graph | R1 — repo-local call graph in a `ScanningRecipe` |
| Propagation precision (arg positions) | R2 — method summaries + position-aware fixed point |
| Inbound detection | R3 — controller + mapping annotations, param sources |
| Outbound detection | R4 — `MethodMatcher` for RestTemplate + WebClient fluent chain |
| Cross-repo identifier form | R5 — normalized `(httpMethod, routeTemplate)` + resolution marker |
| Output surface | R6 — two data tables, sorted |
| Spring types in tests | R7 — add spring-web / spring-webflux to test + parser classpath |
| Determinism | R8 — explicit sort before emit, no unstable iteration |
