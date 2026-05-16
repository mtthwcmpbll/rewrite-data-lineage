package com.snowfort.recipe.lineage.table;

import com.snowfort.recipe.lineage.model.CallChainEdge;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class CallChainEdgeTable extends DataTable<CallChainEdge> {

    public CallChainEdgeTable(Recipe recipe) {
        super(recipe,
                "Call-chain edges",
                "One row per dataflow edge between methods along source-reachable, sink-reachable paths.");
    }
}
