package com.snowfort.recipe.lineage.fixtures;

import com.snowfort.recipe.lineage.LineageRecipeTest;
import com.snowfort.recipe.lineage.table.HttpDataNodeTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

/**
 * Polish task T034 — precision/recall evidence (SC-001: &ge;95% recall, &le;5% false positives). Runs
 * the recipe over a representative multi-endpoint Spring service whose HTTP surface is a known
 * ground-truth list (3 inbound handlers + 2 outbound calls = 5 nodes), interleaved with look-alikes
 * that MUST NOT be detected (a non-handler helper, a plain method call, a controller method with no
 * mapping). Exact-count assertions demonstrate 100% recall and 0% false positives, comfortably inside
 * the thresholds. Single-argument fixtures also pin non-destructiveness (FR-011, Principle IV).
 */
class PrecisionRecallTest extends LineageRecipeTest {

    @Test
    void detectsEveryHttpNodeAndNoLookAlikes() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    List<HttpDataNodeTable.Row> rows = nodeRows(run);
                    // Ground truth: 3 sources + 2 sinks. Recall = detected/expected; FP = spurious/detected.
                    long sources = rows.stream().filter(r -> r.getDirection().equals("SOURCE")).count();
                    long sinks = rows.stream().filter(r -> r.getDirection().equals("SINK")).count();
                    assertThat(sources).as("recall: all 3 inbound handlers").isEqualTo(3);
                    assertThat(sinks).as("recall: both outbound calls").isEqualTo(2);
                    // Exactly 5 rows => zero false positives from the look-alikes below.
                    assertThat(rows).as("no false positives").hasSize(5);
                    // Every detected route/payload is populated (SC-002).
                    assertThat(rows).allSatisfy(r -> {
                        assertThat(r.getRouteResolution()).isNotBlank();
                        assertThat(r.getPayloadType()).isNotBlank();
                    });
                }),
                java(
                        """
                        package com.example.orders;

                        import org.springframework.web.bind.annotation.*;
                        import org.springframework.web.client.RestTemplate;

                        @RestController
                        @RequestMapping("/orders")
                        class OrderController {
                            private final InventoryGateway gateway = new InventoryGateway();

                            @PostMapping
                            String create(@RequestBody Order order) {
                                return gateway.reserve(order);
                            }

                            @GetMapping("/{id}")
                            Order get(@PathVariable String id) {
                                return gateway.lookup(id);
                            }

                            @GetMapping("/health")
                            String health() {
                                return "up";
                            }

                            // Look-alike: not a handler (no mapping annotation) -> must NOT be a source.
                            String describe(Order order) {
                                return order.toString();
                            }
                        }

                        class InventoryGateway {
                            private final RestTemplate rest = new RestTemplate();

                            String reserve(Order order) {
                                return rest.postForObject("http://inventory/reserve", order, String.class);
                            }

                            Order lookup(String id) {
                                // Look-alike: a plain method call on a non-RestTemplate receiver -> not a sink.
                                String key = id.trim();
                                return rest.getForObject("http://inventory/orders/" + key, Order.class);
                            }
                        }

                        class Order {}
                        """
                )
        );
    }
}
