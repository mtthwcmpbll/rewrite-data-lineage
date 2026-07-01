package com.snowfort.recipe.lineage.model;

/**
 * The framework-specific result of matching a source or sink, before location is attached. The
 * scanning recipe combines this with a {@link DataFlowNode.Locator} to build the final node. Keeping
 * detection separate from location keeps the detectors independently testable (Principle II).
 */
public final class Detection {

    private final Direction direction;
    private final Framework framework;
    private final ExternalIdentifier externalIdentifier;
    private final String payloadType;

    public Detection(Direction direction, Framework framework,
                     ExternalIdentifier externalIdentifier, String payloadType) {
        this.direction = direction;
        this.framework = framework;
        this.externalIdentifier = externalIdentifier;
        this.payloadType = payloadType;
    }

    public Direction getDirection() {
        return direction;
    }

    public Framework getFramework() {
        return framework;
    }

    public ExternalIdentifier getExternalIdentifier() {
        return externalIdentifier;
    }

    public String getPayloadType() {
        return payloadType;
    }
}
