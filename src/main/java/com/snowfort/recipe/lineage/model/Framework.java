package com.snowfort.recipe.lineage.model;

/**
 * Which framework-specific detector matched a {@link DataFlowNode}: Spring MVC inbound endpoints, the
 * two Spring imperative HTTP clients, and declarative Spring Cloud OpenFeign outbound clients.
 */
public enum Framework {
    SPRING_MVC,
    REST_TEMPLATE,
    WEB_CLIENT,
    FEIGN
}
