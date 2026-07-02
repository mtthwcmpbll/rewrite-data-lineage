package com.snowfort.recipe.lineage.sink;

import com.snowfort.recipe.lineage.model.Detection;
import com.snowfort.recipe.lineage.model.Direction;
import com.snowfort.recipe.lineage.model.ExternalIdentifier;
import com.snowfort.recipe.lineage.model.Framework;
import com.snowfort.recipe.lineage.model.HttpMethod;
import com.snowfort.recipe.lineage.model.Resolution;
import com.snowfort.recipe.lineage.model.Routes;
import com.snowfort.recipe.lineage.source.RequestMappings;
import com.snowfort.recipe.lineage.source.RequestMappings.PathPart;
import org.jspecify.annotations.Nullable;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;

/**
 * Detects outbound HTTP calls declared through Spring Cloud OpenFeign. Unlike {@code RestTemplate} /
 * {@code WebClient} — where the sink is a call <em>expression</em> — a Feign endpoint is declared on a
 * {@code @FeignClient} interface method with Spring MVC mapping annotations. Each such method is one
 * SINK {@link Detection} with {@link Framework#FEIGN}.
 *
 * <p>The route is the {@code @FeignClient(path=…)} prefix joined with the method mapping path, using
 * the same {@link RequestMappings} rules as the inbound controller side — so an outbound Feign
 * {@code POST /api/x} carries the same {@code (httpMethod, routeTemplate)} join key as the inbound
 * controller {@code POST /api/x} it targets (SC-006). The {@code @FeignClient} {@code name}/{@code
 * value} (the logical service id, e.g. {@code fraud-detection-service}) is recorded as the
 * {@code targetAuthority} so the callee service is preserved without polluting the join key.
 */
public final class FeignClientSink {

    private static final AnnotationMatcher FEIGN_CLIENT =
            new AnnotationMatcher("@org.springframework.cloud.openfeign.FeignClient", true);

    /**
     * @return a SINK detection if {@code md} is a {@code @FeignClient} interface method with a request
     *         mapping, else {@code null}.
     */
    public @Nullable Detection detect(J.MethodDeclaration md, J.@Nullable ClassDeclaration enclosing) {
        if (enclosing == null) {
            return null;
        }
        J.Annotation feign = feignClient(enclosing);
        if (feign == null) {
            return null;
        }
        J.Annotation mapping = RequestMappings.firstMapping(md.getLeadingAnnotations());
        if (mapping == null) {
            return null;
        }

        PathPart methodPart = RequestMappings.pathPartOf(mapping);
        PathPart clientPart = RequestMappings.pathPartOf(feign);  // @FeignClient(path=…) prefix, if any
        Resolution resolution = clientPart.resolved && methodPart.resolved
                ? Resolution.EXACT : Resolution.UNKNOWN;
        String route = Routes.join(clientPart.value, methodPart.value);

        HttpMethod httpMethod = RequestMappings.httpMethodOf(mapping);
        String authority = serviceId(feign);
        ExternalIdentifier id = new ExternalIdentifier(httpMethod, route, authority, resolution);
        String payloadType = RequestMappings.payloadType(md);
        return new Detection(Direction.SINK, Framework.FEIGN, id, payloadType);
    }

    private J.@Nullable Annotation feignClient(J.ClassDeclaration cd) {
        for (J.Annotation a : cd.getLeadingAnnotations()) {
            if (FEIGN_CLIENT.matches(a)) {
                return a;
            }
        }
        return null;
    }

    /**
     * The Feign target's logical identity: the {@code name}/{@code value} service id when present
     * (e.g. {@code fraud-detection-service}), else the host of an explicit {@code url}, else null.
     * {@code @FeignClient(path=…)} is deliberately excluded — that is route, not authority.
     */
    private @Nullable String serviceId(J.Annotation feign) {
        if (feign.getArguments() == null) {
            return null;
        }
        String named = null;
        String url = null;
        for (Expression arg : feign.getArguments()) {
            if (!(arg instanceof J.Assignment)) {
                // A single positional argument on @FeignClient is the name/value (the service id).
                String v = RequestMappings.literalString(arg);
                if (v != null && !v.isEmpty()) {
                    return v;
                }
                continue;
            }
            J.Assignment asg = (J.Assignment) arg;
            if (!(asg.getVariable() instanceof J.Identifier)) {
                continue;
            }
            String attr = ((J.Identifier) asg.getVariable()).getSimpleName();
            String v = RequestMappings.literalString(asg.getAssignment());
            if (v == null || v.isEmpty()) {
                continue;
            }
            if ("name".equals(attr) || "value".equals(attr)) {
                named = v;
            } else if ("url".equals(attr)) {
                url = v;
            }
        }
        if (named != null) {
            return named;
        }
        // Fall back to the authority parsed out of an explicit url (e.g. host of http://host:port).
        return url == null ? null : Routes.authorityOf(url);
    }
}
