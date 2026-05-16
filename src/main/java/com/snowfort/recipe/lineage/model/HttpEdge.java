package com.snowfort.recipe.lineage.model;

/**
 * One matched HTTP edge: a {@link NodeKind#SINK} call site joined to a
 * {@link NodeKind#SOURCE} endpoint by normalized {@code "VERB /path"}.
 *
 * <p>{@code from*} fields describe the caller (the sink); {@code to*} fields
 * describe the receiver (the source).
 */
public record HttpEdge(
        String fromRepo,
        String fromFramework,
        String fromLocator,
        String toRepo,
        String toFramework,
        String toLocator,
        String externalIdentifier,
        String payloadType
) {
}
