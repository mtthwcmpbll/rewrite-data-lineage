package com.snowfort.recipe.lineage.sink;

import com.snowfort.recipe.lineage.LineageRecipeTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

/**
 * Tests for outbound {@code RestTemplate} sink detection (User Story 1). Positive + negative per
 * constitution Principle II.
 */
class RestTemplateSinkTest extends LineageRecipeTest {

    @Test
    void detectsPostForObject() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> assertThat(nodeRows(run)).singleElement().satisfies(r -> {
                    assertThat(r.getDirection()).isEqualTo("SINK");
                    assertThat(r.getFramework()).isEqualTo("REST_TEMPLATE");
                    assertThat(r.getHttpMethod()).isEqualTo("POST");
                    assertThat(r.getRouteTemplate()).isEqualTo("/reserve");
                    assertThat(r.getTargetAuthority()).isEqualTo("inventory");
                    assertThat(r.getPayloadType()).isEqualTo("com.example.Order");
                })),
                java(
                        """
                        package com.example;
                        import org.springframework.web.client.RestTemplate;

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
    void derivesMethodFromExchange() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> assertThat(nodeRows(run)).singleElement().satisfies(r -> {
                    assertThat(r.getFramework()).isEqualTo("REST_TEMPLATE");
                    assertThat(r.getHttpMethod()).isEqualTo("PUT");
                    assertThat(r.getRouteTemplate()).isEqualTo("/orders/1");
                })),
                java(
                        """
                        package com.example;
                        import org.springframework.http.HttpEntity;
                        import org.springframework.http.HttpMethod;
                        import org.springframework.web.client.RestTemplate;

                        class OrderClient {
                            private final RestTemplate rest = new RestTemplate();
                            void send(HttpEntity<String> e) {
                                rest.exchange("http://orders/orders/1", HttpMethod.PUT, e, String.class);
                            }
                        }
                        """
                )
        );
    }

    @Test
    void ignoresNonRestTemplateCall() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> assertThat(nodeRows(run)).isEmpty()),
                java(
                        """
                        package com.example;

                        class OrderClient {
                            String describe(Object o) {
                                return o.toString();
                            }
                        }
                        """
                )
        );
    }
}
