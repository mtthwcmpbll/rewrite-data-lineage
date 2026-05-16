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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.openrewrite.java.Assertions.java;

class FindSpringHttpInSourcesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FindSpringHttpInSources())
                .parser(JavaParser.fromJavaVersion()
                        .classpath("spring-web", "spring-context"));
    }

    @Test
    void postMappingWithRequestBody() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    var rows = run.getDataTableRows(DataFlowNodeTable.class);
                    assertThat(rows).hasSize(1);
                    DataFlowNode row = rows.get(0);
                    assertThat(row.kind()).isEqualTo(NodeKind.SOURCE);
                    assertThat(row.framework()).isEqualTo("spring-mvc");
                    assertThat(row.externalIdentifier()).isEqualTo("POST /orders");
                    assertThat(row.payloadType()).isEqualTo("com.example.Order");
                    assertThat(row.confidence()).isEqualTo(Confidence.HIGH);
                    assertThat(row.locator()).contains("OrderController#create(order)");
                }),
                java("""
                        package com.example;
                        public class Order {}
                        """),
                java("""
                        package com.example;
                        import org.springframework.web.bind.annotation.PostMapping;
                        import org.springframework.web.bind.annotation.RequestBody;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class OrderController {
                            @PostMapping("/orders")
                            public Order create(@RequestBody Order order) {
                                return order;
                            }
                        }
                        """)
        );
    }

    @Test
    void getMappingWithPathVariable() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    var rows = run.getDataTableRows(DataFlowNodeTable.class);
                    assertThat(rows).extracting(DataFlowNode::externalIdentifier, DataFlowNode::payloadType)
                            .containsExactly(tuple("GET /customers/{id}", "java.lang.String"));
                }),
                java("""
                        package com.example;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.PathVariable;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class CustomerController {
                            @GetMapping("/customers/{id}")
                            public String get(@PathVariable String id) {
                                return id;
                            }
                        }
                        """)
        );
    }

    @Test
    void requestParamAndRequestHeader() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    var rows = run.getDataTableRows(DataFlowNodeTable.class);
                    assertThat(rows).extracting(DataFlowNode::externalIdentifier, DataFlowNode::payloadType, DataFlowNode::locator)
                            .containsExactlyInAnyOrder(
                                    tuple("GET /search", "java.lang.String", rows.get(0).locator()),
                                    tuple("GET /search", "java.lang.String", rows.get(1).locator()));
                    assertThat(rows).extracting(DataFlowNode::locator)
                            .anyMatch(l -> l.endsWith("search(q)"))
                            .anyMatch(l -> l.endsWith("search(auth)"));
                }),
                java("""
                        package com.example;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.RequestHeader;
                        import org.springframework.web.bind.annotation.RequestParam;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class SearchController {
                            @GetMapping("/search")
                            public String search(@RequestParam String q, @RequestHeader("Authorization") String auth) {
                                return q + auth;
                            }
                        }
                        """)
        );
    }

    @Test
    void putAndDeleteAndPatchMappings() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    var rows = run.getDataTableRows(DataFlowNodeTable.class);
                    assertThat(rows).extracting(DataFlowNode::externalIdentifier)
                            .containsExactlyInAnyOrder("PUT /a/{id}", "DELETE /a/{id}", "PATCH /a/{id}");
                }),
                java("""
                        package com.example;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        public class C {
                            @PutMapping("/a/{id}")
                            public void update(@PathVariable String id) {}
                            @DeleteMapping("/a/{id}")
                            public void remove(@PathVariable String id) {}
                            @PatchMapping("/a/{id}")
                            public void patch(@PathVariable String id) {}
                        }
                        """)
        );
    }

    @Test
    void classLevelRequestMappingPathIsPrepended() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    var rows = run.getDataTableRows(DataFlowNodeTable.class);
                    assertThat(rows).extracting(DataFlowNode::externalIdentifier)
                            .containsExactly("GET /api/orders");
                }),
                java("""
                        package com.example;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.RequestMapping;
                        import org.springframework.web.bind.annotation.RequestParam;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        @RequestMapping("/api")
                        public class OrderController {
                            @GetMapping("/orders")
                            public String list(@RequestParam String q) { return q; }
                        }
                        """)
        );
    }

    @Test
    void requestMappingWithoutExplicitMethodEmitsWildcardVerb() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    var rows = run.getDataTableRows(DataFlowNodeTable.class);
                    assertThat(rows).extracting(DataFlowNode::externalIdentifier)
                            .containsExactly("* /things/{id}");
                }),
                java("""
                        package com.example;
                        import org.springframework.web.bind.annotation.PathVariable;
                        import org.springframework.web.bind.annotation.RequestMapping;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class ThingController {
                            @RequestMapping("/things/{id}")
                            public String get(@PathVariable String id) { return id; }
                        }
                        """)
        );
    }

    @Test
    void requestMappingWithExplicitMethodsEmitsOneRowPerVerb() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    var rows = run.getDataTableRows(DataFlowNodeTable.class);
                    assertThat(rows).extracting(DataFlowNode::externalIdentifier)
                            .containsExactlyInAnyOrder("GET /things/{id}", "POST /things/{id}");
                }),
                java("""
                        package com.example;
                        import org.springframework.web.bind.annotation.PathVariable;
                        import org.springframework.web.bind.annotation.RequestMapping;
                        import org.springframework.web.bind.annotation.RequestMethod;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class ThingController {
                            @RequestMapping(value = "/things/{id}", method = {RequestMethod.GET, RequestMethod.POST})
                            public String get(@PathVariable String id) { return id; }
                        }
                        """)
        );
    }

    @Test
    void controllerWithResponseBodyMethodIsDetected() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    var rows = run.getDataTableRows(DataFlowNodeTable.class);
                    assertThat(rows).extracting(DataFlowNode::externalIdentifier)
                            .containsExactly("GET /hello");
                }),
                java("""
                        package com.example;
                        import org.springframework.stereotype.Controller;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.RequestParam;
                        import org.springframework.web.bind.annotation.ResponseBody;
                        @Controller
                        public class HelloController {
                            @GetMapping("/hello")
                            @ResponseBody
                            public String greet(@RequestParam String name) { return name; }
                        }
                        """)
        );
    }

    @Test
    void plainPojoWithoutMappingAnnotationsProducesNoRows() {
        rewriteRun(
                spec -> spec.afterRecipe(run ->
                        assertThat(run.getDataTableRows(DataFlowNodeTable.class)).isEmpty()),
                java("""
                        package com.example;
                        public class NotAController {
                            public String hello(String name) { return name; }
                        }
                        """)
        );
    }

    @Test
    void smokeTestOnTwoServiceFixture() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    var rows = run.getDataTableRows(DataFlowNodeTable.class);
                    // Service A: POST /orders with @RequestBody Order
                    // Service B: GET /customers/{id} with @PathVariable id
                    assertThat(rows).extracting(DataFlowNode::externalIdentifier)
                            .containsExactlyInAnyOrder("POST /orders", "GET /customers/{id}");
                    assertThat(rows).allMatch(r -> r.kind() == NodeKind.SOURCE);
                    assertThat(rows).allMatch(r -> "spring-mvc".equals(r.framework()));
                }),
                java(SampleServices.SERVICE_A_SRC, s -> s.path(SampleServices.SERVICE_A_PATH)),
                java(SampleServices.SERVICE_B_SRC, s -> s.path(SampleServices.SERVICE_B_PATH))
        );
    }
}
