package com.snowfort.recipe.lineage;

import com.snowfort.recipe.lineage.table.DataFlowChainTable;
import com.snowfort.recipe.lineage.table.HttpDataNodeTable;
import org.openrewrite.RecipeRun;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.util.List;

/**
 * Shared base for lineage recipe tests: activates {@link FindHttpDataLineage} and puts the Spring web
 * types on the parser classpath so fixtures type-resolve (research R7). Without these, the
 * type-aware matchers silently no-op.
 */
public abstract class LineageRecipeTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FindHttpDataLineage())
                .parser(JavaParser.fromJavaVersion().classpath(
                        "spring-web", "spring-webflux", "spring-beans", "spring-context",
                        "spring-core", "reactor-core"));
    }

    /**
     * The catalog rows produced by a run. Returns an empty list when no node was emitted — OpenRewrite
     * omits a data table entirely when zero rows are inserted, so this is the reliable way to assert
     * the negative case.
     */
    protected static List<HttpDataNodeTable.Row> nodeRows(RecipeRun run) {
        return run.getDataTableRows(HttpDataNodeTable.class);
    }

    /** The data-flow chain edges produced by a run (empty when none). */
    protected static List<DataFlowChainTable.Row> chainRows(RecipeRun run) {
        return run.getDataTableRows(DataFlowChainTable.class);
    }
}
