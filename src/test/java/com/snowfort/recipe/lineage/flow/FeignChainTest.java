package com.snowfort.recipe.lineage.flow;

import com.snowfort.recipe.lineage.LineageRecipeTest;
import com.snowfort.recipe.lineage.table.HttpDataNodeTable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

/**
 * User Story 2 for Feign — tracing request data <em>into</em> a Feign client call site. The outbound
 * Feign endpoint is cataloged at its {@code @FeignClient} declaration; a call site invoking it is
 * promoted to a chain sink so the chain terminates at that declaration node. Covers single-hop,
 * multi-hop (controller &rarr; service &rarr; client call), and the constant-fed negative (FR-007).
 */
class FeignChainTest extends LineageRecipeTest {

    // A controller that receives a @RequestBody and forwards it through a Feign client, plus the
    // @FeignClient interface it calls. Shared across the fixtures below via string interpolation.
    private static final String FRAUD_CLIENT =
            """
            package com.example;
            import org.springframework.cloud.openfeign.FeignClient;
            import org.springframework.web.bind.annotation.*;

            @FeignClient(name = "fraud-service", url = "http://fraud")
            interface FraudClient {
                @PostMapping("/api/fraud/check")
                String checkFraud(@RequestBody Order order);
            }
            class Order {}
            """;

    @Test
    void tracesRequestBodyDirectlyIntoFeignCall() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    HttpDataNodeTable.Row source = nodeRows(run).stream()
                            .filter(r -> r.getDirection().equals("SOURCE")).findFirst().orElseThrow();
                    HttpDataNodeTable.Row feignSink = nodeRows(run).stream()
                            .filter(r -> r.getFramework().equals("FEIGN")).findFirst().orElseThrow();
                    assertThat(chainRows(run)).singleElement().satisfies(c -> {
                        assertThat(c.getSourceNodeId()).isEqualTo(source.getNodeId());
                        assertThat(c.getSinkNodeId()).isEqualTo(feignSink.getNodeId());
                        assertThat(c.getEdgeIndex()).isZero();
                        assertThat(c.getFromMethodFqn()).isEqualTo("com.example.OrderController#create");
                        assertThat(c.getToMethodFqn()).isEqualTo("com.example.FraudClient#checkFraud");
                        // 'order' is argument index 0 of checkFraud(order).
                        assertThat(c.getTaintedArgPositions()).isEqualTo("0");
                    });
                }),
                java(FRAUD_CLIENT),
                java(
                        """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;

                        @RestController
                        class OrderController {
                            private final FraudClient fraudClient;
                            OrderController(FraudClient fraudClient) { this.fraudClient = fraudClient; }

                            @PostMapping("/orders")
                            String create(@RequestBody Order order) {
                                return fraudClient.checkFraud(order);
                            }
                        }
                        """
                )
        );
    }

    @Test
    void tracesRequestBodyThroughServiceIntoFeignCall() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    // Two ordered edges: controller.create -> service.forward -> fraudClient.checkFraud.
                    assertThat(chainRows(run)).hasSize(2);
                    assertThat(chainRows(run)).anySatisfy(c -> {
                        assertThat(c.getEdgeIndex()).isZero();
                        assertThat(c.getFromMethodFqn()).isEqualTo("com.example.OrderController#create");
                        assertThat(c.getToMethodFqn()).isEqualTo("com.example.OrderService#forward");
                    });
                    assertThat(chainRows(run)).anySatisfy(c -> {
                        assertThat(c.getEdgeIndex()).isEqualTo(1);
                        assertThat(c.getFromMethodFqn()).isEqualTo("com.example.OrderService#forward");
                        assertThat(c.getToMethodFqn()).isEqualTo("com.example.FraudClient#checkFraud");
                        assertThat(c.getTaintedArgPositions()).isEqualTo("0");
                    });
                }),
                java(FRAUD_CLIENT),
                java(
                        """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;

                        @RestController
                        class OrderController {
                            private final OrderService service;
                            OrderController(OrderService service) { this.service = service; }

                            @PostMapping("/orders")
                            String create(@RequestBody Order order) {
                                return service.forward(order);
                            }
                        }
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.springframework.stereotype.Service;

                        @Service
                        class OrderService {
                            private final FraudClient fraudClient;
                            OrderService(FraudClient fraudClient) { this.fraudClient = fraudClient; }

                            String forward(Order o) {
                                return fraudClient.checkFraud(o);
                            }
                        }
                        """
                )
        );
    }

    @Test
    void constantFedFeignCallYieldsNoChain() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    // The Feign endpoint is still cataloged as a SINK...
                    assertThat(nodeRows(run)).filteredOn(r -> r.getFramework().equals("FEIGN")).hasSize(1);
                    // ...but the outbound argument is a fresh local, so no request data connects to it.
                    assertThat(chainRows(run)).isEmpty();
                }),
                java(FRAUD_CLIENT),
                java(
                        """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;

                        @RestController
                        class OrderController {
                            private final FraudClient fraudClient;
                            OrderController(FraudClient fraudClient) { this.fraudClient = fraudClient; }

                            @PostMapping("/orders")
                            String create(@RequestBody Order order) {
                                return fraudClient.checkFraud(new Order());
                            }
                        }
                        """
                )
        );
    }
}
