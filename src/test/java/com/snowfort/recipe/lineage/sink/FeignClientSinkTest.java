package com.snowfort.recipe.lineage.sink;

import com.snowfort.recipe.lineage.LineageRecipeTest;
import com.snowfort.recipe.lineage.table.HttpDataNodeTable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

/**
 * Tests for outbound Spring Cloud OpenFeign sink detection. A {@code @FeignClient} interface method
 * carrying a mapping annotation is one SINK node whose {@code (httpMethod, routeTemplate)} mirrors the
 * inbound controller it targets (SC-006). Positive + negative per constitution Principle II.
 */
class FeignClientSinkTest extends LineageRecipeTest {

    @Test
    void detectsFeignInterfaceMethodAsSink() {
        rewriteRun(
                spec -> spec.dataTable(HttpDataNodeTable.Row.class, rows -> {
                    assertThat(rows).hasSize(1);
                    HttpDataNodeTable.Row r = rows.get(0);
                    assertThat(r.getDirection()).isEqualTo("SINK");
                    assertThat(r.getFramework()).isEqualTo("FEIGN");
                    assertThat(r.getHttpMethod()).isEqualTo("POST");
                    assertThat(r.getRouteTemplate()).isEqualTo("/api/fraud/check");
                    assertThat(r.getRouteResolution()).isEqualTo("EXACT");
                    // The @FeignClient name is the logical target service, kept out of the join key.
                    assertThat(r.getTargetAuthority()).isEqualTo("fraud-detection-service");
                    assertThat(r.getPayloadType()).isEqualTo("com.example.FraudCheckRequest");
                }),
                java(
                        """
                        package com.example;
                        import org.springframework.cloud.openfeign.FeignClient;
                        import org.springframework.web.bind.annotation.*;

                        @FeignClient(name = "fraud-detection-service", url = "${fraud.url:http://localhost:8087}")
                        interface FraudClient {
                            @PostMapping("/api/fraud/check")
                            String checkFraud(@RequestBody FraudCheckRequest request);
                        }
                        class FraudCheckRequest {}
                        """
                )
        );
    }

    @Test
    void joinsFeignClientPathPrefixWithMethodPath() {
        rewriteRun(
                spec -> spec.dataTable(HttpDataNodeTable.Row.class, rows -> {
                    assertThat(rows).hasSize(1);
                    HttpDataNodeTable.Row r = rows.get(0);
                    assertThat(r.getHttpMethod()).isEqualTo("GET");
                    assertThat(r.getRouteTemplate()).isEqualTo("/api/orders/{id}");
                    assertThat(r.getTargetAuthority()).isEqualTo("order-service");
                }),
                java(
                        """
                        package com.example;
                        import org.springframework.cloud.openfeign.FeignClient;
                        import org.springframework.web.bind.annotation.*;

                        @FeignClient(name = "order-service", path = "/api/orders")
                        interface OrderClient {
                            @GetMapping("/{id}")
                            String get(@PathVariable Long id);
                        }
                        """
                )
        );
    }

    @Test
    void ignoresPlainInterfaceMethod() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> assertThat(nodeRows(run)).isEmpty()),
                java(
                        """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;

                        // Not a @FeignClient: a mapping annotation alone on a plain interface is not an outbound call.
                        interface NotAFeignClient {
                            @PostMapping("/api/x")
                            String call(@RequestBody Object body);
                        }
                        """
                )
        );
    }
}
