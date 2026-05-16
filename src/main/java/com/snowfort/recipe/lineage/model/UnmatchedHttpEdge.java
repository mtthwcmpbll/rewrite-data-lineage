package com.snowfort.recipe.lineage.model;

/**
 * One HTTP-out sink that did not join to any HTTP-in source. Tracking these
 * separately keeps the cross-repo join honest: gaps in the graph become
 * visible rows rather than silent drops.
 */
public record UnmatchedHttpEdge(
        String repo,
        String framework,
        String locator,
        String externalIdentifier,
        String payloadType,
        Confidence confidence,
        String reason
) {
}
