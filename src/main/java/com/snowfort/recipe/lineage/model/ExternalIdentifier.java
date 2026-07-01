package com.snowfort.recipe.lineage.model;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * The normalized, comparable key that makes a {@link DataFlowNode} matchable across services.
 *
 * <p>{@code routeTemplate} is <em>path-only</em> for both inbound and outbound nodes (scheme, host
 * and query string excluded) so that {@code (httpMethod, routeTemplate)} compares equal for the same
 * logical route across repositories. For outbound calls the scheme+host/service authority is kept
 * separately in {@link #targetAuthority} so callee identity is preserved without polluting the join
 * key. See {@code data-model.md} and FR-004 / FR-005 / SC-006.
 */
public final class ExternalIdentifier {

    private final HttpMethod httpMethod;
    private final String routeTemplate;
    private final @Nullable String targetAuthority;
    private final Resolution resolution;

    public ExternalIdentifier(HttpMethod httpMethod, String routeTemplate,
                              @Nullable String targetAuthority, Resolution resolution) {
        this.httpMethod = httpMethod;
        this.routeTemplate = routeTemplate;
        this.targetAuthority = targetAuthority;
        this.resolution = resolution;
    }

    public HttpMethod getHttpMethod() {
        return httpMethod;
    }

    public String getRouteTemplate() {
        return routeTemplate;
    }

    /** Outbound scheme+host/service authority, or {@code null} for inbound endpoints. */
    public @Nullable String getTargetAuthority() {
        return targetAuthority;
    }

    public Resolution getResolution() {
        return resolution;
    }

    /**
     * The cross-repo join key: two nodes are matchable when they agree on method and path template.
     * Deliberately excludes {@link #targetAuthority} and {@link #resolution}.
     */
    public boolean matchesRoute(ExternalIdentifier other) {
        return httpMethod == other.httpMethod && routeTemplate.equals(other.routeTemplate);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ExternalIdentifier)) {
            return false;
        }
        ExternalIdentifier that = (ExternalIdentifier) o;
        return httpMethod == that.httpMethod &&
               routeTemplate.equals(that.routeTemplate) &&
               Objects.equals(targetAuthority, that.targetAuthority) &&
               resolution == that.resolution;
    }

    @Override
    public int hashCode() {
        return Objects.hash(httpMethod, routeTemplate, targetAuthority, resolution);
    }

    @Override
    public String toString() {
        return httpMethod + " " + routeTemplate +
               (targetAuthority == null ? "" : " @" + targetAuthority) +
               " (" + resolution + ")";
    }
}
