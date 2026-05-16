package com.snowfort.recipe.lineage.model;

/**
 * A point where data of external provenance either enters or leaves a service.
 *
 * <p>The {@code externalIdentifier} is what makes cross-repo joining tractable: an HTTP-out
 * {@code POST /orders} in one repo matches a {@code @PostMapping("/orders")} in another;
 * a Kafka producer to {@code payments.v1} matches any consumer of that topic.
 */
public record DataFlowNode(
        NodeKind kind,
        String framework,
        String locator,
        String externalIdentifier,
        String payloadType,
        String payloadSchemaRef,
        Confidence confidence
) {
}
