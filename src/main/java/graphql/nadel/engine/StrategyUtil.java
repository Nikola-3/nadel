package graphql.nadel.engine;

import graphql.execution.ExecutionPath;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.MergedField;
import graphql.execution.nextgen.FetchedValueAnalysis;
import graphql.execution.nextgen.result.ExecutionResultNode;
import graphql.execution.nextgen.result.ResultNodeTraverser;
import graphql.language.Field;
import graphql.nadel.Operation;
import graphql.nadel.engine.transformation.FieldTransformation;
import graphql.nadel.engine.transformation.HydrationTransformation;
import graphql.schema.GraphQLSchema;
import graphql.util.FpKit;
import graphql.util.NodeMultiZipper;
import graphql.util.NodeZipper;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import graphql.util.TraverserVisitorStub;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static graphql.execution.ExecutionStepInfo.newExecutionStepInfo;
import static graphql.nadel.engine.FixListNamesAdapter.FIX_NAMES_ADAPTER;
import static graphql.util.FpKit.mapEntries;

public class StrategyUtil {

    public static List<NodeMultiZipper<ExecutionResultNode>> groupNodesIntoBatchesByField(List<NodeZipper<ExecutionResultNode>> nodes, ExecutionResultNode root) {
        Map<MergedField, List<NodeZipper<ExecutionResultNode>>> zipperByField = FpKit.groupingBy(nodes,
                (executionResultZipper -> executionResultZipper.getCurNode().getMergedField()));
        return mapEntries(zipperByField, (key, value) -> new NodeMultiZipper<>(root, value, FIX_NAMES_ADAPTER));
    }


    public static List<NodeZipper<ExecutionResultNode>> getHydrationInputNodes(Collection<ExecutionResultNode> roots) {
        List<NodeZipper<ExecutionResultNode>> result = new ArrayList<>();

        ResultNodeTraverser traverser = ResultNodeTraverser.depthFirst();
        traverser.traverse(new TraverserVisitorStub<ExecutionResultNode>() {
            @Override
            public TraversalControl enter(TraverserContext<ExecutionResultNode> context) {
                if (context.thisNode() instanceof HydrationInputNode) {
                    result.add(new NodeZipper<>(context.thisNode(), context.getBreadcrumbs(), FIX_NAMES_ADAPTER));
                }
                return TraversalControl.CONTINUE;
            }

        }, roots);
        return result;
    }

    public static ExecutionStepInfo createRootExecutionStepInfo(GraphQLSchema graphQLSchema, Operation operation) {
        ExecutionStepInfo executionInfo = newExecutionStepInfo().type(operation.getRootType(graphQLSchema)).path(ExecutionPath.rootPath()).build();
        return executionInfo;
    }

    public static <T extends ExecutionResultNode> T changeFieldInResultNode(T executionResultNode, Field newField) {
        MergedField mergedField = MergedField.newMergedField(newField).build();
        FetchedValueAnalysis fetchedValueAnalysis = executionResultNode.getFetchedValueAnalysis();
        ExecutionStepInfo newStepInfo = fetchedValueAnalysis.getExecutionStepInfo().transform(builder -> builder.field(mergedField));
        FetchedValueAnalysis newFetchedValueAnalysis = fetchedValueAnalysis.transfrom(builder -> builder.executionStepInfo(newStepInfo));
        return (T) executionResultNode.withNewFetchedValueAnalysis(newFetchedValueAnalysis);
    }

    public static ExecutionResultNode changeFieldInResultNode(ExecutionResultNode executionResultNode, MergedField mergedField) {
        FetchedValueAnalysis fetchedValueAnalysis = executionResultNode.getFetchedValueAnalysis();
        ExecutionStepInfo newStepInfo = fetchedValueAnalysis.getExecutionStepInfo().transform(builder -> builder.field(mergedField));
        FetchedValueAnalysis newFetchedValueAnalysis = fetchedValueAnalysis.transfrom(builder -> builder.executionStepInfo(newStepInfo));
        return executionResultNode.withNewFetchedValueAnalysis(newFetchedValueAnalysis);
    }


    public static List<HydrationTransformation> getHydrationTransformations(Collection<FieldTransformation> transformations) {
        return transformations
                .stream()
                .filter(transformation -> transformation instanceof HydrationTransformation)
                .map(HydrationTransformation.class::cast)
                .collect(Collectors.toList());
    }
}
