# Quickstart: Validate Spring Boot HTTP Data Lineage (MVP)

This guide proves the feature works end-to-end. It is a validation/run guide — implementation lives in
`tasks.md` and the source tree. Contracts referenced here: `contracts/data-tables.md`,
`contracts/recipes.md`; schema in `data-model.md`.

## Prerequisites

- JDK 17+ and the repo's Gradle wrapper (`./gradlew`).
- Spring web types on the test/parser classpath — `spring-web` and `spring-webflux` added to
  `build.gradle.kts` (research R7). Without them, fixtures don't type-resolve and detectors silently
  no-op.

## Build & test

```bash
./gradlew build        # compiles recipes + runs RewriteTest suite (Principle II gate)
./gradlew test         # tests only
```

Expected: green build; every detector has a passing positive and negative test; end-to-end test
asserts both data tables.

## Validation scenario 1 — Catalog (User Story 1)

**Fixture**: a `@RestController` with one `@PostMapping("/orders")` handler taking a `@RequestBody
Order`, and a `RestTemplate.postForObject("http://inventory/reserve", order, …)` call in a service.

**Run**: the `FindHttpDataLineage` recipe under `RewriteTest`, asserting `HttpDataNodes` rows.

**Expected** (per `contracts/data-tables.md`):
- A SOURCE row: `framework=SPRING_MVC`, `httpMethod=POST`, `routeTemplate=/orders`,
  `payloadType=…​.Order`, `routeResolution=EXACT`.
- A SINK row: `framework=REST_TEMPLATE`, `httpMethod=POST`, `routeTemplate=/reserve` (or the
  normalized target), `payloadType=…​.Order`.
- A plain helper method in the same fixture yields **no** rows (negative assertion, C6).

## Validation scenario 2 — Lineage across methods (User Story 2)

**Fixture**: controller receives `@RequestBody Order`, calls `orderService.forward(order)`, which
calls `RestTemplate.postForObject(url, order, …)` (controller → service → client).

**Expected** (per C4): `DataFlowChains` rows linking the SOURCE `nodeId` to the SINK `nodeId`, with an
edge for `controller→forward` and `forward→postForObject`, `edgeIndex` ordered, `taintedArgPositions`
identifying the argument carrying `order`.

**Negative** (C5): a second outbound call `postForObject(url, new Heartbeat(), …)` fed only by a local
constant produces its SINK catalog row but **no** chain row.

## Validation scenario 3 — Join-ready identifiers (User Story 3)

**Fixture**: caller service does `WebClient…uri("/orders/{id}", id)…retrieve()`; a separate controller
fixture exposes `@GetMapping("/orders/{id}")`.

**Expected** (per SC-006): the outbound SINK's `(httpMethod=GET, routeTemplate=/orders/{id})` equals
the inbound SOURCE's identifier — demonstrated by comparing the two emitted rows. No cross-repo join is
performed by the recipe; this only confirms the keys match.

## Validation scenario 4 — Determinism (SC-004)

Run the end-to-end recipe twice over the identical fixture; assert both table outputs are
byte-identical (C8).

## Success signals

- All four scenarios pass under `RewriteTest`.
- Success criteria SC-001…SC-006 in `spec.md` are demonstrated by scenarios 1–4 plus the recall/
  precision fixture set in `tasks.md`.
- No fixture is modified by the recipe (Principle IV).
