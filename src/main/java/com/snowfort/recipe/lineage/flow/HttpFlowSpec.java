package com.snowfort.recipe.lineage.flow;

import com.snowfort.recipe.lineage.source.SpringMvcInbound;
import org.openrewrite.analysis.InvocationMatcher;
import org.openrewrite.analysis.dataflow.DataFlowNode;
import org.openrewrite.analysis.dataflow.DataFlowSpec;

/**
 * PROTOTYPE (User Story 2). Defines the taint problem handed to {@code GlobalDataFlow}:
 *
 * <ul>
 *   <li><b>source</b> — an inbound request-data parameter of a Spring MVC controller handler.</li>
 *   <li><b>sink</b> — any argument of an outbound {@code RestTemplate} call.</li>
 * </ul>
 *
 * Scoped to RestTemplate for the prototype: WebClient's reactive wildcard generics don't fully
 * type-resolve, which the dataflow engine needs. Extending to WebClient is follow-up work.
 */
public class HttpFlowSpec extends DataFlowSpec {

    private static final InvocationMatcher REST_TEMPLATE_CALL =
            InvocationMatcher.fromMethodMatcher("org.springframework.web.client.RestTemplate *(..)");

    @Override
    public boolean isSource(DataFlowNode srcNode) {
        return SpringMvcInbound.isInboundParameter(srcNode.getCursor());
    }

    @Override
    public boolean isSink(DataFlowNode sinkNode) {
        return REST_TEMPLATE_CALL.advanced().isAnyArgument(sinkNode.getCursor());
    }
}
