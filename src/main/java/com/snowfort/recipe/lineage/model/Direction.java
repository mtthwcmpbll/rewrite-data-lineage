package com.snowfort.recipe.lineage.model;

/**
 * Whether a {@link DataFlowNode} is a point where external data enters the service (a source /
 * inbound endpoint) or leaves it (a sink / outbound call).
 */
public enum Direction {
    SOURCE,
    SINK
}
