package com.snowfort.recipe.lineage.model;

/**
 * HTTP method carried by an {@link ExternalIdentifier}. {@link #UNKNOWN} is used when the method
 * cannot be resolved (e.g. a {@code RestTemplate.exchange} with a non-constant {@code HttpMethod}).
 */
public enum HttpMethod {
    GET,
    POST,
    PUT,
    DELETE,
    PATCH,
    HEAD,
    OPTIONS,
    UNKNOWN
}
