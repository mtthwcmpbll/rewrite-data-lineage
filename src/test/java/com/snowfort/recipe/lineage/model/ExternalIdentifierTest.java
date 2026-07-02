package com.snowfort.recipe.lineage.model;

import com.snowfort.recipe.lineage.LineageRecipeTest;
import com.snowfort.recipe.lineage.table.HttpDataNodeTable;
import org.junit.jupiter.api.Test;
import org.openrewrite.test.TypeValidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

/**
 * User Story 3, task T029 — cross-repo identifier symmetry (SC-006). An inbound
 * {@code @GetMapping("/orders/{id}")} handler and an outbound {@code WebClient.uri("/orders/{id}")}
 * call must produce the same {@code (httpMethod, routeTemplate)} join key, so a future cross-repo
 * phase can match sink&rarr;source by equality without re-analysis. Also pins the pure-model join
 * semantics ({@link ExternalIdentifier#matchesRoute}).
 */
class ExternalIdentifierTest extends LineageRecipeTest {

    @Test
    void inboundAndOutboundSameRouteProduceEqualJoinKeys() {
        rewriteRun(
                // The caller uses WebClient, whose reactive generics don't fully type-resolve.
                spec -> spec.typeValidationOptions(TypeValidation.builder().methodInvocations(false).build())
                        .afterRecipe(run -> {
                            HttpDataNodeTable.Row source = nodeRows(run).stream()
                                    .filter(r -> r.getDirection().equals("SOURCE")).findFirst().orElseThrow();
                            HttpDataNodeTable.Row sink = nodeRows(run).stream()
                                    .filter(r -> r.getDirection().equals("SINK")).findFirst().orElseThrow();
                            // Same path template on both sides — the join key matches (SC-006).
                            assertThat(source.getHttpMethod()).isEqualTo("GET");
                            assertThat(source.getRouteTemplate()).isEqualTo("/orders/{id}");
                            assertThat(sink.getHttpMethod()).isEqualTo(source.getHttpMethod());
                            assertThat(sink.getRouteTemplate()).isEqualTo(source.getRouteTemplate());
                            // The outbound authority is kept separate, never folded into the route.
                            assertThat(sink.getTargetAuthority()).isEqualTo("inventory");
                            assertThat(source.getTargetAuthority()).isBlank();
                        }),
                java(
                        """
                        package com.example.api;
                        import org.springframework.web.bind.annotation.*;

                        @RestController
                        class OrderController {
                            @GetMapping("/orders/{id}")
                            String get(@PathVariable String id) {
                                return "order " + id;
                            }
                        }
                        """
                ),
                java(
                        """
                        package com.example.client;
                        import org.springframework.web.reactive.function.client.WebClient;

                        class OrderClient {
                            private final WebClient web = WebClient.create();
                            String fetch(String id) {
                                return web.get().uri("http://inventory/orders/{id}", id)
                                        .retrieve().bodyToMono(String.class).block();
                            }
                        }
                        """
                )
        );
    }

    @Test
    void matchesRouteIgnoresAuthorityAndResolution() {
        ExternalIdentifier inbound =
                new ExternalIdentifier(HttpMethod.GET, "/orders/{id}", null, Resolution.EXACT);
        ExternalIdentifier outbound =
                new ExternalIdentifier(HttpMethod.GET, "/orders/{id}", "inventory", Resolution.EXACT);
        ExternalIdentifier differentMethod =
                new ExternalIdentifier(HttpMethod.POST, "/orders/{id}", null, Resolution.EXACT);

        assertThat(inbound.matchesRoute(outbound)).isTrue();
        assertThat(inbound.matchesRoute(differentMethod)).isFalse();
    }
}
