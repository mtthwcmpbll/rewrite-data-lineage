package com.snowfort.recipe.lineage;

import com.snowfort.recipe.lineage.fixture.SampleServices;
import com.snowfort.recipe.lineage.table.CallChainEdgeTable;
import com.snowfort.recipe.lineage.table.DataFlowNodeTable;
import org.junit.jupiter.api.Test;
import org.openrewrite.config.DataTableDescriptor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

class FindDataLineageTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FindDataLineage())
                .parser(JavaParser.fromJavaVersion()
                        .classpath("spring-web", "spring-context"));
    }

    @Test
    void registersBothDataTables() {
        FindDataLineage recipe = new FindDataLineage();
        List<String> tableNames = recipe.getDataTableDescriptors().stream()
                .map(DataTableDescriptor::getName)
                .toList();
        assertThat(tableNames).contains(
                DataFlowNodeTable.class.getName(),
                CallChainEdgeTable.class.getName()
        );
    }

    @Test
    void noOpRecipeProducesNoRowsOnTheTwoServiceFixture() {
        rewriteRun(
                spec -> spec.afterRecipe(run -> {
                    assertThat(run.getDataTableRows(DataFlowNodeTable.class)).isEmpty();
                    assertThat(run.getDataTableRows(CallChainEdgeTable.class)).isEmpty();
                }),
                java(SampleServices.SERVICE_A_SRC,
                        s -> s.path(SampleServices.SERVICE_A_PATH)),
                java(SampleServices.SERVICE_B_SRC,
                        s -> s.path(SampleServices.SERVICE_B_PATH))
        );
    }
}
