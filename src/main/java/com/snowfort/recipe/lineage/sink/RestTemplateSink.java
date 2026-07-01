package com.snowfort.recipe.lineage.sink;

import com.snowfort.recipe.lineage.model.Detection;
import com.snowfort.recipe.lineage.model.Direction;
import com.snowfort.recipe.lineage.model.ExternalIdentifier;
import com.snowfort.recipe.lineage.model.Framework;
import com.snowfort.recipe.lineage.model.HttpMethod;
import com.snowfort.recipe.lineage.model.Resolution;
import com.snowfort.recipe.lineage.model.Routes;
import org.jspecify.annotations.Nullable;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.List;

/**
 * Detects outbound HTTP calls made through Spring's {@code RestTemplate}. Emits one SINK
 * {@link Detection} per matched call, with the URI (arg 0) as the route and the request-body
 * argument (where the method carries one) as the payload. HTTP method is derived from the method
 * name, or from the {@code HttpMethod} argument for {@code exchange}.
 */
public final class RestTemplateSink {

    private static final MethodMatcher REST_TEMPLATE =
            new MethodMatcher("org.springframework.web.client.RestTemplate *(..)");

    public @Nullable Detection detect(J.MethodInvocation mi) {
        if (!REST_TEMPLATE.matches(mi)) {
            return null;
        }
        List<Expression> args = mi.getArguments();
        String name = mi.getSimpleName();

        HttpMethod httpMethod;
        int bodyArg;
        switch (name) {
            case "getForObject":
            case "getForEntity":
                httpMethod = HttpMethod.GET;
                bodyArg = -1;
                break;
            case "postForObject":
            case "postForEntity":
            case "postForLocation":
                httpMethod = HttpMethod.POST;
                bodyArg = 1;
                break;
            case "put":
                httpMethod = HttpMethod.PUT;
                bodyArg = 1;
                break;
            case "patchForObject":
                httpMethod = HttpMethod.PATCH;
                bodyArg = 1;
                break;
            case "delete":
                httpMethod = HttpMethod.DELETE;
                bodyArg = -1;
                break;
            case "exchange":
                httpMethod = exchangeMethod(args);
                bodyArg = 2;
                break;
            default:
                return null;
        }

        Expression uriArg = args.isEmpty() ? null : args.get(0);
        ExternalIdentifier id = identifierFor(uriArg, httpMethod);
        String payloadType = bodyArg >= 0 && bodyArg < args.size() ? typeName(args.get(bodyArg)) : "";
        return new Detection(Direction.SINK, Framework.REST_TEMPLATE, id, payloadType);
    }

    private ExternalIdentifier identifierFor(@Nullable Expression uriArg, HttpMethod httpMethod) {
        String raw = literalString(uriArg);
        if (raw == null) {
            // Dynamically-built URL: record the sink but do not guess a route (FR-009, C7).
            return new ExternalIdentifier(httpMethod, "", null, Resolution.UNKNOWN);
        }
        return new ExternalIdentifier(httpMethod, Routes.pathTemplate(raw), Routes.authorityOf(raw),
                Resolution.EXACT);
    }

    private HttpMethod exchangeMethod(List<Expression> args) {
        if (args.size() < 2) {
            return HttpMethod.UNKNOWN;
        }
        Expression methodArg = args.get(1);
        String name = null;
        if (methodArg instanceof J.FieldAccess) {
            name = ((J.FieldAccess) methodArg).getSimpleName();
        } else if (methodArg instanceof J.Identifier) {
            name = ((J.Identifier) methodArg).getSimpleName();
        }
        if (name == null) {
            return HttpMethod.UNKNOWN;
        }
        try {
            return HttpMethod.valueOf(name);
        } catch (IllegalArgumentException e) {
            return HttpMethod.UNKNOWN;
        }
    }

    private static @Nullable String literalString(@Nullable Expression e) {
        if (e instanceof J.Literal && ((J.Literal) e).getValue() instanceof String) {
            return (String) ((J.Literal) e).getValue();
        }
        return null;
    }

    private static String typeName(@Nullable Expression e) {
        if (e == null) {
            return "";
        }
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(e.getType());
        return fq == null ? "" : fq.getFullyQualifiedName();
    }
}
