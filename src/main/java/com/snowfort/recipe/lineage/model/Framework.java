package com.snowfort.recipe.lineage.model;

/**
 * Which framework-specific detector matched a {@link DataFlowNode}. Scoped to the MVP's supported
 * primitives: Spring MVC inbound endpoints and the two Spring imperative HTTP clients.
 */
public enum Framework {
    SPRING_MVC,
    REST_TEMPLATE,
    WEB_CLIENT
}
