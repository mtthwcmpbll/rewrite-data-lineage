# Contract: Recipes (invocation interface)

The recipe surface a user activates on the Moderne platform / OpenRewrite CLI. The MVP ships one
top-level recipe; the per-framework detectors are internal collaborators of the scanning recipe, not
separately-activatable recipes (they share accumulator state).

## Recipe: `com.snowfort.recipe.lineage.FindHttpDataLineage`

- **Type**: `ScanningRecipe` (two-phase: scan accumulates `MethodFlowFacts`, generate emits tables).
- **Display name**: "Find Spring Boot HTTP data lineage"
- **Description**: "Catalog inbound Spring MVC endpoints and outbound RestTemplate/WebClient calls as
  HTTP data nodes, and trace request data from an endpoint to an outbound call across method
  boundaries within the repository. Emits the `HttpDataNodes` and `DataFlowChains` data tables. Does
  not modify source."
- **Options (MVP)**: none required. (Reserved for future: `includePartialRoutes`, `minConfidence`.)
- **Produces**: `HttpDataNodes`, `DataFlowChains` (see `data-tables.md`).
- **Modifies source**: No (Principle IV). Returns the tree unchanged.
- **Prerequisites**: typed Java LST with Spring web types resolvable on the parser classpath (R7).

### Behavioral contract (acceptance-aligned)

| # | Given | Then |
|---|-------|------|
| C1 | A `@RestController` handler with a `@RequestBody` param | Exactly one `HttpDataNodes` SOURCE row for the handler, `SPRING_MVC`, route+method from the mapping, payload = body type; extra params (path/query/header) seed taint but add no rows. |
| C2 | A `RestTemplate.postForObject(url, body, …)` call | One `HttpDataNodes` SINK row, `REST_TEMPLATE`, route from `url`, payload = body type. |
| C3 | A `WebClient …uri(u)…bodyValue(b)…retrieve()` chain | One `HttpDataNodes` SINK row, `WEB_CLIENT`, route from `u`, payload = `b` type. |
| C4 | Handler passes its `@RequestBody` through a service method into an outbound call | `DataFlowChains` rows linking the SOURCE nodeId to the SINK nodeId across the intervening methods. |
| C5 | An outbound call whose args are only local constants | SINK row present (C2/C3) but **no** `DataFlowChains` row to it. |
| C6 | A plain non-HTTP method | No rows in either table. |
| C7 | Dynamically-built outbound URL | SINK row with `routeResolution = PARTIAL` or `UNKNOWN`, never a guessed literal. |
| C8 | Two runs over identical source | Byte-identical table output. |

Each row of this table maps to at least one `RewriteTest` (Principle II); C1/C6, C2/C6, C5 supply the
required positive+negative pairs per detector.
