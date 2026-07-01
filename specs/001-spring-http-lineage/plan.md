# Implementation Plan: Spring Boot HTTP Data Lineage (MVP)

**Branch**: `001-spring-http-lineage` | **Date**: 2026-07-01 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-spring-http-lineage/spec.md`

## Summary

Build an OpenRewrite recipe that, for a single Spring Boot repository, (1) catalogs every inbound
HTTP entry point (Spring MVC controller handlers receiving external data) and every outbound HTTP
call (`RestTemplate` / `WebClient`) as structured **HTTP data node** rows, and (2) connects an
inbound source to an outbound sink — tracing the payload across method and class boundaries within
the repo — as **data flow chain** rows. Both outputs are emitted as OpenRewrite data tables, are
deterministic, and carry cross-repo-joinable external identifiers (normalized route + HTTP method).

Technical approach: a single `ScanningRecipe` that walks the whole-repo LST. The **scan phase**
accumulates, per method: contained HTTP sources, contained HTTP sinks, and outgoing method calls
(with argument positions) plus intra-procedural taint edges computed via `rewrite-analysis`
`Dataflow`/`Taint`. The **generate phase** builds a repo-local call graph from the accumulated call
edges, propagates taint across method boundaries to find source→sink reachability, and emits both
data tables. No source is modified.

## Technical Context

**Language/Version**: Java 17 (recipe source); analyzes Java 8–21 target sources (test runtimes
17/21/25 already configured).

**Primary Dependencies**: OpenRewrite — `rewrite-java` (LST, `MethodMatcher`, `TypeUtils`,
`AnnotationMatcher`), `org.openrewrite.meta:rewrite-analysis` (`Dataflow`/`Taint`, already on the
classpath), OpenRewrite `DataTable` API for output. `rewrite-test` + AssertJ for tests.

**Storage**: N/A. Output is emitted through OpenRewrite data tables (columnar rows surfaced by the
CLI / Moderne platform); no database or file writes by the recipe.

**Testing**: `RewriteTest` (JUnit 5) with in-memory Java fixtures and data-table assertions
(`dataTable(...)` / `dataTableAsCsv(...)`). Positive + negative fixtures per detector.

**Target Platform**: Moderne platform and the OpenRewrite CLI / Gradle / Maven plugin. Must run
under `RewriteTest` without the Moderne platform (so no dependency on platform-only call graph).

**Project Type**: OpenRewrite recipe library — single Gradle module (`com.snowfort.recipe:rewrite-data-lineage`).

**Performance Goals**: Analysis-time, not latency-bound. Single pass over the repo LST (one scan +
one generate). Must scale to a realistic service (thousands of methods) without super-linear blowup;
call-graph reachability bounded to source-reachable / sink-reachable slices.

**Constraints**: Non-destructive (no LST edits); deterministic and order-stable output (rows sorted
before emission); unresolved types/routes recorded with explicit unknown/partial markers, never
dropped.

**Scale/Scope**: MVP = Spring MVC annotated controllers (inbound) + `RestTemplate`/`WebClient`
(outbound), single repository, call/value-level taint across methods within the repo. No cross-repo
join, no non-HTTP primitives, no semantic transform classification, no WebFlux/Feign.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Gates derived from the project constitution (v1.0.0), Principles I–V:

| Principle | Gate | Status |
|-----------|------|--------|
| I. Recipe-First, LST-Native | All detection via type-aware primitives (`MethodMatcher`, `AnnotationMatcher`, `TypeUtils`, `rewrite-analysis` `Taint`); no text/regex over source; output only via `DataTable`. | ✅ PASS — design uses matchers + Taint + ScanningRecipe over LST; data-table-only output. |
| II. Test-First (NON-NEGOTIABLE) | Every source/sink/chain detector has positive + negative `RewriteTest`; data-table rows asserted, not just "recipe ran". | ✅ PASS — plan mandates paired fixtures per detector and row-level assertions (see quickstart.md). |
| III. Stable Schemas | `DataFlowNode`, `CallChainEdge`, `ExternalIdentifier` fixed as a versioned contract; chain extraction independent of any transform kinds; cross-repo join keys populated. | ✅ PASS — schemas in data-model.md / contracts/; no transform coupling in MVP. |
| IV. Deterministic, Non-Destructive | Recipe never edits source; output order-stable (sorted); confidence/unknowns marked as data. | ✅ PASS — ScanningRecipe emits only; deterministic sort + unknown/partial markers specified. |
| V. Phased & Closed-Taxonomy | Scope is a subset of design Phases 1–3 + 5 (schemas, HTTP source/sink catalog, intra+inter taint, raw chain); no speculative taxonomy; reuse framework matchers. | ✅ PASS — no transform rows/taxonomy in MVP; existing matchers reused. |

**Result**: All gates pass. No violations → Complexity Tracking is empty.

## Project Structure

### Documentation (this feature)

```text
specs/001-spring-http-lineage/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output — decisions & rationale
├── data-model.md        # Phase 1 output — DataFlowNode, CallChainEdge, ExternalIdentifier
├── quickstart.md        # Phase 1 output — how to validate the recipe end-to-end
├── contracts/
│   ├── data-tables.md   # Data-table column contracts (the recipe's output interface)
│   └── recipes.md       # Recipe IDs, display names, options (the recipe's invocation interface)
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
src/main/java/com/snowfort/recipe/lineage/
├── model/
│   ├── DataFlowNode.java        # source/sink node record (+ Direction, HttpMethod enums)
│   ├── ExternalIdentifier.java  # normalized route + HTTP method (cross-repo join key)
│   └── CallChainEdge.java       # one edge on a source→sink path
├── table/
│   ├── HttpDataNodeTable.java   # DataTable: one row per catalogued source/sink
│   └── DataFlowChainTable.java  # DataTable: one row per source→sink chain edge
├── source/
│   └── SpringMvcSource.java     # inbound endpoint detection (@*Mapping handlers + param sources)
├── sink/
│   ├── RestTemplateSink.java    # RestTemplate outbound-call detection
│   └── WebClientSink.java       # WebClient outbound-call detection
├── flow/
│   ├── MethodFlowFacts.java     # per-method accumulator (sources, sinks, call edges, taint edges)
│   ├── LocalTaint.java          # intra-procedural taint via rewrite-analysis
│   └── CallGraphReachability.java # repo-local inter-procedural propagation
└── FindHttpDataLineage.java     # top-level ScanningRecipe wiring it together

src/test/java/com/snowfort/recipe/lineage/
├── source/SpringMvcSourceTest.java
├── sink/RestTemplateSinkTest.java
├── sink/WebClientSinkTest.java
├── flow/CallGraphReachabilityTest.java
└── FindHttpDataLineageTest.java  # end-to-end: controller→service→client fixtures
```

**Structure Decision**: Single OpenRewrite recipe module (the existing repo). New code lives under a
new base package `com.snowfort.recipe.lineage`, split by responsibility: `model` (the serializable
schema records — the Principle III contract), `table` (data-table definitions — the output
interface), `source`/`sink` (framework-specific detectors — the Principle II test seams), `flow`
(dataflow + repo-local call graph), and the top-level `FindHttpDataLineage` `ScanningRecipe` that
orchestrates. This keeps each detector independently testable (Principle II) and keeps chain
extraction decoupled from node detection (Principle III).

## Complexity Tracking

> No constitution violations. Section intentionally empty.
