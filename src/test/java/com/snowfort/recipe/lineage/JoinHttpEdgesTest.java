package com.snowfort.recipe.lineage;

import com.snowfort.recipe.lineage.fixture.SampleServices;
import com.snowfort.recipe.lineage.model.HttpEdge;
import com.snowfort.recipe.lineage.model.UnmatchedHttpEdge;
import com.snowfort.recipe.lineage.table.HttpEdgeTable;
import com.snowfort.recipe.lineage.table.UnmatchedHttpEdgeTable;
import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

class JoinHttpEdgesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new JoinHttpEdges())
                .parser(JavaParser.fromJavaVersion()
                        .classpath("spring-web", "spring-context"));
    }

    @Test
    void joinsHttpOutSinkInServiceAToHttpInSourceInServiceB() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    var edges = run.getDataTableRows(HttpEdgeTable.class);
                    assertThat(edges).hasSize(1);
                    HttpEdge edge = edges.get(0);
                    assertThat(edge.fromRepo()).isEqualTo("service-a");
                    assertThat(edge.fromFramework()).isEqualTo("spring-rest-template");
                    assertThat(edge.toRepo()).isEqualTo("service-b");
                    assertThat(edge.toFramework()).isEqualTo("spring-mvc");
                    assertThat(edge.externalIdentifier()).isEqualTo("GET /customers/{id}");

                    assertThat(run.getDataTableRows(UnmatchedHttpEdgeTable.class)).isEmpty();
                }),
                java(SampleServices.SERVICE_A_SRC, s -> s.path(SampleServices.SERVICE_A_PATH)),
                java(SampleServices.SERVICE_B_SRC, s -> s.path(SampleServices.SERVICE_B_PATH))
        );
    }

    @Test
    void mismatchedUrlYieldsNoEdgeAndOneUnmatchedRow() {
        // Service A calls GET /users/{id} but Service B exposes GET /customers/{id}: no match
        String serviceAMismatch = SampleServices.SERVICE_A_SRC.replace(
                "http://service-b/customers/{id}", "http://service-b/users/{id}");
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    assertThat(run.getDataTableRows(HttpEdgeTable.class)).isEmpty();
                    var unmatched = run.getDataTableRows(UnmatchedHttpEdgeTable.class);
                    assertThat(unmatched).hasSize(1);
                    UnmatchedHttpEdge u = unmatched.get(0);
                    assertThat(u.externalIdentifier()).isEqualTo("GET /users/{id}");
                    assertThat(u.repo()).isEqualTo("service-a");
                    assertThat(u.reason()).isEqualTo("no-matching-source");
                }),
                java(serviceAMismatch, s -> s.path(SampleServices.SERVICE_A_PATH)),
                java(SampleServices.SERVICE_B_SRC, s -> s.path(SampleServices.SERVICE_B_PATH))
        );
    }

    @Test
    void normalizationMatchesVariantPathVariableNames() {
        // Sink uses {id}; source uses {customerId}: they should normalize and join.
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    assertThat(run.getDataTableRows(HttpEdgeTable.class)).hasSize(1);
                    assertThat(run.getDataTableRows(UnmatchedHttpEdgeTable.class)).isEmpty();
                }),
                java("""
                        package com.example.servicea;
                        import org.springframework.web.client.RestTemplate;
                        public class A {
                            private final RestTemplate restTemplate = new RestTemplate();
                            public String fetch(String id) {
                                return restTemplate.getForObject("/customers/{id}", String.class, id);
                            }
                        }
                        """, s -> s.path("service-a/src/main/java/com/example/servicea/A.java")),
                java("""
                        package com.example.serviceb;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.PathVariable;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class B {
                            @GetMapping("/customers/{customerId}")
                            public String get(@PathVariable String customerId) { return customerId; }
                        }
                        """, s -> s.path("service-b/src/main/java/com/example/serviceb/B.java"))
        );
    }

    @Test
    void normalizationMatchesLiteralNumericIdAgainstPathTemplate() {
        // Sink hits /customers/123 (literal); source exposes /customers/{id}: should join.
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    var edges = run.getDataTableRows(HttpEdgeTable.class);
                    assertThat(edges).hasSize(1);
                    assertThat(edges.get(0).externalIdentifier()).isEqualTo("GET /customers/123");
                }),
                java("""
                        package com.example.servicea;
                        import org.springframework.web.client.RestTemplate;
                        public class A {
                            private final RestTemplate restTemplate = new RestTemplate();
                            public String fetch() {
                                return restTemplate.getForObject("/customers/123", String.class);
                            }
                        }
                        """, s -> s.path("service-a/src/main/java/A.java")),
                java("""
                        package com.example.serviceb;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.PathVariable;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class B {
                            @GetMapping("/customers/{id}")
                            public String get(@PathVariable String id) { return id; }
                        }
                        """, s -> s.path("service-b/src/main/java/B.java"))
        );
    }

    @Test
    void unresolvedUrlSinkLandsInUnmatchedTable() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    assertThat(run.getDataTableRows(HttpEdgeTable.class)).isEmpty();
                    var unmatched = run.getDataTableRows(UnmatchedHttpEdgeTable.class);
                    assertThat(unmatched).hasSize(1);
                    UnmatchedHttpEdge u = unmatched.get(0);
                    assertThat(u.reason()).isEqualTo("url-not-resolved");
                    assertThat(u.externalIdentifier()).isEmpty();
                }),
                java("""
                        package com.example.servicea;
                        import org.springframework.web.client.RestTemplate;
                        public class A {
                            private final RestTemplate restTemplate = new RestTemplate();
                            public String fetch(String id) {
                                String url = "http://service-b/customers/" + id;
                                return restTemplate.getForObject(url, String.class);
                            }
                        }
                        """, s -> s.path("service-a/src/main/java/A.java"))
        );
    }

    @Test
    void joinKeyIsCaseInsensitiveForVerb() {
        // verbs already get uppercased in our recipes, so this is a regression check on the join key
        assertThat(JoinHttpEdges.joinKey("get /customers/{id}"))
                .isEqualTo(JoinHttpEdges.joinKey("GET /customers/{id}"));
    }

    @Test
    void normalizePath_collapsesTemplatesAndNumericLiterals() {
        assertThat(JoinHttpEdges.normalizePath("/customers/{id}")).isEqualTo("/customers/{}");
        assertThat(JoinHttpEdges.normalizePath("/customers/{customerId}")).isEqualTo("/customers/{}");
        assertThat(JoinHttpEdges.normalizePath("/customers/123")).isEqualTo("/customers/{}");
        assertThat(JoinHttpEdges.normalizePath("/orders/42/items/{itemId}")).isEqualTo("/orders/{}/items/{}");
        // case-sensitive non-numeric segments are preserved
        assertThat(JoinHttpEdges.normalizePath("/Customers/byEmail")).isEqualTo("/Customers/byEmail");
        // trailing slash is dropped
        assertThat(JoinHttpEdges.normalizePath("/orders/")).isEqualTo("/orders");
    }
}
