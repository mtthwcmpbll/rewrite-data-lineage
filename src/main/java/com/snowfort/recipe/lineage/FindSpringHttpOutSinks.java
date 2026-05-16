package com.snowfort.recipe.lineage;

import com.snowfort.recipe.lineage.model.Confidence;
import com.snowfort.recipe.lineage.model.DataFlowNode;
import com.snowfort.recipe.lineage.model.NodeKind;
import com.snowfort.recipe.lineage.table.DataFlowNodeTable;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import static com.snowfort.recipe.lineage.FindSpringHttpInSources.fullyQualifiedName;
import static com.snowfort.recipe.lineage.FindSpringHttpInSources.hasAnnotation;
import static com.snowfort.recipe.lineage.FindSpringHttpInSources.pathsFrom;
import static com.snowfort.recipe.lineage.FindSpringHttpInSources.verbsFrom;
import static com.snowfort.recipe.lineage.FindSpringHttpInSources.findMappingAnnotation;
import static com.snowfort.recipe.lineage.FindSpringHttpInSources.joinPaths;

/**
 * Emits one {@link DataFlowNode} of {@link NodeKind#SINK} per Spring HTTP-out call site.
 *
 * <p>Supported clients:
 * <ul>
 *   <li>{@code RestTemplate} method invocations ({@code framework = "spring-rest-template"})</li>
 *   <li>{@code RestClient} fluent {@code .uri(...)} calls ({@code framework = "spring-rest-client"})</li>
 *   <li>{@code WebClient} fluent {@code .uri(...)} calls ({@code framework = "spring-webclient"})</li>
 *   <li>Methods declared on {@code @FeignClient} interfaces ({@code framework = "spring-feign"})</li>
 * </ul>
 *
 * <p>When the URL argument is a string literal, the row's {@code externalIdentifier} is
 * populated as {@code "VERB /path"} and {@code confidence = HIGH}. When the URL is computed
 * (concatenated, variable, builder, etc.), the row is still emitted with
 * {@code externalIdentifier = ""} and {@code confidence = LOW} so that the cross-repo join in
 * MVP 3 can surface the gap rather than dropping it silently.
 */
public class FindSpringHttpOutSinks extends ScanningRecipe<FindSpringHttpOutSinks.Accumulator> {

    private static final String REST_TEMPLATE_FQN = "org.springframework.web.client.RestTemplate";
    private static final String REST_CLIENT_FQN = "org.springframework.web.client.RestClient";
    private static final String WEB_CLIENT_FQN = "org.springframework.web.reactive.function.client.WebClient";

    private static final AnnotationMatcher FEIGN_CLIENT =
            new AnnotationMatcher("@org.springframework.cloud.openfeign.FeignClient");

    /**
     * RestTemplate method name → (verb, body argument position or -1).
     * Body position is -1 when the call carries no body argument.
     */
    private static final Map<String, MethodInfo> REST_TEMPLATE_METHODS = new LinkedHashMap<>();

    static {
        REST_TEMPLATE_METHODS.put("getForObject", new MethodInfo("GET", -1));
        REST_TEMPLATE_METHODS.put("getForEntity", new MethodInfo("GET", -1));
        REST_TEMPLATE_METHODS.put("postForObject", new MethodInfo("POST", 1));
        REST_TEMPLATE_METHODS.put("postForEntity", new MethodInfo("POST", 1));
        REST_TEMPLATE_METHODS.put("postForLocation", new MethodInfo("POST", 1));
        REST_TEMPLATE_METHODS.put("put", new MethodInfo("PUT", 1));
        REST_TEMPLATE_METHODS.put("delete", new MethodInfo("DELETE", -1));
        REST_TEMPLATE_METHODS.put("patchForObject", new MethodInfo("PATCH", 1));
        REST_TEMPLATE_METHODS.put("headForHeaders", new MethodInfo("HEAD", -1));
        REST_TEMPLATE_METHODS.put("optionsForAllow", new MethodInfo("OPTIONS", -1));
        REST_TEMPLATE_METHODS.put("exchange", new MethodInfo("*", -1)); // verb resolved from HttpMethod argument
    }

    /** Method invocation names that select an HTTP verb on RestClient / WebClient fluent APIs. */
    private static final Set<String> FLUENT_VERB_NAMES = Set.of("get", "post", "put", "delete", "patch", "method", "head", "options");

    transient DataFlowNodeTable dataFlowNodes = new DataFlowNodeTable(this);

    @Override
    public String getDisplayName() {
        return "Find Spring HTTP-out sinks";
    }

    @Override
    public String getDescription() {
        return "Detects outbound HTTP calls made through RestTemplate, RestClient, WebClient, " +
                "and @FeignClient interfaces, and emits one sink row per call site.";
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new HttpOutScanner((node, ctx) -> {
            if (acc.nodes.add(node)) {
                dataFlowNodes.insertRow(ctx, node);
            }
        });
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return TreeVisitor.noop();
    }

    /** Visitor that walks call sites and Feign method declarations and emits one SINK row each. */
    static class HttpOutScanner extends JavaIsoVisitor<ExecutionContext> {
        private final BiConsumer<DataFlowNode, ExecutionContext> emit;

        HttpOutScanner(BiConsumer<DataFlowNode, ExecutionContext> emit) {
            this.emit = emit;
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, ExecutionContext ctx) {
            J.MethodInvocation result = super.visitMethodInvocation(mi, ctx);
            detectRestTemplate(result, ctx);
            detectFluentClient(result, ctx);
            return result;
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration md, ExecutionContext ctx) {
            detectFeignDeclaration(md, ctx);
            return super.visitMethodDeclaration(md, ctx);
        }

        private void detectRestTemplate(J.MethodInvocation mi, ExecutionContext ctx) {
            MethodInfo info = REST_TEMPLATE_METHODS.get(mi.getSimpleName());
            if (info == null) {
                return;
            }
            if (!isOnReceiverOfType(mi, REST_TEMPLATE_FQN)) {
                return;
            }
            String verb = info.verb;
            List<Expression> args = mi.getArguments();
            if ("exchange".equals(mi.getSimpleName()) && args.size() >= 2) {
                String resolved = httpMethodNameFromExpression(args.get(1));
                if (resolved != null) {
                    verb = resolved;
                }
            }
            String url = literalString(args.isEmpty() ? null : args.get(0));
            Confidence confidence = url == null ? Confidence.LOW : Confidence.HIGH;
            String externalIdentifier = url == null ? "" : verb + " " + normalizeRestTemplateUrl(url);
            String payloadType = "";
            if (info.bodyArgIndex >= 0 && info.bodyArgIndex < args.size()) {
                payloadType = fullyQualifiedName(args.get(info.bodyArgIndex).getType());
            }
            emit.accept(new DataFlowNode(
                    NodeKind.SINK,
                    "spring-rest-template",
                    locator(mi),
                    externalIdentifier,
                    payloadType,
                    "",
                    confidence), ctx);
        }

        private void detectFluentClient(J.MethodInvocation mi, ExecutionContext ctx) {
            if (!"uri".equals(mi.getSimpleName())) {
                return;
            }
            Expression select = mi.getSelect();
            if (!(select instanceof J.MethodInvocation)) {
                return;
            }
            J.MethodInvocation verbCall = (J.MethodInvocation) select;
            if (!FLUENT_VERB_NAMES.contains(verbCall.getSimpleName())) {
                return;
            }
            String framework = frameworkForFluentClient(verbCall);
            if (framework == null) {
                return;
            }
            String verb = "method".equals(verbCall.getSimpleName())
                    ? extractMethodArgVerb(verbCall)
                    : verbCall.getSimpleName().toUpperCase(java.util.Locale.ROOT);
            String url = mi.getArguments().isEmpty() ? null : literalString(mi.getArguments().get(0));
            Confidence confidence = url == null ? Confidence.LOW : Confidence.HIGH;
            String externalIdentifier = url == null ? "" : verb + " " + url;
            emit.accept(new DataFlowNode(
                    NodeKind.SINK,
                    framework,
                    locator(mi),
                    externalIdentifier,
                    "",
                    "",
                    confidence), ctx);
        }

        private void detectFeignDeclaration(J.MethodDeclaration md, ExecutionContext ctx) {
            J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
            if (enclosing == null || enclosing.getKind() != J.ClassDeclaration.Kind.Type.Interface) {
                return;
            }
            if (!hasAnnotation(enclosing.getLeadingAnnotations(), FEIGN_CLIENT)) {
                return;
            }
            J.Annotation mapping = findMappingAnnotation(md.getLeadingAnnotations());
            if (mapping == null) {
                return;
            }
            List<String> verbs = verbsFrom(mapping);
            List<String> methodPaths = pathsFrom(mapping);
            if (methodPaths.isEmpty()) {
                methodPaths = List.of("");
            }
            String basePath = feignClientBasePath(enclosing);
            String payloadType = feignRequestBodyType(md);

            SourceFile source = getCursor().firstEnclosingOrThrow(SourceFile.class);
            String sourcePath = source.getSourcePath().toString();
            String interfaceFqn = enclosing.getType() != null
                    ? enclosing.getType().getFullyQualifiedName()
                    : enclosing.getSimpleName();

            for (String verb : verbs) {
                for (String methodPath : methodPaths) {
                    String path = joinPaths(basePath, methodPath);
                    emit.accept(new DataFlowNode(
                            NodeKind.SINK,
                            "spring-feign",
                            sourcePath + ":" + interfaceFqn + "#" + md.getSimpleName(),
                            verb + " " + path,
                            payloadType,
                            "",
                            Confidence.HIGH), ctx);
                }
            }
        }

        private String locator(J.MethodInvocation mi) {
            SourceFile source = getCursor().firstEnclosingOrThrow(SourceFile.class);
            J.ClassDeclaration cd = getCursor().firstEnclosing(J.ClassDeclaration.class);
            J.MethodDeclaration md = getCursor().firstEnclosing(J.MethodDeclaration.class);
            String classFqn = cd == null
                    ? ""
                    : (cd.getType() != null ? cd.getType().getFullyQualifiedName() : cd.getSimpleName());
            String enclosingMethod = md == null ? "<init>" : md.getSimpleName();
            return source.getSourcePath() + ":" + classFqn + "#" + enclosingMethod
                    + " -> " + mi.getSimpleName() + "(...)";
        }
    }

    /**
     * Returns true when {@code mi} is invoked on an expression whose type is {@code fqn}.
     * Falls back to the method's declaring type if the receiver expression has no type;
     * resilient to partial type resolution in test fixtures.
     */
    private static boolean isOnReceiverOfType(J.MethodInvocation mi, String fqn) {
        Expression select = mi.getSelect();
        if (select != null && TypeUtils.isOfClassType(select.getType(), fqn)) {
            return true;
        }
        JavaType.Method mType = mi.getMethodType();
        if (mType != null && mType.getDeclaringType() != null
                && fqn.equals(mType.getDeclaringType().getFullyQualifiedName())) {
            return true;
        }
        return false;
    }

    private static @Nullable String frameworkForFluentClient(J.MethodInvocation verbCall) {
        Expression receiver = verbCall.getSelect();
        if (receiver == null) {
            return null;
        }
        JavaType type = receiver.getType();
        if (TypeUtils.isOfClassType(type, REST_CLIENT_FQN) || isNestedTypeOf(type, REST_CLIENT_FQN)) {
            return "spring-rest-client";
        }
        if (TypeUtils.isOfClassType(type, WEB_CLIENT_FQN) || isNestedTypeOf(type, WEB_CLIENT_FQN)) {
            return "spring-webclient";
        }
        return null;
    }

    private static boolean isNestedTypeOf(@Nullable JavaType type, String outerFqn) {
        if (!(type instanceof JavaType.FullyQualified)) {
            return false;
        }
        String fqn = ((JavaType.FullyQualified) type).getFullyQualifiedName();
        return fqn.startsWith(outerFqn + "$") || fqn.startsWith(outerFqn + ".");
    }

    private static String extractMethodArgVerb(J.MethodInvocation verbCall) {
        List<Expression> args = verbCall.getArguments();
        if (args.isEmpty()) {
            return "*";
        }
        String resolved = httpMethodNameFromExpression(args.get(0));
        return resolved == null ? "*" : resolved;
    }

    private static @Nullable String httpMethodNameFromExpression(Expression e) {
        if (e instanceof J.FieldAccess) {
            return ((J.FieldAccess) e).getSimpleName().toUpperCase(java.util.Locale.ROOT);
        }
        if (e instanceof J.Identifier) {
            JavaType type = ((J.Identifier) e).getType();
            if (type instanceof JavaType.Class || type instanceof JavaType.Variable) {
                return ((J.Identifier) e).getSimpleName().toUpperCase(java.util.Locale.ROOT);
            }
        }
        if (e instanceof J.MethodInvocation) {
            J.MethodInvocation mi = (J.MethodInvocation) e;
            String name = mi.getSimpleName();
            if ("valueOf".equals(name) && !mi.getArguments().isEmpty()) {
                String s = literalString(mi.getArguments().get(0));
                if (s != null) {
                    return s.toUpperCase(java.util.Locale.ROOT);
                }
            }
        }
        return null;
    }

    private static @Nullable String literalString(@Nullable Expression e) {
        if (e instanceof J.Literal) {
            Object value = ((J.Literal) e).getValue();
            if (value instanceof String) {
                return (String) value;
            }
        }
        return null;
    }

    /**
     * RestTemplate URL arguments often include the scheme/host
     * (e.g. {@code "http://service-b/customers/{id}"}). For cross-repo joining we want
     * just the path portion. Leaves arguments that don't look like absolute URLs unchanged.
     */
    static String normalizeRestTemplateUrl(String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        int pathStart = url.indexOf('/', schemeEnd + 3);
        return pathStart < 0 ? "/" : url.substring(pathStart);
    }

    private static String feignClientBasePath(J.ClassDeclaration iface) {
        for (J.Annotation a : iface.getLeadingAnnotations()) {
            if (FEIGN_CLIENT.matches(a)) {
                List<Expression> args = a.getArguments();
                if (args == null) {
                    return "";
                }
                for (Expression arg : args) {
                    if (arg instanceof J.Assignment) {
                        J.Assignment as = (J.Assignment) arg;
                        String name = ((J.Identifier) as.getVariable()).getSimpleName();
                        if ("path".equals(name) || "url".equals(name)) {
                            String s = literalString(as.getAssignment());
                            if (s != null) {
                                int schemeEnd = s.indexOf("://");
                                if (schemeEnd >= 0) {
                                    int pathStart = s.indexOf('/', schemeEnd + 3);
                                    return pathStart < 0 ? "" : s.substring(pathStart);
                                }
                                return s;
                            }
                        }
                    }
                }
            }
        }
        return "";
    }

    private static final AnnotationMatcher REQUEST_BODY =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.RequestBody");

    private static String feignRequestBodyType(J.MethodDeclaration md) {
        for (Statement p : md.getParameters()) {
            if (!(p instanceof J.VariableDeclarations)) {
                continue;
            }
            J.VariableDeclarations vd = (J.VariableDeclarations) p;
            for (J.Annotation a : vd.getLeadingAnnotations()) {
                if (REQUEST_BODY.matches(a)) {
                    return fullyQualifiedName(vd.getType());
                }
            }
        }
        return "";
    }

    private record MethodInfo(String verb, int bodyArgIndex) {
    }

    public static final class Accumulator {
        final Set<DataFlowNode> nodes = new LinkedHashSet<>();
    }
}
