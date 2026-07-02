package com.snowfort.recipe.lineage.flow;

import com.snowfort.recipe.lineage.LineageRecipeTest;
import com.snowfort.recipe.lineage.table.DataFlowChainTable;
import com.snowfort.recipe.lineage.table.HttpDataNodeTable;
import org.junit.jupiter.api.Test;
import org.openrewrite.test.TypeValidation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

/**
 * User Story 2, task T023 — inter-procedural call-graph reachability. A controller &rarr; service
 * &rarr; client flow yields an ordered chain from source to sink; a constant-fed sink yields no chain
 * (FR-007); when two sources exist but only one reaches the sink, only that source is paired
 * (multi-source disambiguation); and WebClient chains are traced as well as RestTemplate ones.
 */
class CallGraphReachabilityTest extends LineageRecipeTest {

    @Test
    void multiHopChainIsOrderedSourceToSink() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    List<DataFlowChainTable.Row> chain = chainRows(run);
                    assertThat(chain).hasSize(2);
                    assertThat(chain.get(0).getEdgeIndex()).isZero();
                    assertThat(chain.get(1).getEdgeIndex()).isEqualTo(1);
                    // Ordered controller -> service -> client.
                    assertThat(chain.get(0).getFromMethodFqn()).isEqualTo("com.example.ApiController#submit");
                    assertThat(chain.get(0).getToMethodFqn()).isEqualTo("com.example.Forwarder#relay");
                    assertThat(chain.get(1).getFromMethodFqn()).isEqualTo("com.example.Forwarder#relay");
                }),
                java(
                        """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;
                        import org.springframework.web.client.RestTemplate;

                        @RestController
                        class ApiController {
                            private final Forwarder forwarder = new Forwarder();
                            @PostMapping("/submit")
                            String submit(@RequestBody Payload payload) {
                                return forwarder.relay(payload);
                            }
                        }

                        class Forwarder {
                            private final RestTemplate rest = new RestTemplate();
                            String relay(Payload p) {
                                return rest.postForObject("http://downstream/ingest", p, String.class);
                            }
                        }
                        class Payload {}
                        """
                )
        );
    }

    @Test
    void onlyTheReachingSourceIsPairedWithTheSink() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    // Two SOURCE handlers are cataloged; only the one whose payload reaches the sink chains.
                    HttpDataNodeTable.Row reaching = nodeRows(run).stream()
                            .filter(r -> r.getDirection().equals("SOURCE") && r.getRouteTemplate().equals("/reach"))
                            .findFirst().orElseThrow();
                    assertThat(chainRows(run)).isNotEmpty();
                    assertThat(chainRows(run)).allSatisfy(c ->
                            assertThat(c.getSourceNodeId()).isEqualTo(reaching.getNodeId()));
                }),
                java(
                        """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;
                        import org.springframework.web.client.RestTemplate;

                        @RestController
                        class TwoSourceController {
                            private final Forwarder forwarder = new Forwarder();
                            @PostMapping("/reach")
                            String reach(@RequestBody Payload payload) {
                                return forwarder.relay(payload);
                            }
                            @PostMapping("/noreach")
                            String noreach(@RequestBody Payload other) {
                                return "ignored";
                            }
                        }

                        class Forwarder {
                            private final RestTemplate rest = new RestTemplate();
                            String relay(Payload p) {
                                return rest.postForObject("http://downstream/ingest", p, String.class);
                            }
                        }
                        class Payload {}
                        """
                )
        );
    }

    @Test
    void constantFedSinkAcrossMethodsYieldsNoChain() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    assertThat(nodeRows(run)).filteredOn(r -> r.getDirection().equals("SINK")).hasSize(1);
                    assertThat(chainRows(run)).isEmpty();
                }),
                java(
                        """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;
                        import org.springframework.web.client.RestTemplate;

                        @RestController
                        class ApiController {
                            private final Forwarder forwarder = new Forwarder();
                            @PostMapping("/submit")
                            String submit(@RequestBody Payload payload) {
                                return forwarder.relayConstant();
                            }
                        }

                        class Forwarder {
                            private final RestTemplate rest = new RestTemplate();
                            String relayConstant() {
                                return rest.postForObject("http://downstream/ingest", new Payload(), String.class);
                            }
                        }
                        class Payload {}
                        """
                )
        );
    }

    @Test
    void webClientChainIsTraced() {
        rewriteRun(
                // WebClient's reactive wildcard generics don't fully type-resolve in a unit-test
                // classpath; chain reconstruction runs off the call graph, not GlobalDataFlow.
                spec -> spec.typeValidationOptions(TypeValidation.builder().methodInvocations(false).build())
                        .afterRecipe(run -> {
                            HttpDataNodeTable.Row sink = nodeRows(run).stream()
                                    .filter(r -> r.getFramework().equals("WEB_CLIENT")).findFirst().orElseThrow();
                            assertThat(chainRows(run)).isNotEmpty();
                            assertThat(chainRows(run)).allSatisfy(c ->
                                    assertThat(c.getSinkNodeId()).isEqualTo(sink.getNodeId()));
                            // The payload enters at bodyValue(payload) -> argument index 0.
                            assertThat(chainRows(run)).last().satisfies(c ->
                                    assertThat(c.getTaintedArgPositions()).isEqualTo("0"));
                        }),
                java(
                        """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;
                        import org.springframework.web.reactive.function.client.WebClient;

                        @RestController
                        class ReactiveController {
                            private final WebClient client = WebClient.create();
                            @PostMapping("/reactive")
                            String submit(@RequestBody Payload payload) {
                                client.post().uri("http://downstream/ingest").bodyValue(payload)
                                        .retrieve().bodyToMono(String.class).block();
                                return "ok";
                            }
                        }
                        class Payload {}
                        """
                )
        );
    }
}
