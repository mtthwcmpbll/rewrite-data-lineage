package com.snowfort.recipe.lineage.source;

import com.snowfort.recipe.lineage.model.HttpMethod;
import org.jspecify.annotations.Nullable;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.List;

/**
 * Shared extraction of Spring web request-mapping metadata (HTTP method, path template + resolution,
 * request-body payload type) from the {@code @RequestMapping}/{@code @GetMapping}/… family.
 *
 * <p>Both the inbound {@link SpringMvcSource} (controller handlers) and the outbound Feign sink use
 * the identical rules, which is what makes an inbound {@code POST /api/x} and an outbound Feign
 * {@code POST /api/x} produce the same cross-repo join key (SC-006). Keeping the rules here — rather
 * than duplicated per detector — also keeps the route-resolution semantics in one place.
 */
public final class RequestMappings {

    private static final String PKG = "org.springframework.web.bind.annotation.";

    // Meta-aware: catches @RequestMapping and the shorthand @GetMapping/@PostMapping/... and composed ones.
    private static final AnnotationMatcher REQUEST_MAPPING =
            new AnnotationMatcher("@" + PKG + "RequestMapping", true);
    private static final AnnotationMatcher REQUEST_BODY = new AnnotationMatcher("@" + PKG + "RequestBody");

    private RequestMappings() {
    }

    /** The first request-mapping annotation among {@code annotations} (meta-annotation aware), or null. */
    public static J.@Nullable Annotation firstMapping(List<J.Annotation> annotations) {
        for (J.Annotation a : annotations) {
            if (REQUEST_MAPPING.matches(a)) {
                return a;
            }
        }
        return null;
    }

    /** A resolved path contribution: its (possibly empty) literal value and whether it resolved. */
    public static final class PathPart {
        public static final PathPart RESOLVED_EMPTY = new PathPart("", true);
        public final String value;
        public final boolean resolved;

        public PathPart(String value, boolean resolved) {
            this.value = value;
            this.resolved = resolved;
        }
    }

    /**
     * Classify a mapping annotation's path contribution: a string-literal {@code value}/{@code path}
     * (or positional) argument resolves to that literal; no path argument at all resolves to empty
     * (the route comes from elsewhere); a path argument that is present but not a literal is
     * unresolved. A {@code null} annotation contributes empty.
     */
    public static PathPart pathPartOf(J.@Nullable Annotation a) {
        if (a == null || a.getArguments() == null || a.getArguments().isEmpty()) {
            return PathPart.RESOLVED_EMPTY;
        }
        boolean hasPathArgument = false;
        for (Expression arg : a.getArguments()) {
            if (arg instanceof J.Assignment) {
                J.Assignment asg = (J.Assignment) arg;
                if (asg.getVariable() instanceof J.Identifier) {
                    String name = ((J.Identifier) asg.getVariable()).getSimpleName();
                    if ("value".equals(name) || "path".equals(name)) {
                        hasPathArgument = true;
                        String v = literalString(asg.getAssignment());
                        if (v != null) {
                            return new PathPart(v, true);
                        }
                    }
                }
            } else {
                // A positional argument is the value/path attribute.
                hasPathArgument = true;
                String v = literalString(arg);
                if (v != null) {
                    return new PathPart(v, true);
                }
            }
        }
        // A path/value argument was present but not a string literal -> unresolved; otherwise the
        // annotation carried only non-path attributes (method=, produces=, …) -> empty path.
        return hasPathArgument ? new PathPart("", false) : PathPart.RESOLVED_EMPTY;
    }

    /** Map the mapping annotation to an HTTP method; UNKNOWN for a plain @RequestMapping with no method or a custom one. */
    public static HttpMethod httpMethodOf(J.Annotation mapping) {
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(mapping.getType());
        String simple = type == null ? mapping.getSimpleName() : type.getClassName();
        switch (simple) {
            case "GetMapping":
                return HttpMethod.GET;
            case "PostMapping":
                return HttpMethod.POST;
            case "PutMapping":
                return HttpMethod.PUT;
            case "DeleteMapping":
                return HttpMethod.DELETE;
            case "PatchMapping":
                return HttpMethod.PATCH;
            case "RequestMapping":
                return requestMappingMethodAttr(mapping);
            default:
                return HttpMethod.UNKNOWN;
        }
    }

    /**
     * The request-body payload type of a mapped method: the {@code @RequestBody} parameter's type
     * when present, else the first parameter's type, else empty (no resolvable payload).
     */
    public static String payloadType(J.MethodDeclaration md) {
        String firstParamType = null;
        for (Statement p : md.getParameters()) {
            if (!(p instanceof J.VariableDeclarations)) {
                continue;
            }
            J.VariableDeclarations vd = (J.VariableDeclarations) p;
            String type = typeName(vd.getType());
            if (firstParamType == null && type != null) {
                firstParamType = type;
            }
            for (J.Annotation ann : vd.getLeadingAnnotations()) {
                if (REQUEST_BODY.matches(ann)) {
                    return type == null ? "" : type;
                }
            }
        }
        return firstParamType == null ? "" : firstParamType;
    }

    private static HttpMethod requestMappingMethodAttr(J.Annotation mapping) {
        if (mapping.getArguments() == null) {
            return HttpMethod.UNKNOWN;
        }
        for (Expression arg : mapping.getArguments()) {
            if (arg instanceof J.Assignment) {
                J.Assignment asg = (J.Assignment) arg;
                if (asg.getVariable() instanceof J.Identifier &&
                    "method".equals(((J.Identifier) asg.getVariable()).getSimpleName())) {
                    return methodFromRequestMethod(asg.getAssignment());
                }
            }
        }
        return HttpMethod.UNKNOWN;
    }

    private static HttpMethod methodFromRequestMethod(Expression e) {
        Expression first = e;
        if (e instanceof J.NewArray && ((J.NewArray) e).getInitializer() != null &&
            !((J.NewArray) e).getInitializer().isEmpty()) {
            first = ((J.NewArray) e).getInitializer().get(0);
        }
        String name = null;
        if (first instanceof J.FieldAccess) {
            name = ((J.FieldAccess) first).getSimpleName();
        } else if (first instanceof J.Identifier) {
            name = ((J.Identifier) first).getSimpleName();
        }
        if (name == null) {
            return HttpMethod.UNKNOWN;
        }
        try {
            return HttpMethod.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return HttpMethod.UNKNOWN;
        }
    }

    /** A String literal, or the first element of a {@code {"a","b"}} array literal; else null. */
    public static @Nullable String literalString(Expression e) {
        Expression first = e;
        if (e instanceof J.NewArray && ((J.NewArray) e).getInitializer() != null &&
            !((J.NewArray) e).getInitializer().isEmpty()) {
            first = ((J.NewArray) e).getInitializer().get(0);
        }
        if (first instanceof J.Literal && ((J.Literal) first).getValue() instanceof String) {
            return (String) ((J.Literal) first).getValue();
        }
        return null;
    }

    private static @Nullable String typeName(@Nullable JavaType type) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        return fq == null ? null : fq.getFullyQualifiedName();
    }
}
