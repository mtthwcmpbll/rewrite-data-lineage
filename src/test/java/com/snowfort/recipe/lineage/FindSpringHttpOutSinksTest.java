package com.snowfort.recipe.lineage;

import com.snowfort.recipe.lineage.fixture.SampleServices;
import com.snowfort.recipe.lineage.model.Confidence;
import com.snowfort.recipe.lineage.model.DataFlowNode;
import com.snowfort.recipe.lineage.model.NodeKind;
import com.snowfort.recipe.lineage.table.DataFlowNodeTable;
import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.openrewrite.java.Assertions.java;

class FindSpringHttpOutSinksTest implements RewriteTest {

    /** Minimal stub of {@code @FeignClient} so the Feign tests don't need spring-cloud on the classpath. */
    private static final String FEIGN_CLIENT_STUB = """
            package org.springframework.cloud.openfeign;
            public @interface FeignClient {
                String name() default "";
                String value() default "";
                String url() default "";
                String path() default "";
            }
            """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FindSpringHttpOutSinks())
                // RestClient/WebClient/HttpEntity inner-spec types don't fully resolve through
                // `.classpath()` jar loading; relax validation for those test fixtures since the
                // recipe only inspects the immediate select chain, not the response specs.
                .typeValidationOptions(TypeValidation.builder().methodInvocations(false).identifiers(false).build())
                .parser(JavaParser.fromJavaVersion()
                        .classpath("spring-web", "spring-webflux", "spring-context"));
    }

    @Test
    void restTemplateGetForObject() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    var rows = run.getDataTableRows(DataFlowNodeTable.class);
                    assertThat(rows).hasSize(1);
                    DataFlowNode row = rows.get(0);
                    assertThat(row.kind()).isEqualTo(NodeKind.SINK);
                    assertThat(row.framework()).isEqualTo("spring-rest-template");
                    assertThat(row.externalIdentifier()).isEqualTo("GET /customers/{id}");
                    assertThat(row.confidence()).isEqualTo(Confidence.HIGH);
                    assertThat(row.payloadType()).isEmpty();
                }),
                java("""
                        package com.example;
                        import org.springframework.web.client.RestTemplate;
                        public class CustomerApi {
                            private final RestTemplate restTemplate = new RestTemplate();
                            public String fetch(String id) {
                                return restTemplate.getForObject("http://service-b/customers/{id}", String.class, id);
                            }
                        }
                        """)
        );
    }

    @Test
    void restTemplatePostForEntityCapturesBodyType() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    var rows = run.getDataTableRows(DataFlowNodeTable.class);
                    assertThat(rows).hasSize(1);
                    DataFlowNode row = rows.get(0);
                    assertThat(row.externalIdentifier()).isEqualTo("POST /orders");
                    assertThat(row.payloadType()).isEqualTo("com.example.Order");
                }),
                java("""
                        package com.example;
                        public class Order {}
                        """),
                java("""
                        package com.example;
                        import org.springframework.http.ResponseEntity;
                        import org.springframework.web.client.RestTemplate;
                        public class OrderApi {
                            private final RestTemplate restTemplate = new RestTemplate();
                            public ResponseEntity<Order> create(Order order) {
                                return restTemplate.postForEntity("/orders", order, Order.class);
                            }
                        }
                        """)
        );
    }

    @Test
    void restTemplateExchangeResolvesHttpMethodLiteral() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    var rows = run.getDataTableRows(DataFlowNodeTable.class);
                    assertThat(rows).extracting(DataFlowNode::externalIdentifier)
                            .containsExactly("PUT /things/{id}");
                }),
                java("""
                        package com.example;
                        import org.springframework.http.HttpEntity;
                        import org.springframework.http.HttpMethod;
                        import org.springframework.web.client.RestTemplate;
                        public class ThingApi {
                            private final RestTemplate restTemplate = new RestTemplate();
                            public String update(String id, String body) {
                                return restTemplate.exchange(
                                        "/things/{id}", HttpMethod.PUT, new HttpEntity<>(body), String.class, id
                                ).getBody();
                            }
                        }
                        """)
        );
    }

    @Test
    void restClientFluentGet() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    var rows = run.getDataTableRows(DataFlowNodeTable.class);
                    assertThat(rows).extracting(DataFlowNode::framework, DataFlowNode::externalIdentifier)
                            .containsExactly(tuple("spring-rest-client", "GET /customers/{id}"));
                }),
                java("""
                        package com.example;
                        import org.springframework.web.client.RestClient;
                        public class CustomerApi {
                            private final RestClient restClient = RestClient.create();
                            public String fetch(String id) {
                                return restClient.get().uri("/customers/{id}", id).retrieve().body(String.class);
                            }
                        }
                        """)
        );
    }

    @Test
    void restClientFluentPostWithBody() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    var rows = run.getDataTableRows(DataFlowNodeTable.class);
                    assertThat(rows).extracting(DataFlowNode::framework, DataFlowNode::externalIdentifier)
                            .containsExactly(tuple("spring-rest-client", "POST /orders"));
                }),
                java("""
                        package com.example;
                        public class Order {}
                        """),
                java("""
                        package com.example;
                        import org.springframework.web.client.RestClient;
                        public class OrderApi {
                            private final RestClient restClient = RestClient.create();
                            public Order create(Order o) {
                                return restClient.post().uri("/orders").body(o).retrieve().body(Order.class);
                            }
                        }
                        """)
        );
    }

    @Test
    void webClientFluentGet() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    var rows = run.getDataTableRows(DataFlowNodeTable.class);
                    assertThat(rows).extracting(DataFlowNode::framework, DataFlowNode::externalIdentifier)
                            .containsExactly(tuple("spring-webclient", "GET /customers/{id}"));
                }),
                java("""
                        package com.example;
                        import org.springframework.web.reactive.function.client.WebClient;
                        public class CustomerApi {
                            private final WebClient webClient = WebClient.create();
                            public String fetch(String id) {
                                return webClient.get().uri("/customers/{id}", id).retrieve().bodyToMono(String.class).block();
                            }
                        }
                        """)
        );
    }

    @Test
    void feignClientMethodEmitsSink() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    var rows = run.getDataTableRows(DataFlowNodeTable.class);
                    assertThat(rows).extracting(DataFlowNode::framework, DataFlowNode::externalIdentifier, DataFlowNode::payloadType)
                            .containsExactlyInAnyOrder(
                                    tuple("spring-feign", "GET /customers/{id}", ""),
                                    tuple("spring-feign", "POST /customers", "com.example.Customer"));
                }),
                java(FEIGN_CLIENT_STUB),
                java("""
                        package com.example;
                        public class Customer {}
                        """),
                java("""
                        package com.example;
                        import org.springframework.cloud.openfeign.FeignClient;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.PathVariable;
                        import org.springframework.web.bind.annotation.PostMapping;
                        import org.springframework.web.bind.annotation.RequestBody;
                        @FeignClient(name = "customers")
                        public interface CustomerClient {
                            @GetMapping("/customers/{id}")
                            Customer get(@PathVariable String id);
                            @PostMapping("/customers")
                            Customer create(@RequestBody Customer c);
                        }
                        """)
        );
    }

    @Test
    void nonLiteralUrlEmitsLowConfidenceRow() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    var rows = run.getDataTableRows(DataFlowNodeTable.class);
                    assertThat(rows).hasSize(1);
                    DataFlowNode row = rows.get(0);
                    assertThat(row.confidence()).isEqualTo(Confidence.LOW);
                    assertThat(row.externalIdentifier()).isEmpty();
                    assertThat(row.framework()).isEqualTo("spring-rest-template");
                }),
                java("""
                        package com.example;
                        import org.springframework.web.client.RestTemplate;
                        public class DynamicApi {
                            private final RestTemplate restTemplate = new RestTemplate();
                            public String fetch(String id) {
                                String base = "http://service-b";
                                String url = base + "/customers/" + id;
                                return restTemplate.getForObject(url, String.class);
                            }
                        }
                        """)
        );
    }

    @Test
    void declaredRestTemplateFieldThatIsNeverInvokedProducesNoRows() {
        rewriteRun(
                spec -> spec.afterRecipe(run ->
                        assertThat(run.getDataTableRows(DataFlowNodeTable.class)).isEmpty()),
                java("""
                        package com.example;
                        import org.springframework.web.client.RestTemplate;
                        public class IdleApi {
                            private final RestTemplate restTemplate = new RestTemplate();
                        }
                        """)
        );
    }

    @Test
    void smokeTestOnTwoServiceFixture() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    var rows = run.getDataTableRows(DataFlowNodeTable.class);
                    assertThat(rows).extracting(DataFlowNode::framework, DataFlowNode::externalIdentifier)
                            .containsExactly(tuple("spring-rest-template", "GET /customers/{id}"));
                    assertThat(rows).allMatch(r -> r.kind() == NodeKind.SINK);
                }),
                java(SampleServices.SERVICE_A_SRC, s -> s.path(SampleServices.SERVICE_A_PATH)),
                java(SampleServices.SERVICE_B_SRC, s -> s.path(SampleServices.SERVICE_B_PATH))
        );
    }
}
