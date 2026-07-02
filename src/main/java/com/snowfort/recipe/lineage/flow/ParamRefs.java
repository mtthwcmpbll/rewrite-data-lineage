package com.snowfort.recipe.lineage.flow;

import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Intra-procedural taint approximation for one method (the "local taint" step, tasks T025/T022). Maps
 * each local name to the method-parameter positions whose value it may carry, then answers "which
 * parameter positions does this expression reference?" for call arguments.
 *
 * <p>Taint is tracked at value granularity through direct parameter references and simple local
 * aliasing ({@code var x = param; call(x)}), computed to a fixed point over the method body. This is
 * deliberately an over-approximation (a name that transitively derives from a parameter is treated as
 * carrying it); the authoritative inter-procedural reachability gate is {@code GlobalDataFlow} (see
 * {@link HttpFlowSpec}). Matching is by simple name — adequate for the Spring controller/service
 * shapes in scope; parameter shadowing is not modeled.
 */
public final class ParamRefs {

    /** Local name (parameter or alias) -> parameter positions it may carry. */
    private final Map<String, Set<Integer>> nameToParams = new HashMap<>();

    private ParamRefs() {
    }

    public static ParamRefs of(J.MethodDeclaration md) {
        ParamRefs refs = new ParamRefs();
        int idx = 0;
        for (Statement p : md.getParameters()) {
            if (p instanceof J.VariableDeclarations) {
                for (J.VariableDeclarations.NamedVariable nv : ((J.VariableDeclarations) p).getVariables()) {
                    refs.add(nv.getSimpleName(), idx);
                }
                idx++;
            }
        }
        if (md.getBody() != null) {
            refs.propagateAliases(md.getBody());
        }
        return refs;
    }

    /** Parameter positions referenced anywhere within {@code expr} (empty if none). */
    public Set<Integer> refsOf(Expression expr) {
        Set<Integer> result = new TreeSet<>();
        for (String name : identifiersIn(expr)) {
            Set<Integer> params = nameToParams.get(name);
            if (params != null) {
                result.addAll(params);
            }
        }
        return result;
    }

    private void add(String name, int paramPos) {
        nameToParams.computeIfAbsent(name, k -> new TreeSet<>()).add(paramPos);
    }

    /**
     * Seed local variables from initializers that reference parameters/aliases, iterating to a fixed
     * point so chains of assignments ({@code a = param; b = a;}) resolve. Bounded iteration keeps it
     * terminating and cheap; deep alias chains beyond the bound are simply not tracked.
     */
    private void propagateAliases(J.Block body) {
        Map<String, Expression> initializers = new HashMap<>();
        new JavaIsoVisitor<Map<String, Expression>>() {
            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(
                    J.VariableDeclarations.NamedVariable nv, Map<String, Expression> inits) {
                if (nv.getInitializer() != null) {
                    inits.put(nv.getSimpleName(), nv.getInitializer());
                }
                return super.visitVariable(nv, inits);
            }
        }.visit(body, initializers);

        for (int pass = 0; pass < 5; pass++) {
            boolean changed = false;
            for (Map.Entry<String, Expression> e : initializers.entrySet()) {
                Set<Integer> params = refsOf(e.getValue());
                if (!params.isEmpty()) {
                    Set<Integer> existing = nameToParams.computeIfAbsent(e.getKey(), k -> new TreeSet<>());
                    changed |= existing.addAll(params);
                }
            }
            if (!changed) {
                break;
            }
        }
    }

    private static Set<String> identifiersIn(Expression expr) {
        Set<String> names = new TreeSet<>();
        new JavaIsoVisitor<Set<String>>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier ident, Set<String> acc) {
                acc.add(ident.getSimpleName());
                return ident;
            }
        }.visit(expr, names);
        return names;
    }
}
