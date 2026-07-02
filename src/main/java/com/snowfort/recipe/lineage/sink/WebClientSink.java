package com.snowfort.recipe.lineage.sink;

import com.snowfort.recipe.lineage.model.Detection;
import com.snowfort.recipe.lineage.model.Direction;
import com.snowfort.recipe.lineage.model.ExternalIdentifier;
import com.snowfort.recipe.lineage.model.Framework;
import com.snowfort.recipe.lineage.model.HttpMethod;
import org.jspecify.annotations.Nullable;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects outbound HTTP calls made through Spring's reactive {@code WebClient}. The call is a fluent
 * chain terminating in {@code retrieve()}; this walks the chain's {@code select} links back to the
 * {@code WebClient} root, extracting the HTTP method ({@code get()}/{@code post()}/{@code method(..)}),
 * the {@code uri(..)} route, and the {@code bodyValue(..)} payload into a single logical SINK.
 */
public final class WebClientSink {

    private static final String WEB_CLIENT = "org.springframework.web.reactive.function.client.WebClient";

    public @Nullable Detection detect(J.MethodInvocation mi) {
        if (!"retrieve".equals(mi.getSimpleName())) {
            return null;
        }

        // Walk the fluent chain, indexing each link by its method name, and confirm it is rooted in a WebClient.
        Map<String, J.MethodInvocation> chain = new HashMap<>();
        boolean webClientRooted = false;
        Expression cur = mi.getSelect();
        while (cur instanceof J.MethodInvocation) {
            J.MethodInvocation link = (J.MethodInvocation) cur;
            chain.putIfAbsent(link.getSimpleName(), link);
            if (isWebClientDeclared(link)) {
                webClientRooted = true;
            }
            cur = link.getSelect();
        }
        if (!webClientRooted && !isWebClientType(cur)) {
            return null;
        }

        HttpMethod httpMethod = httpMethod(chain);
        J.MethodInvocation uri = chain.get("uri");
        Expression uriArg = uri == null || uri.getArguments().isEmpty() ? null : uri.getArguments().get(0);
        ExternalIdentifier id = UriResolver.resolve(uriArg, httpMethod);
        String payloadType = payloadType(chain);
        return new Detection(Direction.SINK, Framework.WEB_CLIENT, id, payloadType);
    }

    private HttpMethod httpMethod(Map<String, J.MethodInvocation> chain) {
        if (chain.containsKey("get")) {
            return HttpMethod.GET;
        }
        if (chain.containsKey("post")) {
            return HttpMethod.POST;
        }
        if (chain.containsKey("put")) {
            return HttpMethod.PUT;
        }
        if (chain.containsKey("delete")) {
            return HttpMethod.DELETE;
        }
        if (chain.containsKey("patch")) {
            return HttpMethod.PATCH;
        }
        J.MethodInvocation method = chain.get("method");
        if (method != null && !method.getArguments().isEmpty()) {
            Expression a = method.getArguments().get(0);
            String name = a instanceof J.FieldAccess ? ((J.FieldAccess) a).getSimpleName()
                    : a instanceof J.Identifier ? ((J.Identifier) a).getSimpleName() : null;
            if (name != null) {
                try {
                    return HttpMethod.valueOf(name);
                } catch (IllegalArgumentException ignored) {
                    // fall through to UNKNOWN
                }
            }
        }
        return HttpMethod.UNKNOWN;
    }

    private String payloadType(Map<String, J.MethodInvocation> chain) {
        J.MethodInvocation body = chain.get("bodyValue");
        if (body == null) {
            body = chain.get("body");
        }
        if (body == null || body.getArguments().isEmpty()) {
            return "";
        }
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(body.getArguments().get(0).getType());
        return fq == null ? "" : fq.getFullyQualifiedName();
    }

    private static boolean isWebClientDeclared(J.MethodInvocation mi) {
        JavaType.Method type = mi.getMethodType();
        return type != null && TypeUtils.isAssignableTo(WEB_CLIENT, type.getDeclaringType());
    }

    private static boolean isWebClientType(@Nullable Expression e) {
        return e != null && TypeUtils.isAssignableTo(WEB_CLIENT, e.getType());
    }
}
