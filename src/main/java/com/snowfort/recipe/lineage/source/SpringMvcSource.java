package com.snowfort.recipe.lineage.source;

import com.snowfort.recipe.lineage.model.Detection;
import com.snowfort.recipe.lineage.model.Direction;
import com.snowfort.recipe.lineage.model.ExternalIdentifier;
import com.snowfort.recipe.lineage.model.Framework;
import com.snowfort.recipe.lineage.model.HttpMethod;
import com.snowfort.recipe.lineage.model.Resolution;
import com.snowfort.recipe.lineage.model.Routes;
import org.jspecify.annotations.Nullable;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.List;

/**
 * Detects Spring MVC inbound endpoints: a handler method on a controller class carrying a request
 * mapping. Emits exactly one SOURCE {@link Detection} per handler (data-model.md). The request-body
 * parameter (or, absent one, the first inbound parameter) supplies the payload type; all inbound
 * parameters are treated elsewhere as taint origins (User Story 2).
 */
public final class SpringMvcSource {

    private static final String PKG = "org.springframework.web.bind.annotation.";

    // Meta-aware: catches @Controller, @RestController, and custom stereotypes composed from them.
    private static final AnnotationMatcher CONTROLLER =
            new AnnotationMatcher("@org.springframework.stereotype.Controller", true);
    // Meta-aware: catches @RequestMapping and the shorthand @GetMapping/@PostMapping/... and composed ones.
    private static final AnnotationMatcher REQUEST_MAPPING =
            new AnnotationMatcher("@" + PKG + "RequestMapping", true);

    private static final AnnotationMatcher REQUEST_BODY = new AnnotationMatcher("@" + PKG + "RequestBody");
    private static final AnnotationMatcher REQUEST_PARAM = new AnnotationMatcher("@" + PKG + "RequestParam");
    private static final AnnotationMatcher PATH_VARIABLE = new AnnotationMatcher("@" + PKG + "PathVariable");
    private static final AnnotationMatcher REQUEST_HEADER = new AnnotationMatcher("@" + PKG + "RequestHeader");

    /**
     * @return a SOURCE detection if {@code md} is a controller handler, else {@code null}.
     */
    public @Nullable Detection detect(J.MethodDeclaration md, J.@Nullable ClassDeclaration enclosing) {
        if (enclosing == null || !isController(enclosing)) {
            return null;
        }
        J.Annotation mapping = firstMapping(md.getLeadingAnnotations());
        if (mapping == null) {
            return null;
        }

        // The full route is the class-level prefix joined with the method-level path. A route is EXACT
        // only when BOTH parts resolve — where "no path argument" resolves to an empty contribution
        // (e.g. @PostMapping inheriting a class-level @RequestMapping), distinct from a path argument
        // that is present but not a string literal (e.g. a constant reference), which is UNKNOWN.
        PathPart methodPart = pathPartOf(mapping);
        PathPart classPart = pathPartOf(firstMapping(enclosing.getLeadingAnnotations()));
        Resolution resolution = classPart.resolved && methodPart.resolved
                ? Resolution.EXACT : Resolution.UNKNOWN;
        String route = Routes.join(classPart.value, methodPart.value);

        HttpMethod httpMethod = httpMethodOf(mapping);
        ExternalIdentifier id = new ExternalIdentifier(httpMethod, route, null, resolution);
        String payloadType = payloadType(md);
        return new Detection(Direction.SOURCE, Framework.SPRING_MVC, id, payloadType);
    }

    private boolean isController(J.ClassDeclaration cd) {
        for (J.Annotation a : cd.getLeadingAnnotations()) {
            if (CONTROLLER.matches(a)) {
                return true;
            }
        }
        return false;
    }

    private J.@Nullable Annotation firstMapping(List<J.Annotation> annotations) {
        for (J.Annotation a : annotations) {
            if (REQUEST_MAPPING.matches(a)) {
                return a;
            }
        }
        return null;
    }

    /** A resolved path contribution: its (possibly empty) literal value and whether it resolved. */
    private static final class PathPart {
        static final PathPart RESOLVED_EMPTY = new PathPart("", true);
        final String value;
        final boolean resolved;

        PathPart(String value, boolean resolved) {
            this.value = value;
            this.resolved = resolved;
        }
    }

    /**
     * Classify a mapping annotation's path contribution: a string-literal {@code value}/{@code path}
     * (or positional) argument resolves to that literal; no path argument at all resolves to empty
     * (the route comes from elsewhere); a path argument that is present but not a literal is
     * unresolved. A {@code null} annotation (no class-level mapping) contributes empty.
     */
    private PathPart pathPartOf(J.@Nullable Annotation a) {
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
    private HttpMethod httpMethodOf(J.Annotation mapping) {
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

    private HttpMethod requestMappingMethodAttr(J.Annotation mapping) {
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

    private HttpMethod methodFromRequestMethod(Expression e) {
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

    private @Nullable String literalString(Expression e) {
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

    private String payloadType(J.MethodDeclaration md) {
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

    private static @Nullable String typeName(@Nullable JavaType type) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        return fq == null ? null : fq.getFullyQualifiedName();
    }
}
