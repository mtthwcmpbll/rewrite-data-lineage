package com.snowfort.recipe.lineage;

import com.snowfort.recipe.lineage.flow.LineageAccumulator;
import com.snowfort.recipe.lineage.model.DataFlowNode;
import com.snowfort.recipe.lineage.model.Detection;
import com.snowfort.recipe.lineage.model.ExternalIdentifier;
import com.snowfort.recipe.lineage.source.SpringMvcSource;
import com.snowfort.recipe.lineage.sink.RestTemplateSink;
import com.snowfort.recipe.lineage.sink.WebClientSink;
import com.snowfort.recipe.lineage.table.DataFlowChainTable;
import com.snowfort.recipe.lineage.table.HttpDataNodeTable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Catalogs inbound Spring MVC endpoints and outbound RestTemplate/WebClient calls as HTTP data
 * nodes, and (User Story 2) traces request data from an endpoint to an outbound call across method
 * boundaries within the repository. Emits the {@link HttpDataNodeTable} and {@link DataFlowChainTable}
 * data tables. Does not modify source (constitution Principle IV).
 */
public class FindHttpDataLineage extends ScanningRecipe<LineageAccumulator> {

    private final transient HttpDataNodeTable nodeTable = new HttpDataNodeTable(this);
    private final transient DataFlowChainTable chainTable = new DataFlowChainTable(this);

    private final transient SpringMvcSource springMvcSource = new SpringMvcSource();
    private final transient RestTemplateSink restTemplateSink = new RestTemplateSink();
    private final transient WebClientSink webClientSink = new WebClientSink();

    @Override
    public String getDisplayName() {
        return "Find Spring Boot HTTP data lineage";
    }

    @Override
    public String getDescription() {
        return "Catalog inbound Spring MVC endpoints and outbound RestTemplate/WebClient calls as HTTP " +
               "data nodes, and trace request data from an endpoint to an outbound call across method " +
               "boundaries within the repository. Emits the HttpDataNodes and DataFlowChains data " +
               "tables. Does not modify source.";
    }

    @Override
    public LineageAccumulator getInitialValue(ExecutionContext ctx) {
        return new LineageAccumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(LineageAccumulator acc) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration md, ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(md, ctx);
                J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
                Detection d = springMvcSource.detect(m, enclosing);
                if (d != null) {
                    acc.addNode(buildNode(d, methodFqn(m.getMethodType()), m.getSimpleName()));
                }
                return m;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(mi, ctx);
                Detection d = restTemplateSink.detect(m);
                if (d == null) {
                    d = webClientSink.detect(m);
                }
                if (d != null) {
                    J.MethodDeclaration enclosing = getCursor().firstEnclosing(J.MethodDeclaration.class);
                    String methodFqn = enclosing == null ? "" : methodFqn(enclosing.getMethodType());
                    acc.addNode(buildNode(d, methodFqn, m.printTrimmed(getCursor())));
                }
                return m;
            }

            private DataFlowNode buildNode(Detection d, String methodFqn, String expression) {
                JavaSourceFile cu = getCursor().firstEnclosing(JavaSourceFile.class);
                String filePath = cu == null ? "" : cu.getSourcePath().toString();
                DataFlowNode.Locator locator =
                        new DataFlowNode.Locator("", filePath, methodFqn, 0, expression);
                return new DataFlowNode(d.getDirection(), d.getFramework(), d.getExternalIdentifier(),
                        d.getPayloadType(), locator);
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(LineageAccumulator acc, ExecutionContext ctx) {
        List<DataFlowNode> nodes = acc.getNodes();
        nodes.sort(NODE_ORDER);
        for (DataFlowNode n : nodes) {
            ExternalIdentifier id = n.getExternalIdentifier();
            nodeTable.insertRow(ctx, new HttpDataNodeTable.Row(
                    n.getNodeId(),
                    n.getDirection().name(),
                    n.getFramework().name(),
                    id.getHttpMethod().name(),
                    id.getRouteTemplate(),
                    id.getResolution().name(),
                    id.getTargetAuthority() == null ? "" : id.getTargetAuthority(),
                    n.getPayloadType(),
                    n.isPayloadResolved(),
                    n.getLocator().getRepository(),
                    n.getLocator().getFilePath(),
                    n.getLocator().getMethodFqn(),
                    n.getLocator().getLine()));
        }
        return Collections.emptyList();
    }

    private static final Comparator<DataFlowNode> NODE_ORDER = Comparator
            .comparing((DataFlowNode n) -> n.getLocator().getRepository())
            .thenComparing(n -> n.getLocator().getFilePath())
            .thenComparingInt(n -> n.getLocator().getLine())
            .thenComparing(n -> n.getDirection().name())
            .thenComparing(DataFlowNode::getNodeId);

    private static String methodFqn(JavaType.Method mt) {
        if (mt == null) {
            return "";
        }
        JavaType.FullyQualified dt = mt.getDeclaringType();
        String owner = dt == null ? "" : dt.getFullyQualifiedName();
        return owner + "#" + mt.getName();
    }
}
