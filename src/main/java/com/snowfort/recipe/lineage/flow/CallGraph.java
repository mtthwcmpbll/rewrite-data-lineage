package com.snowfort.recipe.lineage.flow;

import com.snowfort.recipe.lineage.model.CallChainEdge;
import com.snowfort.recipe.lineage.model.DataFlowNode;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Repo-local call graph accumulated during the scan phase, plus the position-aware fixed-point
 * propagation that reconstructs ordered source&rarr;sink chains (tasks T026/T027, research R2).
 *
 * <p>Each call site is stored as an {@link Edge} carrying, per caller-argument position, the set of
 * enclosing-method parameter positions that argument references (from {@link ParamRefs}). A call
 * argument at position {@code a} fills the callee's parameter position {@code a}, so taint propagates
 * across a call when a tainted parameter is referenced by an argument. Propagation runs forward from a
 * source handler's inbound-parameter positions to a fixed point; a sink is reachable when a tainted
 * parameter reaches one of its payload-argument positions. Monotone over a finite lattice, so it
 * terminates and is deterministic (Principle IV).
 */
public final class CallGraph {

    /** A single call site: either an in-repo method call or an outbound HTTP sink call. */
    public static final class Edge {
        final String callerFqn;
        final String calleeFqn;
        final boolean sink;
        final String callSiteFile;
        /** caller-argument position -> caller parameter positions that argument references. */
        final Map<Integer, Set<Integer>> argToCallerParams;
        /** Sink edges only: the printed sink expression, used to recompute the catalog nodeId. */
        final @Nullable String sinkExpression;

        Edge(String callerFqn, String calleeFqn, boolean sink, String callSiteFile,
             Map<Integer, Set<Integer>> argToCallerParams, @Nullable String sinkExpression) {
            this.callerFqn = callerFqn;
            this.calleeFqn = calleeFqn;
            this.sink = sink;
            this.callSiteFile = callSiteFile;
            this.argToCallerParams = argToCallerParams;
            this.sinkExpression = sinkExpression;
        }

        public boolean isSink() {
            return sink;
        }

        public String getCallerFqn() {
            return callerFqn;
        }

        public @Nullable String getSinkExpression() {
            return sinkExpression;
        }
    }

    private final Map<String, List<Edge>> edgesByCaller = new LinkedHashMap<>();
    private final List<Edge> sinkEdges = new ArrayList<>();
    /** Every method declaration FQN seen — used to tell in-repo callees from library calls. */
    private final Set<String> declaredMethods = new TreeSet<>();
    /** Source handler FQN -> inbound (taint-origin) parameter positions. */
    private final Map<String, Set<Integer>> sourceOrigins = new LinkedHashMap<>();
    /** Source handler FQN -> its catalog node (for referential integrity on emit). */
    private final Map<String, DataFlowNode> sourceNodes = new LinkedHashMap<>();

    public void declareMethod(String fqn) {
        declaredMethods.add(fqn);
    }

    public void addCall(String callerFqn, String calleeFqn, String callSiteFile,
                        Map<Integer, Set<Integer>> argToCallerParams) {
        edgesByCaller.computeIfAbsent(callerFqn, k -> new ArrayList<>())
                .add(new Edge(callerFqn, calleeFqn, false, callSiteFile, argToCallerParams, null));
    }

    public Edge addSink(String callerFqn, String calleeFqn, String callSiteFile,
                        Map<Integer, Set<Integer>> argToCallerParams, String sinkExpression) {
        Edge edge = new Edge(callerFqn, calleeFqn, true, callSiteFile, argToCallerParams, sinkExpression);
        edgesByCaller.computeIfAbsent(callerFqn, k -> new ArrayList<>()).add(edge);
        sinkEdges.add(edge);
        return edge;
    }

    public void addSource(String fqn, Set<Integer> originPositions, DataFlowNode node) {
        sourceOrigins.put(fqn, new TreeSet<>(originPositions));
        sourceNodes.put(fqn, node);
    }

    /**
     * Reconstruct every ordered source&rarr;sink chain terminating at {@code sinkEdge}, one list of
     * {@link CallChainEdge}s per reaching source. {@code sinkNodeId} anchors the sink end; {@code gate}
     * (when non-null) restricts terminal tainted positions to those an external oracle also confirmed
     * (the {@code GlobalDataFlow} RestTemplate gate) — pass {@code null} to trust propagation alone.
     */
    public List<List<CallChainEdge>> chainsTo(Edge sinkEdge, String sinkNodeId, @Nullable Set<Integer> gate) {
        List<List<CallChainEdge>> chains = new ArrayList<>();
        for (Map.Entry<String, Set<Integer>> src : sourceOrigins.entrySet()) {
            String srcFqn = src.getKey();
            Propagation p = propagateFrom(srcFqn, src.getValue());
            Set<Integer> taintedAtSink = p.tainted.get(sinkEdge.callerFqn);
            if (taintedAtSink == null) {
                continue;
            }
            Set<Integer> terminalPositions = new TreeSet<>();
            for (Map.Entry<Integer, Set<Integer>> arg : sinkEdge.argToCallerParams.entrySet()) {
                boolean tainted = !Collections.disjoint(arg.getValue(), taintedAtSink);
                boolean allowed = gate == null || gate.contains(arg.getKey());
                if (tainted && allowed) {
                    terminalPositions.add(arg.getKey());
                }
            }
            if (terminalPositions.isEmpty()) {
                continue;
            }
            chains.add(buildChain(p, srcFqn, sinkEdge, sinkNodeId, terminalPositions));
        }
        return chains;
    }

    private List<CallChainEdge> buildChain(Propagation p, String srcFqn, Edge sinkEdge,
                                           String sinkNodeId, Set<Integer> terminalPositions) {
        String sourceNodeId = sourceNodes.get(srcFqn).getNodeId();

        // Walk parent pointers from the sink-containing method back to the source, then reverse.
        List<Edge> intermediate = new ArrayList<>();
        String cur = sinkEdge.callerFqn;
        while (!cur.equals(srcFqn)) {
            Edge parent = p.parentEdge.get(cur);
            if (parent == null) {
                break;
            }
            intermediate.add(parent);
            cur = p.parentMethod.get(cur);
        }
        Collections.reverse(intermediate);

        List<CallChainEdge> chain = new ArrayList<>();
        int edgeIndex = 0;
        for (Edge e : intermediate) {
            Set<Integer> callerTaint = p.tainted.getOrDefault(e.callerFqn, Collections.emptySet());
            chain.add(new CallChainEdge(sourceNodeId, sinkNodeId, edgeIndex++,
                    e.callerFqn, e.calleeFqn, e.callSiteFile, 0,
                    joinPositions(taintedPositions(e, callerTaint)), false));
        }
        chain.add(new CallChainEdge(sourceNodeId, sinkNodeId, edgeIndex,
                sinkEdge.callerFqn, sinkEdge.calleeFqn, sinkEdge.callSiteFile, 0,
                joinPositions(terminalPositions), false));
        return chain;
    }

    private Propagation propagateFrom(String srcFqn, Set<Integer> origins) {
        Propagation p = new Propagation();
        p.tainted.put(srcFqn, new TreeSet<>(origins));
        Deque<String> work = new ArrayDeque<>();
        work.add(srcFqn);
        while (!work.isEmpty()) {
            String method = work.poll();
            Set<Integer> taint = p.tainted.get(method);
            for (Edge e : edgesByCaller.getOrDefault(method, Collections.emptyList())) {
                if (e.sink || !declaredMethods.contains(e.calleeFqn)) {
                    continue;
                }
                Set<Integer> calleeTaint = taintedPositions(e, taint);
                if (calleeTaint.isEmpty()) {
                    continue;
                }
                Set<Integer> existing = p.tainted.get(e.calleeFqn);
                boolean firstVisit = existing == null;
                if (firstVisit) {
                    existing = new TreeSet<>();
                    p.tainted.put(e.calleeFqn, existing);
                    p.parentEdge.put(e.calleeFqn, e);
                    p.parentMethod.put(e.calleeFqn, method);
                }
                if (existing.addAll(calleeTaint) || firstVisit) {
                    work.add(e.calleeFqn);
                }
            }
        }
        return p;
    }

    /** Caller-argument positions of {@code e} whose referenced parameters intersect {@code taint}. */
    private static Set<Integer> taintedPositions(Edge e, Set<Integer> taint) {
        Set<Integer> positions = new TreeSet<>();
        for (Map.Entry<Integer, Set<Integer>> arg : e.argToCallerParams.entrySet()) {
            if (!Collections.disjoint(arg.getValue(), taint)) {
                positions.add(arg.getKey());
            }
        }
        return positions;
    }

    private static String joinPositions(Set<Integer> positions) {
        StringBuilder sb = new StringBuilder();
        for (Integer pos : positions) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(pos);
        }
        return sb.toString();
    }

    public List<Edge> getSinkEdges() {
        return sinkEdges;
    }

    /** Forward-propagation state from a single source: tainted parameters + first-reach parent tree. */
    private static final class Propagation {
        final Map<String, Set<Integer>> tainted = new HashMap<>();
        final Map<String, Edge> parentEdge = new HashMap<>();
        final Map<String, String> parentMethod = new HashMap<>();
    }
}
