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
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Emits one {@link DataFlowNode} of {@link NodeKind#SOURCE} per Spring MVC
 * inbound payload — every parameter annotated {@code @RequestBody},
 * {@code @RequestParam}, {@code @PathVariable}, or {@code @RequestHeader} on a
 * method exposed by an {@code @RestController} (or an {@code @Controller}
 * method that is itself {@code @ResponseBody}).
 *
 * <p>The {@code externalIdentifier} is the canonical {@code "VERB /path"} string
 * built by concatenating any class-level {@code @RequestMapping} prefix with the
 * method-level path. Verbs come from the mapping annotation itself
 * ({@code @GetMapping}, {@code @PostMapping}, etc.); {@code @RequestMapping} with
 * explicit {@code method=} emits one row per listed verb, and
 * {@code @RequestMapping} with no {@code method=} attribute emits a single row
 * with verb {@code *}.
 */
public class FindSpringHttpInSources extends ScanningRecipe<FindSpringHttpInSources.Accumulator> {

    static final String FRAMEWORK = "spring-mvc";

    static final AnnotationMatcher REST_CONTROLLER =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.RestController");
    static final AnnotationMatcher CONTROLLER =
            new AnnotationMatcher("@org.springframework.stereotype.Controller");
    static final AnnotationMatcher RESPONSE_BODY =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.ResponseBody");

    static final AnnotationMatcher REQUEST_MAPPING =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.RequestMapping");
    static final AnnotationMatcher GET_MAPPING =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.GetMapping");
    static final AnnotationMatcher POST_MAPPING =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.PostMapping");
    static final AnnotationMatcher PUT_MAPPING =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.PutMapping");
    static final AnnotationMatcher DELETE_MAPPING =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.DeleteMapping");
    static final AnnotationMatcher PATCH_MAPPING =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.PatchMapping");

    static final AnnotationMatcher REQUEST_BODY =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.RequestBody");
    static final AnnotationMatcher REQUEST_PARAM =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.RequestParam");
    static final AnnotationMatcher PATH_VARIABLE =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.PathVariable");
    static final AnnotationMatcher REQUEST_HEADER =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.RequestHeader");

    transient DataFlowNodeTable dataFlowNodes = new DataFlowNodeTable(this);

    @Override
    public String getDisplayName() {
        return "Find Spring MVC HTTP-in sources";
    }

    @Override
    public String getDescription() {
        return "Detects Spring MVC controller endpoints and emits one source row per " +
                "@RequestBody, @RequestParam, @PathVariable, or @RequestHeader parameter.";
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new HttpInScanner((node, ctx) -> {
            if (acc.nodes.add(node)) {
                dataFlowNodes.insertRow(ctx, node);
            }
        });
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return TreeVisitor.noop();
    }

    /** Visitor that walks Spring MVC controller methods and emits one {@code SOURCE} row per HTTP-bound parameter. */
    static class HttpInScanner extends JavaIsoVisitor<ExecutionContext> {
        private final BiConsumer<DataFlowNode, ExecutionContext> emit;

        HttpInScanner(BiConsumer<DataFlowNode, ExecutionContext> emit) {
            this.emit = emit;
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
            if (enclosing == null) {
                return super.visitMethodDeclaration(method, ctx);
            }
            boolean restController = hasAnnotation(enclosing.getLeadingAnnotations(), REST_CONTROLLER);
            boolean controller = hasAnnotation(enclosing.getLeadingAnnotations(), CONTROLLER);
            boolean methodResponseBody = hasAnnotation(method.getLeadingAnnotations(), RESPONSE_BODY);
            if (!restController && !(controller && methodResponseBody)) {
                return super.visitMethodDeclaration(method, ctx);
            }

            J.Annotation mappingAnnotation = findMappingAnnotation(method.getLeadingAnnotations());
            if (mappingAnnotation == null) {
                return super.visitMethodDeclaration(method, ctx);
            }
            List<String> classPaths = pathsFromAnnotations(enclosing.getLeadingAnnotations(), REQUEST_MAPPING);
            if (classPaths.isEmpty()) {
                classPaths = List.of("");
            }
            List<String> methodPaths = pathsFrom(mappingAnnotation);
            if (methodPaths.isEmpty()) {
                methodPaths = List.of("");
            }
            List<String> verbs = verbsFrom(mappingAnnotation);

            SourceFile source = getCursor().firstEnclosingOrThrow(SourceFile.class);
            String sourcePath = source.getSourcePath().toString();
            String classFqn = enclosing.getType() != null
                    ? enclosing.getType().getFullyQualifiedName()
                    : enclosing.getSimpleName();

            for (Statement parameter : method.getParameters()) {
                if (!(parameter instanceof J.VariableDeclarations)) {
                    continue;
                }
                J.VariableDeclarations vd = (J.VariableDeclarations) parameter;
                if (!hasAnnotation(vd.getLeadingAnnotations(),
                        REQUEST_BODY, REQUEST_PARAM, PATH_VARIABLE, REQUEST_HEADER)) {
                    continue;
                }
                if (vd.getVariables().isEmpty()) {
                    continue;
                }
                String paramName = vd.getVariables().get(0).getSimpleName();
                String payloadType = fullyQualifiedName(vd.getType());

                for (String verb : verbs) {
                    for (String classPath : classPaths) {
                        for (String methodPath : methodPaths) {
                            String path = joinPaths(classPath, methodPath);
                            String externalIdentifier = verb + " " + path;
                            String locator = sourcePath + ":" + classFqn + "#"
                                    + method.getSimpleName() + "(" + paramName + ")";
                            emit.accept(new DataFlowNode(
                                    NodeKind.SOURCE,
                                    FRAMEWORK,
                                    locator,
                                    externalIdentifier,
                                    payloadType,
                                    "",
                                    Confidence.HIGH), ctx);
                        }
                    }
                }
            }
            return super.visitMethodDeclaration(method, ctx);
        }
    }

    static boolean hasAnnotation(List<J.Annotation> annotations, AnnotationMatcher... matchers) {
        if (annotations == null) {
            return false;
        }
        for (J.Annotation a : annotations) {
            for (AnnotationMatcher m : matchers) {
                if (m.matches(a)) {
                    return true;
                }
            }
        }
        return false;
    }

    static J.@Nullable Annotation findMappingAnnotation(List<J.Annotation> annotations) {
        if (annotations == null) {
            return null;
        }
        for (J.Annotation a : annotations) {
            if (GET_MAPPING.matches(a) || POST_MAPPING.matches(a) || PUT_MAPPING.matches(a)
                    || DELETE_MAPPING.matches(a) || PATCH_MAPPING.matches(a) || REQUEST_MAPPING.matches(a)) {
                return a;
            }
        }
        return null;
    }

    static List<String> pathsFromAnnotations(List<J.Annotation> annotations, AnnotationMatcher matcher) {
        List<String> result = new ArrayList<>();
        if (annotations == null) {
            return result;
        }
        for (J.Annotation a : annotations) {
            if (matcher.matches(a)) {
                result.addAll(pathsFrom(a));
            }
        }
        return result;
    }

    /** Extract path/value strings from a Spring mapping annotation. */
    static List<String> pathsFrom(J.Annotation annotation) {
        List<String> result = new ArrayList<>();
        List<Expression> args = annotation.getArguments();
        if (args == null) {
            return result;
        }
        for (Expression arg : args) {
            if (arg instanceof J.Assignment) {
                J.Assignment as = (J.Assignment) arg;
                String name = simpleName(as.getVariable());
                if ("value".equals(name) || "path".equals(name)) {
                    addStringValues(as.getAssignment(), result);
                }
            } else {
                addStringValues(arg, result);
            }
        }
        return result;
    }

    /** Extract HTTP verbs from a Spring mapping annotation. */
    static List<String> verbsFrom(J.Annotation annotation) {
        if (GET_MAPPING.matches(annotation)) {
            return List.of("GET");
        }
        if (POST_MAPPING.matches(annotation)) {
            return List.of("POST");
        }
        if (PUT_MAPPING.matches(annotation)) {
            return List.of("PUT");
        }
        if (DELETE_MAPPING.matches(annotation)) {
            return List.of("DELETE");
        }
        if (PATCH_MAPPING.matches(annotation)) {
            return List.of("PATCH");
        }
        // @RequestMapping
        List<String> verbs = new ArrayList<>();
        List<Expression> args = annotation.getArguments();
        if (args != null) {
            for (Expression arg : args) {
                if (arg instanceof J.Assignment) {
                    J.Assignment as = (J.Assignment) arg;
                    String name = simpleName(as.getVariable());
                    if ("method".equals(name)) {
                        addVerbValues(as.getAssignment(), verbs);
                    }
                }
            }
        }
        if (verbs.isEmpty()) {
            verbs.add("*");
        }
        return verbs;
    }

    private static void addStringValues(Expression e, List<String> out) {
        if (e instanceof J.Literal) {
            Object v = ((J.Literal) e).getValue();
            if (v instanceof String) {
                out.add((String) v);
            }
        } else if (e instanceof J.NewArray) {
            List<Expression> init = ((J.NewArray) e).getInitializer();
            if (init != null) {
                for (Expression el : init) {
                    addStringValues(el, out);
                }
            }
        }
    }

    private static void addVerbValues(Expression e, List<String> out) {
        if (e instanceof J.FieldAccess) {
            out.add(((J.FieldAccess) e).getSimpleName().toUpperCase(Locale.ROOT));
        } else if (e instanceof J.Identifier) {
            out.add(((J.Identifier) e).getSimpleName().toUpperCase(Locale.ROOT));
        } else if (e instanceof J.NewArray) {
            List<Expression> init = ((J.NewArray) e).getInitializer();
            if (init != null) {
                for (Expression el : init) {
                    addVerbValues(el, out);
                }
            }
        }
    }

    private static String simpleName(Expression e) {
        if (e instanceof J.Identifier) {
            return ((J.Identifier) e).getSimpleName();
        }
        if (e instanceof J.FieldAccess) {
            return ((J.FieldAccess) e).getSimpleName();
        }
        return "";
    }

    static String joinPaths(String classPath, String methodPath) {
        String c = classPath == null ? "" : classPath.trim();
        String m = methodPath == null ? "" : methodPath.trim();
        if (c.isEmpty()) {
            return ensureLeadingSlash(m);
        }
        if (m.isEmpty()) {
            return ensureLeadingSlash(c);
        }
        return ensureLeadingSlash(stripTrailingSlash(c)) + ensureLeadingSlash(m);
    }

    private static String ensureLeadingSlash(String p) {
        if (p.isEmpty()) {
            return "/";
        }
        return p.startsWith("/") ? p : "/" + p;
    }

    private static String stripTrailingSlash(String p) {
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    static String fullyQualifiedName(@Nullable JavaType type) {
        if (type == null) {
            return "";
        }
        if (type instanceof JavaType.FullyQualified) {
            return ((JavaType.FullyQualified) type).getFullyQualifiedName();
        }
        if (type instanceof JavaType.Primitive) {
            return ((JavaType.Primitive) type).getKeyword();
        }
        if (type instanceof JavaType.Array) {
            return fullyQualifiedName(((JavaType.Array) type).getElemType()) + "[]";
        }
        return type.toString();
    }

    public static final class Accumulator {
        final Set<DataFlowNode> nodes = new LinkedHashSet<>();
    }
}
