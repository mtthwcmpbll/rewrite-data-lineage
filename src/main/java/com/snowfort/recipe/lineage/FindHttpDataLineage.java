package com.snowfort.recipe.lineage;

import com.snowfort.recipe.lineage.flow.CallGraph;
import com.snowfort.recipe.lineage.flow.HttpFlowSpec;
import com.snowfort.recipe.lineage.flow.LineageAccumulator;
import com.snowfort.recipe.lineage.flow.ParamRefs;
import com.snowfort.recipe.lineage.model.CallChainEdge;
import com.snowfort.recipe.lineage.model.DataFlowNode;
import com.snowfort.recipe.lineage.model.Detection;
import com.snowfort.recipe.lineage.model.Direction;
import com.snowfort.recipe.lineage.model.ExternalIdentifier;
import com.snowfort.recipe.lineage.model.Framework;
import com.snowfort.recipe.lineage.source.SpringMvcInbound;
import com.snowfort.recipe.lineage.source.SpringMvcSource;
import com.snowfort.recipe.lineage.sink.FeignClientSink;
import com.snowfort.recipe.lineage.sink.RestTemplateSink;
import com.snowfort.recipe.lineage.sink.WebClientSink;
import com.snowfort.recipe.lineage.table.DataFlowChainTable;
import com.snowfort.recipe.lineage.table.HttpDataNodeTable;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.analysis.dataflow.global.GlobalDataFlow;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Catalogs inbound Spring MVC endpoints and outbound RestTemplate/WebClient calls as HTTP data
 * nodes (User Story 1), and traces request data from an endpoint to an outbound call across method
 * boundaries within the repository (User Story 2). Emits the {@link HttpDataNodeTable} and
 * {@link DataFlowChainTable} data tables. Does not modify source (constitution Principle IV).
 *
 * <p>Chains are reconstructed by a repo-local {@link CallGraph}: the scan phase records each call
 * site with the caller-parameter references of its arguments ({@link ParamRefs}); the generate phase
 * runs a position-aware fixed point from each source's inbound parameters to every reachable sink,
 * emitting one ordered {@link CallChainEdge} per method hop. {@code GlobalDataFlow} is retained as the
 * inter-procedural reachability oracle (research R1); computing reachability in the call graph lets
 * emission be a single deterministic, sorted pass (SC-004).
 */
public class FindHttpDataLineage extends ScanningRecipe<LineageAccumulator> {

    private final transient HttpDataNodeTable nodeTable = new HttpDataNodeTable(this);
    private final transient DataFlowChainTable chainTable = new DataFlowChainTable(this);

    private final transient SpringMvcSource springMvcSource = new SpringMvcSource();
    private final transient RestTemplateSink restTemplateSink = new RestTemplateSink();
    private final transient WebClientSink webClientSink = new WebClientSink();
    private final transient FeignClientSink feignClientSink = new FeignClientSink();

    @Override
    public String getDisplayName() {
        return "Find Spring Boot HTTP data lineage";
    }

    @Override
    public String getDescription() {
        return "Catalog inbound Spring MVC endpoints and outbound RestTemplate/WebClient/Feign calls as " +
               "HTTP data nodes, and trace request data from an endpoint to an outbound call across " +
               "method boundaries within the repository. Emits the HttpDataNodes and DataFlowChains " +
               "data tables. Does not modify source.";
    }

    @Override
    public LineageAccumulator getInitialValue(ExecutionContext ctx) {
        return new LineageAccumulator(GlobalDataFlow.accumulator(new HttpFlowSpec()));
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(LineageAccumulator acc) {
        TreeVisitor<?, ExecutionContext> globalScanner = acc.global().scanner();
        JavaIsoVisitor<ExecutionContext> nodeScanner = nodeScanner(acc);
        // Run both scanners once over each source file (both accumulate; neither mutates).
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    globalScanner.visit(tree, ctx);
                    nodeScanner.visit(tree, ctx);
                }
                return tree;
            }
        };
    }

    /**
     * US1 node detection (one SOURCE per handler, one SINK per outbound call) plus US2 call-graph
     * construction (every in-repo call and every outbound sink, annotated with which caller parameters
     * each argument references).
     */
    private JavaIsoVisitor<ExecutionContext> nodeScanner(LineageAccumulator acc) {
        CallGraph cg = acc.callGraph();
        Map<J.MethodDeclaration, ParamRefs> refsCache = new IdentityHashMap<>();
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration md, ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(md, ctx);
                String fqn = methodFqn(m.getMethodType());
                cg.declareMethod(fqn);
                J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
                Detection source = springMvcSource.detect(m, enclosing);
                if (source != null) {
                    DataFlowNode node = buildNode(getCursor(), source, fqn, m.getSimpleName());
                    acc.addNode(node);
                    cg.addSource(fqn, inboundOriginPositions(m), node);
                }
                // A declarative Feign endpoint is an outbound SINK declared on the interface method.
                Detection feign = feignClientSink.detect(m, enclosing);
                if (feign != null) {
                    acc.addNode(buildNode(getCursor(), feign, fqn, m.getSimpleName()));
                }
                return m;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(mi, ctx);
                J.MethodDeclaration enclosingMethod = getCursor().firstEnclosing(J.MethodDeclaration.class);
                String callerFqn = enclosingMethod == null ? "" : methodFqn(enclosingMethod.getMethodType());
                ParamRefs refs = enclosingMethod == null ? null
                        : refsCache.computeIfAbsent(enclosingMethod, ParamRefs::of);

                Detection sink = restTemplateSink.detect(m);
                boolean webClient = false;
                if (sink == null) {
                    sink = webClientSink.detect(m);
                    webClient = sink != null;
                }
                if (sink != null) {
                    String expression = m.printTrimmed(getCursor());
                    acc.addNode(buildNode(getCursor(), sink, callerFqn, expression));
                    if (enclosingMethod != null) {
                        recordSinkEdge(cg, callerFqn, m, refs, webClient, sourceFile(getCursor()), expression);
                    }
                    return m;
                }
                if (enclosingMethod != null) {
                    String calleeFqn = methodFqn(m.getMethodType());
                    if (!calleeFqn.isEmpty()) {
                        cg.addCall(callerFqn, calleeFqn, sourceFile(getCursor()), argRefs(m.getArguments(), refs));
                    }
                }
                return m;
            }
        };
    }

    /** Record the outbound sink call as a terminal call-graph edge carrying its payload-argument refs. */
    private void recordSinkEdge(CallGraph cg, String callerFqn, J.MethodInvocation mi,
                                @Nullable ParamRefs refs, boolean webClient, String file, String expression) {
        Map<Integer, Set<Integer>> argToParams;
        String calleeFqn;
        if (webClient) {
            J.MethodInvocation body = bodyLink(mi);
            if (body != null && !body.getArguments().isEmpty()) {
                argToParams = argRefs(Collections.singletonList(body.getArguments().get(0)), refs);
                calleeFqn = methodFqn(body.getMethodType());
            } else {
                argToParams = new LinkedHashMap<>();
                calleeFqn = methodFqn(mi.getMethodType());
            }
        } else {
            argToParams = argRefs(mi.getArguments(), refs);
            calleeFqn = methodFqn(mi.getMethodType());
        }
        cg.addSink(callerFqn, calleeFqn, file, argToParams, expression);
    }

    /** The {@code bodyValue(..)}/{@code body(..)} link of a WebClient fluent chain, if present. */
    private static J.@Nullable MethodInvocation bodyLink(J.MethodInvocation retrieve) {
        Expression cur = retrieve.getSelect();
        while (cur instanceof J.MethodInvocation) {
            J.MethodInvocation link = (J.MethodInvocation) cur;
            if ("bodyValue".equals(link.getSimpleName()) || "body".equals(link.getSimpleName())) {
                return link;
            }
            cur = link.getSelect();
        }
        return null;
    }

    /** For each non-empty call argument, the caller-parameter positions it references. */
    private static Map<Integer, Set<Integer>> argRefs(List<Expression> args, @Nullable ParamRefs refs) {
        Map<Integer, Set<Integer>> result = new LinkedHashMap<>();
        if (refs == null) {
            return result;
        }
        for (int i = 0; i < args.size(); i++) {
            Expression a = args.get(i);
            if (a instanceof J.Empty) {
                continue;
            }
            Set<Integer> params = refs.refsOf(a);
            if (!params.isEmpty()) {
                result.put(i, params);
            }
        }
        return result;
    }

    /** Positions of the handler's inbound request-data parameters — the taint origins for a source. */
    private static Set<Integer> inboundOriginPositions(J.MethodDeclaration md) {
        Set<Integer> positions = new TreeSet<>();
        int idx = 0;
        for (Statement p : md.getParameters()) {
            if (p instanceof J.VariableDeclarations) {
                if (SpringMvcInbound.hasInboundParamAnnotation((J.VariableDeclarations) p)) {
                    positions.add(idx);
                }
                idx++;
            }
        }
        // A handler with no annotated params (e.g. a bare @RequestBody-less body): treat the first
        // parameter as the origin so its payload can still be followed.
        if (positions.isEmpty() && idx > 0) {
            positions.add(0);
        }
        return positions;
    }

    @Override
    public Collection<? extends SourceFile> generate(LineageAccumulator acc, ExecutionContext ctx) {
        List<DataFlowNode> nodes = acc.getNodes();
        nodes.sort(NODE_ORDER);
        for (DataFlowNode n : nodes) {
            ExternalIdentifier id = n.getExternalIdentifier();
            nodeTable.insertRow(ctx, new HttpDataNodeTable.Row(
                    n.getNodeId(),
                    n.getDirection().name(),
                    n.getFramework().name(),
                    id.getHttpMethod().name(),
                    id.getRouteTemplate(),
                    id.getResolution().name(),
                    id.getTargetAuthority() == null ? "" : id.getTargetAuthority(),
                    n.getPayloadType(),
                    n.isPayloadResolved(),
                    n.getLocator().getRepository(),
                    n.getLocator().getFilePath(),
                    n.getLocator().getMethodFqn(),
                    n.getLocator().getLine()));
        }

        // US2: reconstruct ordered source->sink chains from the repo-local call graph, sorted for
        // deterministic, referentially-intact output (I1, SC-004).
        CallGraph cg = acc.callGraph();
        // A Feign endpoint's SINK is its declaration; promote call sites invoking it into sink edges so
        // request data flowing into the Feign call is traced (now that every declaration is known).
        cg.promoteDeclarationSinks(feignSinkMethodFqns(nodes));
        List<CallChainEdge> chainRows = new ArrayList<>();
        for (CallGraph.Edge sinkEdge : cg.getSinkEdges()) {
            DataFlowNode sinkNode = findSinkNode(nodes, sinkEdge);
            if (sinkNode == null) {
                continue;
            }
            for (List<CallChainEdge> chain : cg.chainsTo(sinkEdge, sinkNode.getNodeId(), null)) {
                chainRows.addAll(chain);
            }
        }
        chainRows.sort(CHAIN_ORDER);
        for (CallChainEdge e : chainRows) {
            chainTable.insertRow(ctx, new DataFlowChainTable.Row(
                    e.getSourceNodeId(), e.getSinkNodeId(), e.getEdgeIndex(),
                    e.getFromMethodFqn(), e.getToMethodFqn(),
                    e.getCallSiteFile(), e.getCallSiteLine(),
                    e.getTaintedArgPositions(), e.isTaintedReturn()));
        }
        return Collections.emptyList();
    }

    /**
     * The catalog SINK node backing a call-graph sink edge. An EXPRESSION sink (RestTemplate/WebClient)
     * lives at the call site, matched by (enclosing method, printed expression); a DECLARATION sink
     * (Feign) lives at the endpoint declaration, matched by the resolved callee method FQN.
     */
    private static @Nullable DataFlowNode findSinkNode(List<DataFlowNode> nodes, CallGraph.Edge sinkEdge) {
        boolean declaration = sinkEdge.getSinkKind() == CallGraph.SinkKind.DECLARATION;
        for (DataFlowNode n : nodes) {
            if (n.getDirection() != Direction.SINK) {
                continue;
            }
            if (declaration) {
                if (n.getFramework() == Framework.FEIGN &&
                    n.getLocator().getMethodFqn().equals(sinkEdge.getCalleeFqn())) {
                    return n;
                }
            } else if (n.getLocator().getMethodFqn().equals(sinkEdge.getCallerFqn()) &&
                       n.getLocator().getExpression().equals(sinkEdge.getSinkExpression())) {
                return n;
            }
        }
        return null;
    }

    /** The method FQNs of every cataloged Feign endpoint declaration (chain-sink lookup keys). */
    private static Set<String> feignSinkMethodFqns(List<DataFlowNode> nodes) {
        Set<String> fqns = new HashSet<>();
        for (DataFlowNode n : nodes) {
            if (n.getDirection() == Direction.SINK && n.getFramework() == Framework.FEIGN) {
                fqns.add(n.getLocator().getMethodFqn());
            }
        }
        return fqns;
    }

    private static DataFlowNode buildNode(Cursor cursor, Detection d, String methodFqn, String expression) {
        return new DataFlowNode(d.getDirection(), d.getFramework(), d.getExternalIdentifier(),
                d.getPayloadType(), new DataFlowNode.Locator("", sourceFile(cursor), methodFqn, 0, expression));
    }

    private static String sourceFile(Cursor cursor) {
        JavaSourceFile cu = cursor.firstEnclosing(JavaSourceFile.class);
        return cu == null ? "" : cu.getSourcePath().toString();
    }

    private static final Comparator<DataFlowNode> NODE_ORDER = Comparator
            .comparing((DataFlowNode n) -> n.getLocator().getRepository())
            .thenComparing(n -> n.getLocator().getFilePath())
            .thenComparingInt(n -> n.getLocator().getLine())
            .thenComparing(n -> n.getDirection().name())
            .thenComparing(DataFlowNode::getNodeId);

    private static final Comparator<CallChainEdge> CHAIN_ORDER = Comparator
            .comparing(CallChainEdge::getSourceNodeId)
            .thenComparing(CallChainEdge::getSinkNodeId)
            .thenComparingInt(CallChainEdge::getEdgeIndex);

    private static String methodFqn(JavaType.Method mt) {
        if (mt == null) {
            return "";
        }
        JavaType.FullyQualified dt = mt.getDeclaringType();
        String owner = dt == null ? "" : dt.getFullyQualifiedName();
        return owner + "#" + mt.getName();
    }
}
