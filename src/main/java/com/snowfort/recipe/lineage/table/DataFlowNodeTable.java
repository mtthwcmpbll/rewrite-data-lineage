package com.snowfort.recipe.lineage.table;

import com.snowfort.recipe.lineage.model.DataFlowNode;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class DataFlowNodeTable extends DataTable<DataFlowNode> {

    public DataFlowNodeTable(Recipe recipe) {
        super(recipe,
                "Data-flow source/sink nodes",
                "One row for every source or sink discovered while analyzing the codebase.");
    }
}
