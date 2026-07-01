package com.snowfort.recipe.lineage.sink;

import com.snowfort.recipe.lineage.LineageRecipeTest;
import org.junit.jupiter.api.Test;
import org.openrewrite.test.TypeValidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

/**
 * Tests for outbound {@code WebClient} sink detection (User Story 1). The fluent chain must be
 * collapsed to a single logical SINK. Positive + negative per constitution Principle II.
 */
class WebClientSinkTest extends LineageRecipeTest {

    @Test
    void detectsFluentPostChainAsSingleSink() {
        rewriteRun(
                // WebClient's reactive wildcard generics don't fully type-resolve in a unit-test
                // classpath; the parts the detector needs (receiver type, chain names, body arg type) do.
                spec -> spec.typeValidationOptions(TypeValidation.builder().methodInvocations(false).build())
                        .afterRecipe(run -> assertThat(nodeRows(run)).singleElement().satisfies(r -> {
                            assertThat(r.getDirection()).isEqualTo("SINK");
                            assertThat(r.getFramework()).isEqualTo("WEB_CLIENT");
                            assertThat(r.getHttpMethod()).isEqualTo("POST");
                            assertThat(r.getRouteTemplate()).isEqualTo("/orders/{id}");
                            assertThat(r.getPayloadType()).isEqualTo("com.example.Order");
                        })),
                java(
                        """
                        package com.example;
                        import org.springframework.web.reactive.function.client.WebClient;

                        class OrderClient {
                            private final WebClient web = WebClient.create();
                            void send(Order o) {
                                web.post().uri("/orders/{id}", "1").bodyValue(o)
                                   .retrieve().bodyToMono(String.class).block();
                            }
                        }
                        class Order {}
                        """
                )
        );
    }

    @Test
    void ignoresRetrieveOnNonWebClientType() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> assertThat(nodeRows(run)).isEmpty()),
                java(
                        """
                        package com.example;

                        class NotAClient {
                            String retrieve() {
                                return "x";
                            }
                        }
                        class User {
                            void go() {
                                new NotAClient().retrieve();
                            }
                        }
                        """
                )
        );
    }
}
