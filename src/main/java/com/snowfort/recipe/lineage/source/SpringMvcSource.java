package com.snowfort.recipe.lineage.source;

import com.snowfort.recipe.lineage.model.Detection;
import com.snowfort.recipe.lineage.model.Direction;
import com.snowfort.recipe.lineage.model.ExternalIdentifier;
import com.snowfort.recipe.lineage.model.Framework;
import com.snowfort.recipe.lineage.model.HttpMethod;
import com.snowfort.recipe.lineage.model.Resolution;
import com.snowfort.recipe.lineage.model.Routes;
import com.snowfort.recipe.lineage.source.RequestMappings.PathPart;
import org.jspecify.annotations.Nullable;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.tree.J;

/**
 * Detects Spring MVC inbound endpoints: a handler method on a controller class carrying a request
 * mapping. Emits exactly one SOURCE {@link Detection} per handler (data-model.md). The request-body
 * parameter (or, absent one, the first inbound parameter) supplies the payload type; all inbound
 * parameters are treated elsewhere as taint origins (User Story 2). Mapping metadata is extracted via
 * the shared {@link RequestMappings} so inbound routes normalize identically to outbound Feign ones.
 */
public final class SpringMvcSource {

    // Meta-aware: catches @Controller, @RestController, and custom stereotypes composed from them.
    private static final AnnotationMatcher CONTROLLER =
            new AnnotationMatcher("@org.springframework.stereotype.Controller", true);

    /**
     * @return a SOURCE detection if {@code md} is a controller handler, else {@code null}.
     */
    public @Nullable Detection detect(J.MethodDeclaration md, J.@Nullable ClassDeclaration enclosing) {
        if (enclosing == null || !isController(enclosing)) {
            return null;
        }
        J.Annotation mapping = RequestMappings.firstMapping(md.getLeadingAnnotations());
        if (mapping == null) {
            return null;
        }

        // The full route is the class-level prefix joined with the method-level path. A route is EXACT
        // only when BOTH parts resolve — where "no path argument" resolves to an empty contribution
        // (e.g. @PostMapping inheriting a class-level @RequestMapping), distinct from a path argument
        // that is present but not a string literal (e.g. a constant reference), which is UNKNOWN.
        PathPart methodPart = RequestMappings.pathPartOf(mapping);
        PathPart classPart = RequestMappings.pathPartOf(
                RequestMappings.firstMapping(enclosing.getLeadingAnnotations()));
        Resolution resolution = classPart.resolved && methodPart.resolved
                ? Resolution.EXACT : Resolution.UNKNOWN;
        String route = Routes.join(classPart.value, methodPart.value);

        HttpMethod httpMethod = RequestMappings.httpMethodOf(mapping);
        ExternalIdentifier id = new ExternalIdentifier(httpMethod, route, null, resolution);
        String payloadType = RequestMappings.payloadType(md);
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
}
