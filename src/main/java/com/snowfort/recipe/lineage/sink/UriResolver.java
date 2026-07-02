package com.snowfort.recipe.lineage.sink;

import com.snowfort.recipe.lineage.model.ExternalIdentifier;
import com.snowfort.recipe.lineage.model.HttpMethod;
import com.snowfort.recipe.lineage.model.Resolution;
import com.snowfort.recipe.lineage.model.Routes;
import org.jspecify.annotations.Nullable;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;

/**
 * Shared outbound-URI normalization for {@link RestTemplateSink} and {@link WebClientSink}, so both
 * clients build {@link ExternalIdentifier}s the same way as the inbound side (FR-004/FR-005, SC-006).
 *
 * <p>Resolution is recorded as data, never guessed (Principle IV, FR-009):
 * <ul>
 *   <li>a string literal &rarr; {@link Resolution#EXACT} (path-only template + authority);</li>
 *   <li>a concatenation with a leading string literal ({@code "http://inv/orders/" + id}) &rarr;
 *       {@link Resolution#PARTIAL} — the known path prefix plus a {@code {...}} placeholder for the
 *       dynamic segment, so a partially-known route is never emitted as a concrete literal;</li>
 *   <li>anything else (a bare variable, a builder call) &rarr; {@link Resolution#UNKNOWN}.</li>
 * </ul>
 */
final class UriResolver {

    /** The placeholder for the dynamic tail of a partially-resolved outbound route. */
    static final String DYNAMIC_SEGMENT = "{...}";

    private UriResolver() {
    }

    static ExternalIdentifier resolve(@Nullable Expression uriArg, HttpMethod httpMethod) {
        String literal = literalString(uriArg);
        if (literal != null) {
            return new ExternalIdentifier(httpMethod, Routes.pathTemplate(literal),
                    Routes.authorityOf(literal), Resolution.EXACT);
        }
        String prefix = leadingLiteral(uriArg);
        if (prefix != null) {
            return new ExternalIdentifier(httpMethod, partialRoute(prefix),
                    Routes.authorityOf(prefix), Resolution.PARTIAL);
        }
        // Dynamically-built URL with no recoverable prefix: record the sink but do not guess a route.
        return new ExternalIdentifier(httpMethod, "", null, Resolution.UNKNOWN);
    }

    /** Known path prefix with a placeholder appended, e.g. {@code /orders/{...}}. */
    private static String partialRoute(String prefix) {
        String path = Routes.pathTemplate(prefix);
        return "/".equals(path) ? "/" + DYNAMIC_SEGMENT : path + "/" + DYNAMIC_SEGMENT;
    }

    /** The leftmost string literal of a {@code +} concatenation chain, or {@code null}. */
    private static @Nullable String leadingLiteral(@Nullable Expression e) {
        Expression cur = e;
        while (cur instanceof J.Binary) {
            J.Binary binary = (J.Binary) cur;
            if (binary.getOperator() != J.Binary.Type.Addition) {
                return null;
            }
            cur = binary.getLeft();
        }
        return literalString(cur);
    }

    private static @Nullable String literalString(@Nullable Expression e) {
        if (e instanceof J.Literal && ((J.Literal) e).getValue() instanceof String) {
            return (String) ((J.Literal) e).getValue();
        }
        return null;
    }
}
