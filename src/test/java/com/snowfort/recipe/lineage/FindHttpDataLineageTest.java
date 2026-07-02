package com.snowfort.recipe.lineage;

import com.snowfort.recipe.lineage.table.DataFlowChainTable;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

/**
 * End-to-end tests for {@link FindHttpDataLineage} (User Stories 1 &amp; 2). Uses single-argument
 * {@code java(...)} fixtures, which assert the recipe makes <em>no source changes</em> — the explicit
 * check for FR-011 / constitution Principle IV.
 */
class FindHttpDataLineageTest extends LineageRecipeTest {

    @Test
    void catalogsSourcesAndSinksWithUnknownPayloadMarker() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    assertThat(nodeRows(run)).hasSize(3);
                    assertThat(nodeRows(run)).filteredOn(r -> r.getDirection().equals("SOURCE")).hasSize(2);
                    assertThat(nodeRows(run)).filteredOn(r -> r.getDirection().equals("SINK")).hasSize(1);
                    // The parameterless endpoint has no resolvable payload -> explicit unknown marker (FR-009, SC-002).
                    assertThat(nodeRows(run)).anySatisfy(r -> {
                        assertThat(r.getRouteTemplate()).isEqualTo("/health");
                        assertThat(r.getPayloadType()).isEqualTo("<unknown>");
                        assertThat(r.isPayloadResolved()).isFalse();
                    });
                    // Every row carries a populated route resolution and payload marker (SC-002).
                    assertThat(nodeRows(run)).allSatisfy(r -> {
                        assertThat(r.getRouteResolution()).isNotBlank();
                        assertThat(r.getPayloadType()).isNotBlank();
                    });
                }),
                java(
                        """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;
                        import org.springframework.web.client.RestTemplate;

                        @RestController
                        class OrderController {
                            @PostMapping("/orders")
                            String create(@RequestBody Order order) {
                                return "ok";
                            }

                            @GetMapping("/health")
                            String health() {
                                return "up";
                            }
                        }

                        class OrderClient {
                            private final RestTemplate rest = new RestTemplate();
                            void send(Order o) {
                                rest.postForObject("http://inventory/reserve", o, String.class);
                            }
                        }
                        class Order {}
                        """
                )
        );
    }

    @Test
    void chainsAreOrderedAndReferentiallyIntactWithNodes() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    // Invariant I1: every chain endpoint resolves to a catalogued node row.
                    Set<String> nodeIds = nodeRows(run).stream()
                            .map(r -> r.getNodeId()).collect(Collectors.toSet());
                    assertThat(chainRows(run)).isNotEmpty();
                    assertThat(chainRows(run)).allSatisfy(c -> {
                        assertThat(nodeIds).contains(c.getSourceNodeId());
                        assertThat(nodeIds).contains(c.getSinkNodeId());
                    });
                    // Edge indices for a single (source, sink) chain are a contiguous 0..n run.
                    assertThat(chainRows(run)).extracting(DataFlowChainTable.Row::getEdgeIndex)
                            .containsExactly(0, 1);
                    // Deterministic sort key holds: rows are ordered by (source, sink, edgeIndex).
                    assertThat(chainRows(run)).isSortedAccordingTo(
                            java.util.Comparator.comparing(DataFlowChainTable.Row::getSourceNodeId)
                                    .thenComparing(DataFlowChainTable.Row::getSinkNodeId)
                                    .thenComparingInt(DataFlowChainTable.Row::getEdgeIndex));
                }),
                java(
                        """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;
                        import org.springframework.web.client.RestTemplate;

                        @RestController
                        class GatewayController {
                            private final DownstreamService service = new DownstreamService();
                            @PostMapping("/gateway")
                            String gateway(@RequestBody Order order) {
                                return service.push(order);
                            }
                        }

                        class DownstreamService {
                            private final RestTemplate rest = new RestTemplate();
                            String push(Order o) {
                                return rest.postForObject("http://inventory/reserve", o, String.class);
                            }
                        }
                        class Order {}
                        """
                )
        );
    }
}
