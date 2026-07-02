---
description: "Task list for Spring Boot HTTP Data Lineage (MVP)"
---

# Tasks: Spring Boot HTTP Data Lineage (MVP)

**Input**: Design documents from `/specs/001-spring-http-lineage/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: REQUIRED. The project constitution (v1.0.0) makes Test-First recipe development
**non-negotiable (Principle II)** — every detector needs a positive **and** negative `RewriteTest`
asserting on emitted data-table rows. Test tasks below are therefore mandatory, not optional.

**Organization**: Grouped by user story (US1–US3 from spec.md) for independent implementation/testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1 / US2 / US3 (setup, foundational, and polish tasks carry no story label)
- All paths are repo-relative from the repository root.

## Path Conventions

Single OpenRewrite recipe module. Source under `src/main/java/com/snowfort/recipe/lineage/`, tests
under `src/test/java/com/snowfort/recipe/lineage/` (per plan.md Structure Decision).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Make the module able to compile recipes and type-resolve Spring web fixtures.

- [X] T001 Add `spring-web` and `spring-webflux` to the test + recipe parser classpath in `build.gradle.kts` (research R7): add `testRuntimeOnly`/`testImplementation` entries and, if using classpath-from-resources, wire the parser classpath so `RestController`, `RestTemplate`, and `WebClient` types resolve in fixtures.
- [X] T002 [P] Create the base package directory structure `src/main/java/com/snowfort/recipe/lineage/{model,table,source,sink,flow}/` (empty package-info or placeholder as needed).
- [X] T003 Verify baseline build: `./gradlew compileJava compileTestJava` passes with the new dependencies and empty structure (guards the T001 classpath wiring before any detection work).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Lock down the serializable schema (design Phase 1) and the scanning-recipe skeleton that
BOTH the catalog (US1) and the chain (US2) depend on. This is the Principle III contract.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T004 [P] Create `HttpMethod` and `Resolution` enums in `src/main/java/com/snowfort/recipe/lineage/model/` (values per data-model.md).
- [X] T005 [P] Create `Direction` and `Framework` enums in `src/main/java/com/snowfort/recipe/lineage/model/`.
- [X] T006 Implement `ExternalIdentifier` record in `src/main/java/com/snowfort/recipe/lineage/model/ExternalIdentifier.java` with the shared normalized-route construction rules (depends on T004).
- [X] T007 Implement `DataFlowNode` record + nested `Locator` + stable `nodeId` hashing in `src/main/java/com/snowfort/recipe/lineage/model/DataFlowNode.java` (depends on T005, T006).
- [X] T008 Implement `CallChainEdge` record in `src/main/java/com/snowfort/recipe/lineage/model/CallChainEdge.java`, referencing nodes by `nodeId` (depends on T007).
- [X] T009 [P] Implement `HttpDataNodeTable` (`com.snowfort.recipe.lineage.HttpDataNodes`) `DataTable` with columns per contracts/data-tables.md in `src/main/java/com/snowfort/recipe/lineage/table/HttpDataNodeTable.java` (depends on T007).
- [X] T010 [P] Implement `DataFlowChainTable` (`com.snowfort.recipe.lineage.DataFlowChains`) `DataTable` per contracts/data-tables.md in `src/main/java/com/snowfort/recipe/lineage/table/DataFlowChainTable.java` (depends on T008).
- [X] T011 Implement the `FindHttpDataLineage` `ScanningRecipe` skeleton + `MethodFlowFacts` accumulator + deterministic sorted-emit helper in `src/main/java/com/snowfort/recipe/lineage/FindHttpDataLineage.java` and `flow/MethodFlowFacts.java` (getDisplayName/getDescription; generate phase emits empty tables for now) (depends on T009, T010).
- [X] T012 Create a `RewriteTest` base (`src/test/java/com/snowfort/recipe/lineage/LineageRecipeTest.java`) that configures the Spring web parser classpath and a helper to assert data-table rows (depends on T001, T011).

**Checkpoint**: Schema + tables + scanning skeleton compile; recipe runs and emits empty tables.

---

## Phase 3: User Story 1 - Catalog HTTP data entry/exit points (Priority: P1) 🎯 MVP

**Goal**: Emit one `HttpDataNodes` row for every inbound Spring MVC endpoint and every outbound
`RestTemplate`/`WebClient` call, with route+method, payload type, and location.

**Independent Test**: Run the recipe on a fixture with known controllers and outbound calls; assert a
SOURCE/SINK row per endpoint/call with correct fields, and no rows for non-HTTP methods (quickstart
scenario 1; contracts C1–C3, C6).

### Tests for User Story 1 (write first, must fail)

- [X] T013 [P] [US1] `SpringMvcSourceTest` in `src/test/java/com/snowfort/recipe/lineage/source/SpringMvcSourceTest.java`: positive (`@RestController`+`@PostMapping`+`@RequestBody` → SOURCE row), negative (plain method → no row), plus a composed/meta-annotation mapping case.
- [X] T014 [P] [US1] `RestTemplateSinkTest` in `src/test/java/com/snowfort/recipe/lineage/sink/RestTemplateSinkTest.java`: positive (`postForObject`/`exchange` → SINK row), negative (non-HTTP method call → no row).
- [X] T015 [P] [US1] `WebClientSinkTest` in `src/test/java/com/snowfort/recipe/lineage/sink/WebClientSinkTest.java`: positive (`WebClient…uri…bodyValue…retrieve` fluent chain → one SINK row), negative.
- [X] T016 [P] [US1] Catalog-level `FindHttpDataLineageTest` in `src/test/java/com/snowfort/recipe/lineage/FindHttpDataLineageTest.java`: combined fixture asserts all `HttpDataNodes` rows, including a source-with-no-sink and an unresolved payload emitting the `<unknown>` marker (FR-009, SC-002); also assert the recipe makes **no source changes** to any fixture (FR-011, Principle IV).

### Implementation for User Story 1

- [X] T017 [US1] Implement `SpringMvcSource` in `src/main/java/com/snowfort/recipe/lineage/source/SpringMvcSource.java`: detect controller handlers via `AnnotationMatcher` (incl. meta-annotations), build `ExternalIdentifier` from class+method path template + HTTP method, mark `@RequestBody`/`@RequestParam`/`@PathVariable`/`@RequestHeader` params as source values, resolve payload type (depends on T007).
- [X] T018 [P] [US1] Implement `RestTemplateSink` in `src/main/java/com/snowfort/recipe/lineage/sink/RestTemplateSink.java` via `MethodMatcher` for RestTemplate HTTP methods; extract URI arg + body arg positions + payload type (depends on T007).
- [X] T019 [P] [US1] Implement `WebClientSink` in `src/main/java/com/snowfort/recipe/lineage/sink/WebClientSink.java`: walk the fluent chain to a single logical sink; extract `uri`, `bodyValue`/`body`, HTTP method (depends on T007).
- [X] T020 [US1] Wire the three detectors into `FindHttpDataLineage` scan phase to accumulate nodes into `MethodFlowFacts`, and emit sorted `HttpDataNodes` rows in the generate phase (depends on T011, T017, T018, T019).
- [X] T021 [US1] Implement payload-type / route resolution and `<unknown>`/`PARTIAL` marker handling for node construction so no field is ever blank (FR-009, SC-002) (depends on T020).

**Checkpoint**: US1 fully functional — the recipe catalogs the repo's HTTP data surface. **This is the MVP.**

---

## Phase 4: User Story 2 - Trace data endpoint → outbound call (Priority: P1)

**Goal**: Emit `DataFlowChains` rows linking an inbound source to an outbound sink when request data
reaches the outbound call, tracing across method/class boundaries within the repo.

**Independent Test**: Run on a controller→service→client fixture; assert chain edges linking the
SOURCE to the SINK, and assert a constant-fed outbound call produces **no** chain (quickstart scenario
2; contracts C4, C5). Depends on US1 detectors to identify the endpoints being connected.

> **COMPLETE (to contract).** Chains are now the full ordered per-edge `CallChainEdge` sequence
> (controller→service→client emits one edge per method hop with per-edge `fromMethodFqn`/`toMethodFqn`
> and `taintedArgPositions`), with multi-source disambiguation and WebClient support. The rich
> `GlobalDataFlowAccumulator` FlowGraph accessors turned out to be package-private (reachable only by
> reflection), so per the chosen design the ordered chain is reconstructed by a **repo-local call graph
> + parameter-reference (local) taint** (`flow/CallGraph.java`, `flow/ParamRefs.java`); `GlobalDataFlow`
> is retained as the inter-procedural reachability oracle. Task class names below are mapped to this
> architecture: `LocalTaint` → `ParamRefs`, `CallGraphReachability` → `CallGraph`. See research.md R1.

### Tests for User Story 2 (write first, must fail)

- [X] T022 [P] [US2] Intra-procedural taint test in `src/test/java/com/snowfort/recipe/lineage/flow/LocalTaintTest.java`: within one method, a `@RequestBody` value reaching `postForObject` is detected as a taint edge; an untainted arg is not (plus a local-aliasing case).
- [X] T023 [P] [US2] `CallGraphReachabilityTest` in `src/test/java/com/snowfort/recipe/lineage/flow/CallGraphReachabilityTest.java`: controller→service→client fixture yields chain edges source→sink; constant-fed sink yields no chain (FR-007); multi-source disambiguation; WebClient chain traced.
- [X] T024 [P] [US2] End-to-end chain assertions in `FindHttpDataLineageTest`: `DataFlowChains` rows have correct `edgeIndex` ordering, `taintedArgPositions`, deterministic sort, and every `sourceNodeId`/`sinkNodeId` resolves in `HttpDataNodes` (invariant I1).

### Implementation for User Story 2

- [X] T025 [US2] Implement local taint as `flow/ParamRefs.java` (the `LocalTaint` role): per method, map each argument to the caller-parameter positions it references (direct + simple local aliasing, fixed point). Authoritative inter-procedural reachability retained via `GlobalDataFlow` (depends on T007, T017).
- [X] T026 [US2] Extend the scan phase to accumulate per-method outgoing call edges (with arg positions) and local taint facts into the repo-local call graph (`flow/CallGraph.java`, held by `LineageAccumulator`) (depends on T020, T025).
- [X] T027 [US2] Implement position-aware call-graph reachability in `flow/CallGraph.java` (the `CallGraphReachability` role): fixed-point propagation from each source's inbound params to every reachable sink, with first-reach parent tracking for ordered path reconstruction (research R1/R2) (depends on T026).
- [X] T028 [US2] Emit `DataFlowChains` rows in the generate phase — ordered by `edgeIndex`, sorted deterministically by `(sourceNodeId, sinkNodeId, edgeIndex)`, with referential integrity to node rows and no chain to untainted sinks (depends on T027, T010).

**Checkpoint**: US1 + US2 work — the recipe catalogs nodes AND traces lineage across methods.

---

## Phase 5: User Story 3 - Cross-repo-joinable identifiers (Priority: P2)

**Goal**: Guarantee inbound and outbound external identifiers are constructed symmetrically so the
same logical route matches across services, and mark dynamic/unresolved routes rather than guessing.

**Independent Test**: A caller fixture (`WebClient…uri("/orders/{id}")`) and a controller fixture
(`@GetMapping("/orders/{id}")`) produce equal `(httpMethod, routeTemplate)`; a dynamic URL yields
PARTIAL/UNKNOWN (quickstart scenario 3; SC-006, contract C7).

### Tests for User Story 3 (write first, must fail)

- [ ] T029 [P] [US3] Identifier-symmetry test in `src/test/java/com/snowfort/recipe/lineage/model/ExternalIdentifierTest.java`: inbound `@GetMapping("/orders/{id}")` and outbound `uri("/orders/{id}")` produce equal identifiers (SC-006).
- [ ] T030 [P] [US3] Dynamic-URL resolution test (in the sink tests): a runtime-built outbound URL yields `routeResolution=PARTIAL` or `UNKNOWN`, never a guessed literal (FR-009, C7).

### Implementation for User Story 3

- [ ] T031 [US3] Harden `ExternalIdentifier` normalization for symmetric inbound/outbound construction: class+method path join for inbound; for outbound, extract the path-only `routeTemplate` and the scheme+host into `targetAuthority` (never fold host into the route), in `model/ExternalIdentifier.java` and the detectors (depends on T017, T018, T019).
- [ ] T032 [US3] Implement dynamic/unresolved URL → `PARTIAL`/`UNKNOWN` resolution marking in `RestTemplateSink`/`WebClientSink` (depends on T031).

**Checkpoint**: All three stories independently functional; identifiers are join-ready for a future cross-repo phase.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Determinism, precision/recall evidence, docs, and final validation.

- [ ] T033 [P] Determinism test in `FindHttpDataLineageTest`: run the recipe twice over identical source and assert byte-identical table output (SC-004, C8) and that no fixture source is modified (FR-011).
- [ ] T034 [P] Precision/recall fixture set in `src/test/java/com/snowfort/recipe/lineage/fixtures/`: a representative multi-endpoint Spring service fixture; assert detection ≥95% recall / ≤5% false positives against a known ground-truth list (SC-001).
- [ ] T035 [P] Review every recipe's `getDisplayName`/`getDescription` and add an optional declarative aggregate entry/doc in `rewrite.yml` if useful (Principle I; contracts/recipes.md).
- [ ] T036 [P] Update `README.md`/`docs/` to document the `FindHttpDataLineage` recipe and map it to design Phases 1–3+5.
- [ ] T037 Run all four `quickstart.md` validation scenarios and confirm `./gradlew build` is green.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately.
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS all user stories.
- **User Story 1 (Phase 3)**: Depends on Foundational. Delivers the MVP.
- **User Story 2 (Phase 4)**: Depends on Foundational **and** US1 detectors (T017–T020) — a chain
  needs the source/sink nodes it links. Not started until US1 node emission exists.
- **User Story 3 (Phase 5)**: Depends on Foundational **and** the detectors from US1 (T017–T019).
  Independent of US2.
- **Polish (Phase 6)**: Depends on the desired stories being complete.

### User Story Dependencies

- **US1 (P1)**: Independent once Foundational is done. → MVP.
- **US2 (P1)**: Builds on US1's node detection (spec permits US2 integrating with US1). Independently
  testable via its own controller→service→client fixtures.
- **US3 (P2)**: Builds on US1's identifier construction; independent of US2.

### Within Each User Story

- Tests (Principle II) are written first and must fail before implementation.
- Model/enums → tables → detectors → scan/generate wiring.
- Detectors in different files ([P]) can proceed in parallel; scan/generate wiring is the join point.

### Parallel Opportunities

- Setup: T002 is [P].
- Foundational: T004/T005 [P]; T009/T010 [P] once their records exist.
- US1 tests T013–T016 all [P]; detectors T018/T019 [P] alongside T017.
- US2 tests T022–T024 [P].
- US3 tests T029/T030 [P].
- Polish T033–T036 [P].

---

## Parallel Example: User Story 1

```bash
# Write all US1 tests first (they must fail), in parallel:
Task: "SpringMvcSourceTest in src/test/java/com/snowfort/recipe/lineage/source/SpringMvcSourceTest.java"
Task: "RestTemplateSinkTest in src/test/java/com/snowfort/recipe/lineage/sink/RestTemplateSinkTest.java"
Task: "WebClientSinkTest in src/test/java/com/snowfort/recipe/lineage/sink/WebClientSinkTest.java"

# Then implement the sink detectors in parallel (different files):
Task: "RestTemplateSink in src/main/java/com/snowfort/recipe/lineage/sink/RestTemplateSink.java"
Task: "WebClientSink in src/main/java/com/snowfort/recipe/lineage/sink/WebClientSink.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup.
2. Complete Phase 2: Foundational (schema + tables + scanning skeleton) — blocks everything.
3. Complete Phase 3: User Story 1 — the recipe catalogs the HTTP data surface.
4. **STOP and VALIDATE**: quickstart scenario 1; run `./gradlew build`.
5. This is a shippable MVP (an HTTP source/sink inventory) on its own.

### Incremental Delivery

1. Setup + Foundational → foundation ready.
2. US1 → catalog → validate → demo (MVP).
3. US2 → lineage chains → validate → demo.
4. US3 → join-ready identifiers → validate → demo.
5. Polish → determinism + precision/recall evidence + docs.

### Parallel Team Strategy

Once Foundational is done, one developer can take US1; after US1 detectors land, US2 and US3 can
proceed in parallel (US3 only needs the detectors, US2 needs node emission).

---

## Notes

- [P] = different files, no dependency on an incomplete task.
- Every detector task is paired with a positive + negative test (Principle II).
- Verify tests fail before implementing.
- Recipe MUST NOT modify source and MUST emit sorted, deterministic output (Principle IV).
- Commit after each task or logical group; stop at any checkpoint to validate a story independently.
