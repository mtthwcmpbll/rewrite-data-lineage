package com.snowfort.recipe.lineage.table;

import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

/**
 * One row per edge on an inbound-source &rarr; outbound-sink path where request data reaches the
 * outbound call. Lineage output for User Story 2. Join to {@link HttpDataNodeTable} on
 * {@code sourceNodeId} / {@code sinkNodeId}. See {@code contracts/data-tables.md}.
 */
public class DataFlowChainTable extends DataTable<DataFlowChainTable.Row> {

    public DataFlowChainTable(Recipe recipe) {
        super(recipe,
                "HTTP data-flow chains (source -> sink)",
                "One row per edge on an inbound-source to outbound-sink path where request data " +
                "reaches the outbound call.");
    }

    public static class Row {
        @Column(displayName = "Source node ID", description = "FK to the inbound source node.")
        private final String sourceNodeId;

        @Column(displayName = "Sink node ID", description = "FK to the outbound sink node.")
        private final String sinkNodeId;

        @Column(displayName = "Edge index", description = "0-based position along the path.")
        private final int edgeIndex;

        @Column(displayName = "From method", description = "Caller method signature.")
        private final String fromMethodFqn;

        @Column(displayName = "To method", description = "Callee method signature.")
        private final String toMethodFqn;

        @Column(displayName = "Call-site file", description = "File of the call expression.")
        private final String callSiteFile;

        @Column(displayName = "Call-site line", description = "Line of the call expression (0 if unavailable).")
        private final int callSiteLine;

        @Column(displayName = "Tainted arg positions",
                description = "Comma-joined caller arg indices carrying taint; empty if via receiver.")
        private final String taintedArgPositions;

        @Column(displayName = "Tainted return", description = "Callee return carries taint back.")
        private final boolean taintedReturn;

        public Row(String sourceNodeId, String sinkNodeId, int edgeIndex, String fromMethodFqn,
                   String toMethodFqn, String callSiteFile, int callSiteLine,
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
}
