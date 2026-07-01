package com.snowfort.recipe.lineage.model;

/**
 * One edge on an ordered source&rarr;sink path: the payload moving from one method to the next. A
 * full chain is the ordered set of edges sharing a {@code (sourceNodeId, sinkNodeId)} pair, ordered
 * by {@link #edgeIndex}. Chain extraction references {@link DataFlowNode}s only by id, keeping it
 * decoupled from node detection (constitution Principle III).
 */
public final class CallChainEdge {

    private final String sourceNodeId;
    private final String sinkNodeId;
    private final int edgeIndex;
    private final String fromMethodFqn;
    private final String toMethodFqn;
    private final String callSiteFile;
    private final int callSiteLine;
    private final String taintedArgPositions;
    private final boolean taintedReturn;

    public CallChainEdge(String sourceNodeId, String sinkNodeId, int edgeIndex,
                         String fromMethodFqn, String toMethodFqn,
                         String callSiteFile, int callSiteLine,
                         String taintedArgPositions, boolean taintedReturn) {
        this.sourceNodeId = sourceNodeId;
        this.sinkNodeId = sinkNodeId;
        this.edgeIndex = edgeIndex;
        this.fromMethodFqn = fromMethodFqn;
        this.toMethodFqn = toMethodFqn;
        this.callSiteFile = callSiteFile;
        this.callSiteLine = callSiteLine;
        this.taintedArgPositions = taintedArgPositions;
        this.taintedReturn = taintedReturn;
    }

    public String getSourceNodeId() {
        return sourceNodeId;
    }

    public String getSinkNodeId() {
        return sinkNodeId;
    }

    public int getEdgeIndex() {
        return edgeIndex;
    }

    public String getFromMethodFqn() {
        return fromMethodFqn;
    }

    public String getToMethodFqn() {
        return toMethodFqn;
    }

    public String getCallSiteFile() {
        return callSiteFile;
    }

    public int getCallSiteLine() {
        return callSiteLine;
    }

    public String getTaintedArgPositions() {
        return taintedArgPositions;
    }

    public boolean isTaintedReturn() {
        return taintedReturn;
    }
}
