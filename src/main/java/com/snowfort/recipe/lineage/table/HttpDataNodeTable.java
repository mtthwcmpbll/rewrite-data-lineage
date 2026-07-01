package com.snowfort.recipe.lineage.table;

import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

/**
 * One row per inbound HTTP endpoint (source) and outbound HTTP call (sink) discovered in the
 * repository. Catalog output for User Story 1. See {@code contracts/data-tables.md}.
 */
public class HttpDataNodeTable extends DataTable<HttpDataNodeTable.Row> {

    public HttpDataNodeTable(Recipe recipe) {
        super(recipe,
                "HTTP data nodes (sources & sinks)",
                "Every inbound HTTP endpoint (source) and outbound HTTP call (sink) discovered in the " +
                "repository, one row each.");
    }

    public static class Row {
        @Column(displayName = "Node ID",
                description = "Stable synthetic id; referenced by the data-flow chain table.")
        private final String nodeId;

        @Column(displayName = "Direction", description = "SOURCE (inbound) or SINK (outbound).")
        private final String direction;

        @Column(displayName = "Framework", description = "SPRING_MVC, REST_TEMPLATE, or WEB_CLIENT.")
        private final String framework;

        @Column(displayName = "HTTP method", description = "GET/POST/... or UNKNOWN.")
        private final String httpMethod;

        @Column(displayName = "Route template", description = "Normalized path-only route, e.g. /orders/{id}.")
        private final String routeTemplate;

        @Column(displayName = "Route resolution", description = "EXACT / PARTIAL / UNKNOWN.")
        private final String routeResolution;

        @Column(displayName = "Target authority",
                description = "Outbound scheme+host/service (e.g. inventory); blank for inbound.")
        private final String targetAuthority;

        @Column(displayName = "Payload type", description = "FQN of the in/out type, or <unknown>.")
        private final String payloadType;

        @Column(displayName = "Payload resolved", description = "False when the payload type is unresolved.")
        private final boolean payloadResolved;

        @Column(displayName = "Repository", description = "Repository/origin identifier.")
        private final String repository;

        @Column(displayName = "File", description = "Source-relative file path.")
        private final String filePath;

        @Column(displayName = "Method", description = "Declaring method signature.")
        private final String methodFqn;

        @Column(displayName = "Line", description = "Line of the endpoint/call (0 if unavailable).")
        private final int line;

        public Row(String nodeId, String direction, String framework, String httpMethod,
                   String routeTemplate, String routeResolution, String targetAuthority,
                   String payloadType, boolean payloadResolved, String repository, String filePath,
                   String methodFqn, int line) {
            this.nodeId = nodeId;
            this.direction = direction;
            this.framework = framework;
            this.httpMethod = httpMethod;
            this.routeTemplate = routeTemplate;
            this.routeResolution = routeResolution;
            this.targetAuthority = targetAuthority;
            this.payloadType = payloadType;
            this.payloadResolved = payloadResolved;
            this.repository = repository;
            this.filePath = filePath;
            this.methodFqn = methodFqn;
            this.line = line;
        }

        public String getNodeId() {
            return nodeId;
        }

        public String getDirection() {
            return direction;
        }

        public String getFramework() {
            return framework;
        }

        public String getHttpMethod() {
            return httpMethod;
        }

        public String getRouteTemplate() {
            return routeTemplate;
        }

        public String getRouteResolution() {
            return routeResolution;
        }

        public String getTargetAuthority() {
            return targetAuthority;
        }

        public String getPayloadType() {
            return payloadType;
        }

        public boolean isPayloadResolved() {
            return payloadResolved;
        }

        public String getRepository() {
            return repository;
        }

        public String getFilePath() {
            return filePath;
        }

        public String getMethodFqn() {
            return methodFqn;
        }

        public int getLine() {
            return line;
        }
    }
}
