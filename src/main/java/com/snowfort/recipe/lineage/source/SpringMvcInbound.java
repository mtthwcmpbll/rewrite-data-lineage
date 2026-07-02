package com.snowfort.recipe.lineage.source;

import org.openrewrite.Cursor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.tree.J;

/**
 * Shared predicates for recognizing Spring MVC inbound endpoints and their request-data parameters.
 * Used both by {@link SpringMvcSource} (node detection) and by the data-flow spec that seeds
 * inter-procedural taint from inbound parameters (User Story 2).
 */
public final class SpringMvcInbound {

    private static final String PKG = "org.springframework.web.bind.annotation.";

    private static final AnnotationMatcher CONTROLLER =
            new AnnotationMatcher("@org.springframework.stereotype.Controller", true);
    private static final AnnotationMatcher REQUEST_MAPPING =
            new AnnotationMatcher("@" + PKG + "RequestMapping", true);

    private static final AnnotationMatcher[] INBOUND_PARAM = {
            new AnnotationMatcher("@" + PKG + "RequestBody"),
            new AnnotationMatcher("@" + PKG + "RequestParam"),
            new AnnotationMatcher("@" + PKG + "PathVariable"),
            new AnnotationMatcher("@" + PKG + "RequestHeader"),
    };

    private SpringMvcInbound() {
    }

    public static boolean isController(J.ClassDeclaration cd) {
        for (J.Annotation a : cd.getLeadingAnnotations()) {
            if (CONTROLLER.matches(a)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isHandler(J.MethodDeclaration md) {
        for (J.Annotation a : md.getLeadingAnnotations()) {
            if (REQUEST_MAPPING.matches(a)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasInboundParamAnnotation(J.VariableDeclarations param) {
        for (J.Annotation a : param.getLeadingAnnotations()) {
            for (AnnotationMatcher m : INBOUND_PARAM) {
                if (m.matches(a)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True when {@code cursor} sits on an inbound request-data parameter of a controller handler —
     * the taint origin for inter-procedural flow. Fires when the cursor is at (or inside) the
     * parameter declaration of a {@code @RequestBody}/{@code @RequestParam}/… argument.
     */
    public static boolean isInboundParameter(Cursor cursor) {
        J.VariableDeclarations param = cursor.firstEnclosing(J.VariableDeclarations.class);
        if (param == null || !hasInboundParamAnnotation(param)) {
            return false;
        }
        J.MethodDeclaration md = cursor.firstEnclosing(J.MethodDeclaration.class);
        J.ClassDeclaration cd = cursor.firstEnclosing(J.ClassDeclaration.class);
        return md != null && cd != null && isHandler(md) && isController(cd);
    }
}
