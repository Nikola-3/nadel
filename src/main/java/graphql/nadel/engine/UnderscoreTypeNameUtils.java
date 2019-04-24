package graphql.nadel.engine;

import graphql.execution.MergedField;
import graphql.execution.nextgen.result.ExecutionResultNode;
import graphql.execution.nextgen.result.LeafExecutionResultNode;
import graphql.language.Field;
import graphql.language.SelectionSet;
import graphql.nadel.util.Util;
import graphql.schema.GraphQLOutputType;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import graphql.util.TraverserVisitorStub;
import graphql.util.TreeTransformerUtil;

import java.util.List;

import static graphql.Assert.assertNotNull;

/**
 * Interfaces and unions require that __typename be put on queries so we can work out what type they are on he other side
 */
public class UnderscoreTypeNameUtils {

    private static final String UNDERSCORE_TYPENAME = "__typename";

    public static Field maybeAddUnderscoreTypeName(NadelContext nadelContext, Field field, GraphQLOutputType fieldType) {
        if (!Util.isInterfaceOrUnionField(fieldType)) {
            return field;
        }
        String underscoreTypeNameAlias = nadelContext.getUnderscoreTypeNameAlias();
        assertNotNull(underscoreTypeNameAlias, "We MUST have a generated __typename alias in the request context");

        SelectionSet selectionSet = field.getSelectionSet();
        Field underscoreTypeNameAliasField = Field.newField(UNDERSCORE_TYPENAME).alias(underscoreTypeNameAlias).build();
        if (selectionSet == null) {
            selectionSet = SelectionSet.newSelectionSet().selection(underscoreTypeNameAliasField).build();
        } else {
            selectionSet = selectionSet.transform(builder -> builder.selection(underscoreTypeNameAliasField));
        }
        SelectionSet newSelectionSet = selectionSet;
        field = field.transform(builder -> builder.selectionSet(newSelectionSet));
        return field;
    }

    @SuppressWarnings("UnnecessaryLocalVariable")
    public static ExecutionResultNode maybeRemoveUnderscoreTypeName(NadelContext nadelContext, ExecutionResultNode resultNode) {
        ResultNodesTransformer resultNodesTransformer = new ResultNodesTransformer();
        ExecutionResultNode newNode = resultNodesTransformer.transform(resultNode, new TraverserVisitorStub<ExecutionResultNode>() {
            @Override
            public TraversalControl enter(TraverserContext<ExecutionResultNode> context) {
                ExecutionResultNode node = context.thisNode();
                if (node instanceof LeafExecutionResultNode) {
                    LeafExecutionResultNode leaf = (LeafExecutionResultNode) node;
                    MergedField mergedField = leaf.getFetchedValueAnalysis().getField();

                    if (isAliasedUnderscoreTypeNameField(nadelContext, mergedField)) {
                        return TreeTransformerUtil.deleteNode(context);
                    }
                }
                return TraversalControl.CONTINUE;
            }
        });
        return newNode;
    }

    public static boolean isAliasedUnderscoreTypeNameField(NadelContext nadelContext, MergedField mergedField) {
        String underscoreTypeNameAlias = nadelContext.getUnderscoreTypeNameAlias();
        List<Field> fields = mergedField.getFields();
        // we KNOW we put the field in as a single field with alias (not merged) and hence we can assume that on the reverse
        if (fields.size() == 1) {
            Field singleField = mergedField.getSingleField();
            String alias = singleField.getAlias();
            return underscoreTypeNameAlias.equals(alias);
        }
        return false;
    }
}