package com.snowfort.recipe.lineage.source;

import com.snowfort.recipe.lineage.LineageRecipeTest;
import com.snowfort.recipe.lineage.table.HttpDataNodeTable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

/**
 * Tests for inbound Spring MVC endpoint detection (User Story 1). Each detector carries a positive
 * and a negative case per constitution Principle II.
 */
class SpringMvcSourceTest extends LineageRecipeTest {

    @Test
    void detectsPostMappingWithRequestBody() {
        rewriteRun(
                spec -> spec.dataTable(HttpDataNodeTable.Row.class, rows -> {
                    assertThat(rows).hasSize(1);
                    HttpDataNodeTable.Row r = rows.get(0);
                    assertThat(r.getDirection()).isEqualTo("SOURCE");
                    assertThat(r.getFramework()).isEqualTo("SPRING_MVC");
                    assertThat(r.getHttpMethod()).isEqualTo("POST");
                    assertThat(r.getRouteTemplate()).isEqualTo("/orders");
                    assertThat(r.getRouteResolution()).isEqualTo("EXACT");
                    assertThat(r.getPayloadType()).isEqualTo("com.example.Order");
                    assertThat(r.isPayloadResolved()).isTrue();
                }),
                java(
                        """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;

                        @RestController
                        class OrderController {
                            @PostMapping("/orders")
                            public String create(@RequestBody Order order) {
                                return "ok";
                            }
                        }
                        class Order {}
                        """
                )
        );
    }

    @Test
    void joinsClassLevelAndMethodLevelPathsAndReadsGetMappingViaMetaAnnotation() {
        rewriteRun(
                spec -> spec.dataTable(HttpDataNodeTable.Row.class, rows -> {
                    assertThat(rows).hasSize(1);
                    HttpDataNodeTable.Row r = rows.get(0);
                    assertThat(r.getHttpMethod()).isEqualTo("GET");
                    assertThat(r.getRouteTemplate()).isEqualTo("/api/orders/{id}");
                }),
                java(
                        """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;

                        @RestController
                        @RequestMapping("/api")
                        class OrderController {
                            @GetMapping("/orders/{id}")
                            public String get(@PathVariable String id) {
                                return id;
                            }
                        }
                        """
                )
        );
    }

    @Test
    void methodMappingWithNoPathInheritsClassPathAsExact() {
        // Regression (found running on ModerneTraining): a class-level @RequestMapping plus a
        // method mapping with no path is a fully-resolved route, not UNKNOWN.
        rewriteRun(
                spec -> spec.dataTable(HttpDataNodeTable.Row.class, rows -> {
                    assertThat(rows).hasSize(1);
                    HttpDataNodeTable.Row r = rows.get(0);
                    assertThat(r.getHttpMethod()).isEqualTo("POST");
                    assertThat(r.getRouteTemplate()).isEqualTo("/api/customers");
                    assertThat(r.getRouteResolution()).isEqualTo("EXACT");
                }),
                java(
                        """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;

                        @RestController
                        @RequestMapping("/api/customers")
                        class CustomerController {
                            @PostMapping
                            public String create(@RequestBody Customer customer) {
                                return "ok";
                            }
                        }
                        class Customer {}
                        """
                )
        );
    }

    @Test
    void unresolvableMappingPathIsMarkedUnknown() {
        // A path argument that is present but not a string literal (a constant reference) cannot be
        // resolved -> UNKNOWN, distinct from "no path argument".
        rewriteRun(
                spec -> spec.dataTable(HttpDataNodeTable.Row.class, rows -> {
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0).getRouteResolution()).isEqualTo("UNKNOWN");
                }),
                java(
                        """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;

                        @RestController
                        class RouteController {
                            static final String PATH = "/dynamic";
                            @GetMapping(PATH)
                            public String get() {
                                return "ok";
                            }
                        }
                        """
                )
        );
    }

    @Test
    void ignoresPlainMethodOnController() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> assertThat(nodeRows(run)).isEmpty()),
                java(
                        """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;

                        @RestController
                        class OrderController {
                            public String helper(String x) {
                                return x;
                            }
                        }
                        """
                )
        );
    }
}
