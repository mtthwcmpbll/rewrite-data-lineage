package com.snowfort.recipe.lineage;

import com.snowfort.recipe.lineage.table.CallChainEdgeTable;
import com.snowfort.recipe.lineage.table.DataFlowNodeTable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.TreeVisitor;

/**
 * Umbrella scanning recipe for the data-lineage pipeline.
 *
 * <p>At this stage the recipe is a no-op: it registers the two backing data tables
 * ({@link DataFlowNodeTable} and {@link CallChainEdgeTable}) so that downstream
 * recipes can write to a stable, shared output schema, and serves as the entry
 * point that later MVPs extend with per-framework source/sink detection,
 * inter-procedural propagation, and cross-repo edge joining.
 */
public class FindDataLineage extends ScanningRecipe<FindDataLineage.Accumulator> {

    transient DataFlowNodeTable dataFlowNodes = new DataFlowNodeTable(this);
    transient CallChainEdgeTable callChainEdges = new CallChainEdgeTable(this);

    @Override
    public String getDisplayName() {
        return "Find data lineage";
    }

    @Override
    public String getDescription() {
        return "Aggregates data-lineage findings (sources, sinks, and call-chain edges) " +
                "across a codebase. Initially a no-op that registers the output data tables; " +
                "framework-specific detection recipes are layered on top in subsequent MVPs.";
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return TreeVisitor.noop();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return TreeVisitor.noop();
    }

    public static final class Accumulator {
    }
}
