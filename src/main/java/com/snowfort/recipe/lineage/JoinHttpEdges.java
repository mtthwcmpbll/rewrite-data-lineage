package com.snowfort.recipe.lineage;

import com.snowfort.recipe.lineage.model.Confidence;
import com.snowfort.recipe.lineage.model.DataFlowNode;
import com.snowfort.recipe.lineage.model.HttpEdge;
import com.snowfort.recipe.lineage.model.UnmatchedHttpEdge;
import com.snowfort.recipe.lineage.table.HttpEdgeTable;
import com.snowfort.recipe.lineage.table.UnmatchedHttpEdgeTable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pairs HTTP-out {@link DataFlowNode} sinks with HTTP-in source endpoints across
 * repositories and emits the result as queryable edges.
 *
 * <h2>Inputs</h2>
 *
 * <p>This recipe internally re-derives sources and sinks via the
 * {@link FindSpringHttpInSources.HttpInScanner} and
 * {@link FindSpringHttpOutSinks.HttpOutScanner} visitors. Running it inside
 * Moderne's multi-repo data-table aggregation gives the same result as joining
 * over an aggregated {@code DataFlowNodeTable}; running it locally on a single
 * codebase joins only within that codebase.
 *
 * <h2>Join key &amp; normalization</h2>
 *
 * <p>The join key is {@code (verb, normalized-path)}. Verbs are compared
 * case-insensitively after uppercasing. Path normalization:
 * <ol>
 *   <li>Trim and ensure a leading {@code "/"}.</li>
 *   <li>Strip a single trailing {@code "/"} (unless the path is just {@code "/"}).</li>
 *   <li>Split on {@code "/"}; replace every segment that matches
 *       <code>{anyName}</code> with <code>{}</code> so that
 *       {@code /customers/{id}} and {@code /customers/{customerId}} join on
 *       the same key.</li>
 *   <li>Replace pure-numeric segments with <code>{}</code> so that
 *       {@code /customers/123} (a literal-id sink) joins with the templated
 *       inbound {@code /customers/{id}}.</li>
 * </ol>
 * Lowercasing is intentionally <strong>not</strong> applied because HTTP path
 * segments are case-sensitive per RFC 3986.
 *
 * <h2>Outputs</h2>
 *
 * <ul>
 *   <li>{@link HttpEdgeTable} — one row per matched pair, with the caller in
 *       {@code from*} columns and the receiver in {@code to*} columns.</li>
 *   <li>{@link UnmatchedHttpEdgeTable} — one row per sink that did not join
 *       to any inbound endpoint, with a {@code reason} describing why. This
 *       surfaces gaps rather than dropping them silently.</li>
 * </ul>
 */
public class JoinHttpEdges extends ScanningRecipe<JoinHttpEdges.Accumulator> {

    transient HttpEdgeTable httpEdges = new HttpEdgeTable(this);
    transient UnmatchedHttpEdgeTable unmatchedEdges = new UnmatchedHttpEdgeTable(this);

    @Override
    public String getDisplayName() {
        return "Join HTTP edges across repositories";
    }

    @Override
    public String getDescription() {
        return "Pairs Spring HTTP-out sinks with Spring HTTP-in sources by normalized " +
                "(verb, path) and writes both matched edges and unresolved sinks to data tables.";
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                String path = cu.getSourcePath().toString();
                new FindSpringHttpInSources.HttpInScanner(
                        (node, c) -> acc.sources.add(new Entry(node, path))).visit(cu, ctx);
                new FindSpringHttpOutSinks.HttpOutScanner(
                        (node, c) -> acc.sinks.add(new Entry(node, path))).visit(cu, ctx);
                return cu;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        Map<String, List<Entry>> sourcesByKey = new LinkedHashMap<>();
        for (Entry s : acc.sources) {
            sourcesByKey.computeIfAbsent(joinKey(s.node.externalIdentifier()), k -> new ArrayList<>()).add(s);
        }
        for (Entry sink : acc.sinks) {
            if (sink.node.confidence() == Confidence.LOW || sink.node.externalIdentifier().isEmpty()) {
                unmatchedEdges.insertRow(ctx, new UnmatchedHttpEdge(
                        repoOf(sink.sourcePath),
                        sink.node.framework(),
                        sink.node.locator(),
                        sink.node.externalIdentifier(),
                        sink.node.payloadType(),
                        sink.node.confidence(),
                        "url-not-resolved"));
                continue;
            }
            String key = joinKey(sink.node.externalIdentifier());
            List<Entry> matches = sourcesByKey.getOrDefault(key, Collections.emptyList());
            if (matches.isEmpty()) {
                unmatchedEdges.insertRow(ctx, new UnmatchedHttpEdge(
                        repoOf(sink.sourcePath),
                        sink.node.framework(),
                        sink.node.locator(),
                        sink.node.externalIdentifier(),
                        sink.node.payloadType(),
                        sink.node.confidence(),
                        "no-matching-source"));
                continue;
            }
            for (Entry src : matches) {
                String payloadType = !sink.node.payloadType().isEmpty()
                        ? sink.node.payloadType()
                        : src.node.payloadType();
                httpEdges.insertRow(ctx, new HttpEdge(
                        repoOf(sink.sourcePath),
                        sink.node.framework(),
                        sink.node.locator(),
                        repoOf(src.sourcePath),
                        src.node.framework(),
                        src.node.locator(),
                        sink.node.externalIdentifier(),
                        payloadType));
            }
        }
        return Collections.emptyList();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return TreeVisitor.noop();
    }

    /** First path segment is treated as the repository name; empty string if the path has no segment. */
    static String repoOf(String sourcePath) {
        if (sourcePath == null || sourcePath.isEmpty()) {
            return "";
        }
        int slash = sourcePath.indexOf('/');
        if (slash <= 0) {
            return "";
        }
        return sourcePath.substring(0, slash);
    }

    /** Normalize an {@code "VERB /path"} external identifier into its join key. */
    static String joinKey(String externalIdentifier) {
        int space = externalIdentifier.indexOf(' ');
        String verb = space < 0 ? "*" : externalIdentifier.substring(0, space);
        String path = space < 0 ? externalIdentifier : externalIdentifier.substring(space + 1);
        return verb.toUpperCase(java.util.Locale.ROOT) + " " + normalizePath(path);
    }

    /** Apply the path-template normalization rule documented on the class javadoc. */
    static String normalizePath(String path) {
        String p = path.trim();
        if (p.isEmpty()) {
            return "/";
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        String[] segs = p.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < segs.length; i++) {
            sb.append('/');
            String seg = segs[i];
            if (seg.length() >= 2 && seg.startsWith("{") && seg.endsWith("}")) {
                sb.append("{}");
            } else if (!seg.isEmpty() && isAllDigits(seg)) {
                sb.append("{}");
            } else {
                sb.append(seg);
            }
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private record Entry(DataFlowNode node, String sourcePath) {
    }

    public static final class Accumulator {
        final List<Entry> sources = new ArrayList<>();
        final List<Entry> sinks = new ArrayList<>();
    }
}
