package com.snowfort.recipe.lineage.flow;

import com.snowfort.recipe.lineage.LineageRecipeTest;
import com.snowfort.recipe.lineage.table.HttpDataNodeTable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

/**
 * Integration tests for User Story 2 — inter-procedural chains. Proves the canonical
 * controller &rarr; service &rarr; client taint is traced across method boundaries as an ordered
 * per-hop {@link com.snowfort.recipe.lineage.model.CallChainEdge} sequence, and that a constant-fed
 * outbound call produces no chain (FR-007).
 */
class HttpFlowChainTest extends LineageRecipeTest {

    @Test
    void tracesRequestBodyThroughServiceToOutboundCall() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    HttpDataNodeTable.Row source = nodeRows(run).stream()
                            .filter(r -> r.getDirection().equals("SOURCE")).findFirst().orElseThrow();
                    HttpDataNodeTable.Row sink = nodeRows(run).stream()
                            .filter(r -> r.getDirection().equals("SINK")).findFirst().orElseThrow();
                    // Two ordered edges: controller.create -> service.forward -> rest.postForObject.
                    assertThat(chainRows(run)).hasSize(2);
                    assertThat(chainRows(run)).allSatisfy(c -> {
                        assertThat(c.getSourceNodeId()).isEqualTo(source.getNodeId());
                        assertThat(c.getSinkNodeId()).isEqualTo(sink.getNodeId());
                    });
                    assertThat(chainRows(run)).anySatisfy(c -> {
                        assertThat(c.getEdgeIndex()).isZero();
                        assertThat(c.getFromMethodFqn()).isEqualTo("com.example.OrderController#create");
                        assertThat(c.getToMethodFqn()).isEqualTo("com.example.OrderService#forward");
                        // 'order' is argument index 0 of forward(order).
                        assertThat(c.getTaintedArgPositions()).isEqualTo("0");
                    });
                    assertThat(chainRows(run)).anySatisfy(c -> {
                        assertThat(c.getEdgeIndex()).isEqualTo(1);
                        assertThat(c.getFromMethodFqn()).isEqualTo("com.example.OrderService#forward");
                        assertThat(c.getToMethodFqn())
                                .isEqualTo("org.springframework.web.client.RestTemplate#postForObject");
                        // 'o' is argument index 1 of postForObject(url, body, responseType).
                        assertThat(c.getTaintedArgPositions()).isEqualTo("1");
                    });
                }),
                java(
                        """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;
                        import org.springframework.web.client.RestTemplate;

                        @RestController
                        class OrderController {
                            private final OrderService service = new OrderService();
                            @PostMapping("/orders")
                            String create(@RequestBody Order order) {
                                return service.forward(order);
                            }
                        }

                        class OrderService {
                            private final RestTemplate rest = new RestTemplate();
                            String forward(Order o) {
                                return rest.postForObject("http://inventory/reserve", o, String.class);
                            }
                        }
                        class Order {}
                        """
                )
        );
    }

    @Test
    void emitsNoChainForConstantFedOutboundCall() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    // Source and sink are both cataloged...
                    assertThat(nodeRows(run)).hasSize(2);
                    // ...but the outbound body is a locally-constructed constant, so no taint connects them.
                    assertThat(chainRows(run)).isEmpty();
                }),
                java(
                        """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;
                        import org.springframework.web.client.RestTemplate;

                        @RestController
                        class OrderController {
                            private final RestTemplate rest = new RestTemplate();
                            @PostMapping("/orders")
                            String create(@RequestBody Order order) {
                                return rest.postForObject("http://inventory/reserve", new Order(), String.class);
                            }
                        }
                        class Order {}
                        """
                )
        );
    }
}
