package com.snowfort.recipe.lineage.model;

/**
 * One edge in the raw call chain: a method-to-method call along which tainted values flow.
 *
 * <p>List-shaped fields are serialized as comma-separated strings so that data table rows
 * remain flat for CSV emission. Consumers can split on {@code ,} to recover the original
 * sequence.
 */
public record CallChainEdge(
        String fromMethod,
        String toMethod,
        String callSite,
        String taintedArgPositions,
        boolean taintedReturn,
        String sourceRefs,
        String sinkRefs
) {
}
