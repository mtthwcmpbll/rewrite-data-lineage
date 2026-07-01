package com.snowfort.recipe.lineage.flow;

import com.snowfort.recipe.lineage.model.DataFlowNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-file state gathered during the scanning recipe's scan phase. For the catalog (User Story 1)
 * this holds every detected {@link DataFlowNode}; User Story 2 extends it with the inter-procedural
 * data-flow facts used to build chains.
 */
public final class LineageAccumulator {

    private final List<DataFlowNode> nodes = new ArrayList<>();

    public void addNode(DataFlowNode node) {
        nodes.add(node);
    }

    public List<DataFlowNode> getNodes() {
        return nodes;
    }
}
