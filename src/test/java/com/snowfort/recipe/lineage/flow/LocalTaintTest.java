package com.snowfort.recipe.lineage.flow;

import com.snowfort.recipe.lineage.LineageRecipeTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

/**
 * User Story 2, task T022 — intra-procedural taint. Within a single handler method, a
 * {@code @RequestBody} value that reaches {@code postForObject} is detected as a (single-edge) taint
 * chain, and an outbound argument that carries no request data yields no chain. Also covers simple
 * local aliasing ({@code var copy = order; ... postForObject(url, copy, ...)}).
 */
class LocalTaintTest extends LineageRecipeTest {

    @Test
    void requestBodyReachingOutboundCallInSameMethodIsATaintEdge() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> assertThat(chainRows(run)).singleElement().satisfies(c -> {
                    assertThat(c.getEdgeIndex()).isZero();
                    assertThat(c.getFromMethodFqn()).isEqualTo("com.example.OrderController#create");
                    assertThat(c.getToMethodFqn())
                            .isEqualTo("org.springframework.web.client.RestTemplate#postForObject");
                    // 'order' is argument index 1 of postForObject(url, body, responseType).
                    assertThat(c.getTaintedArgPositions()).isEqualTo("1");
                })),
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
                                return rest.postForObject("http://inventory/reserve", order, String.class);
                            }
                        }
                        class Order {}
                        """
                )
        );
    }

    @Test
    void aliasedRequestBodyIsStillTainted() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> assertThat(chainRows(run)).singleElement().satisfies(c ->
                        assertThat(c.getTaintedArgPositions()).isEqualTo("1"))),
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
                                Order copy = order;
                                return rest.postForObject("http://inventory/reserve", copy, String.class);
                            }
                        }
                        class Order {}
                        """
                )
        );
    }

    @Test
    void untaintedOutboundArgumentYieldsNoChain() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    // Source + sink are cataloged, but the body is a local constant -> no taint edge.
                    assertThat(nodeRows(run)).hasSize(2);
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
