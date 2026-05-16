package com.snowfort.recipe.lineage.table;

import com.snowfort.recipe.lineage.model.UnmatchedHttpEdge;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class UnmatchedHttpEdgeTable extends DataTable<UnmatchedHttpEdge> {

    public UnmatchedHttpEdgeTable(Recipe recipe) {
        super(recipe,
                "Unmatched HTTP-out sinks",
                "Outbound HTTP call sites that did not join to any inbound endpoint, " +
                        "with the reason for the miss (unknown URL, no matching peer, etc.).");
    }
}
