package com.snowfort.recipe.lineage.table;

import com.snowfort.recipe.lineage.model.HttpEdge;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class HttpEdgeTable extends DataTable<HttpEdge> {

    public HttpEdgeTable(Recipe recipe) {
        super(recipe,
                "HTTP edges between services",
                "One row per matched HTTP-out sink to HTTP-in source pair, keyed by the " +
                        "normalized VERB and path-template.");
    }
}
