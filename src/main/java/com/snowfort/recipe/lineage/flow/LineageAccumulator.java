package com.snowfort.recipe.lineage.flow;

import com.snowfort.recipe.lineage.model.DataFlowNode;
import org.openrewrite.analysis.dataflow.global.GlobalDataFlow;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-file state gathered during the scanning recipe's scan phase. Holds every detected
 * {@link DataFlowNode} (User Story 1 catalog), the repo-local {@link CallGraph} used to reconstruct
 * ordered source&rarr;sink chains (User Story 2), and the {@link GlobalDataFlow.Accumulator} that
 * gates RestTemplate chains on inter-procedural reachability.
 */
public final class LineageAccumulator {

    private final List<DataFlowNode> nodes = new ArrayList<>();
    private final CallGraph callGraph = new CallGraph();
    private final GlobalDataFlow.Accumulator global;

    public LineageAccumulator(GlobalDataFlow.Accumulator global) {
        this.global = global;
    }

    public void addNode(DataFlowNode node) {
        nodes.add(node);
    }

    public List<DataFlowNode> getNodes() {
        return nodes;
    }

    /** The repo-local call graph + taint propagation backing chain reconstruction (User Story 2). */
    public CallGraph callGraph() {
        return callGraph;
    }

    /** The inter-procedural data-flow accumulator; query reachability via {@code isSource/isSink(cursor)}. */
    public GlobalDataFlow.Accumulator global() {
        return global;
    }
}
