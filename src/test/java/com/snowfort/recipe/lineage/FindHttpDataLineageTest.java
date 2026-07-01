package com.snowfort.recipe.lineage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

/**
 * End-to-end catalog tests for {@link FindHttpDataLineage} (User Story 1). Uses single-argument
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
}
