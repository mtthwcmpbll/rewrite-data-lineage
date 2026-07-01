<!--
SYNC IMPACT REPORT
==================
Version change: (uninitialized template) → 1.0.0
Bump rationale: Initial ratification of the project constitution (MAJOR baseline).

Principles defined:
  I.   Recipe-First, LST-Native Analysis
  II.  Test-First Recipe Development (NON-NEGOTIABLE)
  III. Stable Schemas as the Interface Contract
  IV.  Deterministic, Non-Destructive Analysis
  V.   Phased Delivery & Closed-Taxonomy Discipline

Added sections:
  - Engineering Constraints
  - Development Workflow & Quality Gates
  - Governance

Templates requiring updates:
  ✅ .specify/templates/plan-template.md   — Constitution Check gate aligns with Principles I–V
  ✅ .specify/templates/spec-template.md   — no constitution-specific gates required; no change needed
  ✅ .specify/templates/tasks-template.md  — task categories compatible with test-first + schema-first flow

Follow-up TODOs: none. Ratification date set to first adoption (2026-07-01).
-->

# Rewrite Data Lineage Constitution

This project builds OpenRewrite/Moderne recipes that extract data lineage across repositories:
identifying sources, transforms, and sinks; propagating taint into a raw call chain; and
composing that chain into human-readable semantic summaries. The principles below are the
non-negotiable rules that govern how those recipes are designed, built, and evolved.

## Core Principles

### I. Recipe-First, LST-Native Analysis

All analysis MUST be delivered as OpenRewrite recipes operating over the Lossless Semantic Tree
(LST). Every capability — source/sink identification, dataflow, call-graph propagation, idiom
detection, summarization — is a recipe or recipe family, not an out-of-band script.

- Symbol and type resolution MUST go through type-aware primitives (`MethodMatcher`,
  `TypeUtils`, `rewrite-analysis` `Dataflow`/`Taint`, the Moderne call graph). Raw text/regex
  matching over source is prohibited for anything the type system can answer.
- Recipes MUST follow OpenRewrite recipe best practices (single-responsibility visitors,
  declarative composition where possible, `getDisplayName`/`getDescription` on every recipe).
- Analysis output is emitted through OpenRewrite **data tables**, never printed or written to
  ad-hoc files.

**Rationale:** LST-native analysis is the only approach that scales across repos on Moderne and
stays correct as code changes underneath. Text heuristics silently rot; type-aware recipes do not.

### II. Test-First Recipe Development (NON-NEGOTIABLE)

Every recipe MUST have `RewriteTest` coverage written before or alongside the implementation, and
that coverage MUST assert on real behavior.

- Each source/sink/transform detector MUST have at least one positive test (it fires) and one
  negative test (it does not fire on look-alike code).
- Recipes that emit data tables MUST assert on emitted rows, not merely that the recipe runs.
- Tests MUST use realistic framework fixtures (Spring MVC, Kafka, JDBC, etc.) so detectors are
  exercised against the idioms they claim to recognize.
- No detector is considered "done" until its false-positive and false-negative behavior is pinned
  by tests.

**Rationale:** Lineage analysis is only trustworthy if its precision and recall are pinned. A
detector without a negative test is an unbounded source of false positives in downstream graphs.

### III. Stable Schemas as the Interface Contract

The serializable output schemas — `DataFlowNode`, `CallChainEdge`, transform row, and library
summary card — are the contract every stage depends on. They MUST be treated as a versioned API.

- The raw call chain and the semantic summary are **decoupled**: chain extraction MUST NOT depend
  on transform kinds, and summarization MUST consume the chain as input rather than re-deriving it.
- Cross-repo joins rely on `externalIdentifier` and `(library-coordinates, method, version)`;
  producers of nodes MUST populate these keys so downstream assembly can join without re-analysis.
- Schema changes MUST be deliberate and versioned. A breaking field change is a MAJOR-level event
  for any consumer and MUST be called out in review.

**Rationale:** The design's core bet is that a stable chain lets summarization strategies evolve
independently. That only holds if the schemas are stable and the two output streams stay separable.

### IV. Deterministic, Non-Destructive Analysis

Lineage recipes are analyses, not migrations. A run MUST produce the same output for the same
input and MUST NOT mutate the code under analysis.

- Analysis recipes MUST NOT edit source. Source-modifying recipes are permitted ONLY for the
  explicit annotation-accelerator track (`@DataSource`/`@DataSink`/`@DataTransform`) and MUST be
  clearly separated from analysis recipes.
- Output MUST be deterministic and order-stable so results are diffable across re-runs; any
  unavoidable nondeterminism (e.g. iteration order) MUST be normalized before emission.
- Confidence MUST be represented as data, not dropped. Heuristic/low-confidence findings
  (long-tail libraries, method-name inference) MUST be marked so the display layer can flag them —
  they are never silently promoted to the same status as type-verified findings.

**Rationale:** Consumers audit, diff, and build graphs on this output. Nondeterminism or silent
mutation makes it unusable for its primary purposes.

### V. Phased Delivery & Closed-Taxonomy Discipline

Work follows the phased plan (schemas → source/sink catalog → intra-procedural dataflow → idiom
detection → call chain → library summaries → cross-repo joins → SQL → summarization). Each phase
MUST leave the system in a working, tested state that a later phase can build on.

- The transformation taxonomy is **closed**. `unclassified` is a valid, first-class output. New
  taxonomy entries are added ONLY when `unclassified` clusters into a recognizable, evidenced
  pattern — never speculatively.
- Prefer reusing and normalizing existing inventory recipes over writing new detectors from
  scratch; the work is usually schema normalization, not greenfield analysis.
- Complexity beyond the current phase's needs MUST be justified against a concrete, present
  requirement (YAGNI). Leave documented hooks for deferred work (e.g. field-level tracking)
  rather than building it early.

**Rationale:** A sprawling taxonomy and premature generality are the two failure modes that make
lineage tools unmaintainable. Phased, evidence-driven growth keeps the vocabulary legible.

## Engineering Constraints

- **Platform:** OpenRewrite recipes targeting the Moderne platform; Java, built with Gradle
  (`./gradlew`) and Maven (`./mvnw`) as configured in this repo. Published under
  `com.snowfort.recipe:rewrite-data-lineage`.
- **Dependencies:** Analysis capabilities SHOULD be built on `rewrite-analysis` (Dataflow/Taint)
  and the Moderne call graph rather than reimplemented. New third-party dependencies MUST be
  justified in review.
- **Reproducibility:** Recipes MUST run on the standard Moderne/OpenRewrite build path. Any recipe
  requiring LST or call-graph readiness MUST document that prerequisite.
- **Documentation:** Design decisions that change the model or schemas MUST be reflected in
  `docs/DESIGN.md` in the same change.

## Development Workflow & Quality Gates

- **Constitution Check:** Every implementation plan MUST pass the Constitution Check gate derived
  from Principles I–V before task generation. Violations MUST be recorded and justified in the
  plan's Complexity Tracking section or the plan MUST be revised.
- **Review:** Every change MUST verify: recipes are LST-native (I), have positive + negative tests
  (II), preserve schema decoupling and cross-repo keys (III), are non-destructive and deterministic
  (IV), and respect taxonomy/phase discipline (V).
- **Green build:** `./gradlew build` (recipe compilation + `RewriteTest`s) MUST pass before merge.
- **Schema changes:** Any change to an output schema MUST be flagged explicitly in the PR
  description and reviewed for downstream-consumer impact.

## Governance

This constitution supersedes ad-hoc practice for the Rewrite Data Lineage project. When guidance
conflicts, the constitution wins.

- **Amendments** MUST be made by editing this file, MUST include an updated Sync Impact Report, and
  MUST propagate any required changes into `.specify/templates/` and affected docs in the same
  change.
- **Versioning** follows semantic versioning: MAJOR for removing or redefining a principle in a
  backward-incompatible way; MINOR for adding a principle or materially expanding guidance; PATCH
  for clarifications and non-semantic refinements.
- **Compliance** is verified at plan time (Constitution Check gate) and at review time. Complexity
  that violates a principle MUST be justified against a concrete requirement or removed.
- **Runtime guidance** for agents and contributors lives in `CLAUDE.md` and `docs/DESIGN.md`; those
  documents MUST stay consistent with the principles here.

**Version**: 1.0.0 | **Ratified**: 2026-07-01 | **Last Amended**: 2026-07-01
