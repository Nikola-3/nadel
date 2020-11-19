package graphql.nadel.engine.transformation;

import graphql.Internal;
import graphql.execution.ExecutionPath;
import graphql.language.AbstractNode;
import graphql.language.Field;
import graphql.language.Node;
import graphql.nadel.dsl.RemoteArgumentDefinition;
import graphql.nadel.dsl.RemoteArgumentSource;
import graphql.nadel.dsl.UnderlyingServiceHydration;
import graphql.nadel.engine.ExecutionResultNodeMapper;
import graphql.nadel.engine.PathMapper;
import graphql.nadel.engine.UnapplyEnvironment;
import graphql.nadel.normalized.NormalizedQueryField;
import graphql.nadel.result.ExecutionResultNode;
import graphql.nadel.result.LeafExecutionResultNode;
import graphql.nadel.result.ListExecutionResultNode;
import graphql.nadel.result.ObjectExecutionResultNode;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static graphql.Assert.assertShouldNeverHappen;
import static graphql.Assert.assertTrue;
import static graphql.nadel.engine.HydrationInputNode.newHydrationInputNode;
import static graphql.nadel.engine.transformation.FieldUtils.geFirstLeafNode;
import static graphql.nadel.engine.transformation.FieldUtils.mapChildren;
import static graphql.nadel.util.FpKit.filter;
import static graphql.util.TreeTransformerUtil.changeNode;

@Internal
public class HydrationTransformation extends FieldTransformation {


    private UnderlyingServiceHydration underlyingServiceHydration;

    ExecutionResultNodeMapper executionResultNodeMapper = new ExecutionResultNodeMapper();
    PathMapper pathMapper = new PathMapper();


    public HydrationTransformation(UnderlyingServiceHydration underlyingServiceHydration) {
        this.underlyingServiceHydration = underlyingServiceHydration;
    }

    @Override
    public AbstractNode getDefinition() {
        return underlyingServiceHydration;
    }

    @Override
    public ApplyResult apply(ApplyEnvironment environment) {
        setEnvironment(environment);

        TraverserContext<Node> context = environment.getTraverserContext();
        List<RemoteArgumentDefinition> arguments = underlyingServiceHydration.getArguments();

        List<RemoteArgumentDefinition> sourceValues = filter(arguments, argument -> argument.getRemoteArgumentSource().getSourceType() == RemoteArgumentSource.SourceType.OBJECT_FIELD);
        assertTrue(sourceValues.size() == 1, () -> "exactly one object field source expected");
        List<RemoteArgumentDefinition> argumentValues = filter(arguments, argument -> argument.getRemoteArgumentSource().getSourceType() == RemoteArgumentSource.SourceType.FIELD_ARGUMENT);
        assertTrue(1 + argumentValues.size() == arguments.size(), () -> "only $source and $argument values for arguments are supported");

        RemoteArgumentSource remoteArgumentSource = sourceValues.get(0).getRemoteArgumentSource();
        List<String> hydrationSourceName = remoteArgumentSource.getPath();

        Field newField = FieldUtils.pathToFields(hydrationSourceName, environment.getField(), getTransformationId(), Collections.emptyList(), true, environment.getMetadataByFieldId());

        changeNode(context, newField);
        return new ApplyResult(TraversalControl.ABORT);
    }

    public UnderlyingServiceHydration getUnderlyingServiceHydration() {
        return underlyingServiceHydration;
    }

    @Override
    public UnapplyResult unapplyResultNode(ExecutionResultNode node, List<FieldTransformation> allTransformations, UnapplyEnvironment environment) {

        /*
         * The goal here is to return a flat list HydrationInputNodes which then can be used to create the hydration query.
         */

        NormalizedQueryField matchingNormalizedOverallField = getMatchingNormalizedQueryFieldBasedOnParent(environment.parentNode);

        // we can have a list of hydration inputs. E.g.: $source.userIds (this is a list of leafs)
        // or we can have a list of things inside the path: e.g.: $source.issues.userIds (this is a list of objects)
        if (node instanceof ListExecutionResultNode) {
            if (node.getChildren().size() == 0) {
                return handleEmptyList((ListExecutionResultNode) node, allTransformations, matchingNormalizedOverallField);
            }
            // Update the environment.parent to current node
            ExecutionPath mappedPath = environment.parentNode.getExecutionPath().segment(node.getResultKey());
            node = node.transform(builder -> builder.executionPath(mappedPath));

            ExecutionResultNode child = node.getChildren().get(0);
            if (child instanceof LeafExecutionResultNode) {
                return handleListOfLeafs((ListExecutionResultNode) node, allTransformations, environment, matchingNormalizedOverallField);
            } else if (child instanceof ObjectExecutionResultNode) {
                return handleListOfObjects((ListExecutionResultNode) node, allTransformations, environment, matchingNormalizedOverallField);
            } else {
                return assertShouldNeverHappen("Not implemented yet");
            }
        }

        LeafExecutionResultNode leafNode =
                geFirstLeafNode(node);
        LeafExecutionResultNode changedNode = unapplyLeafNode(leafNode, allTransformations, environment, matchingNormalizedOverallField, false);
        return new UnapplyResult(changedNode, TraversalControl.ABORT);
    }

    private UnapplyResult handleEmptyList(ListExecutionResultNode listNode,
                                          List<FieldTransformation> allTransformations,
                                          NormalizedQueryField matchingNormalizedOverallField) {
        ExecutionResultNode changedList = mapToOverallFieldAndTypes(listNode, allTransformations, matchingNormalizedOverallField);
        return new UnapplyResult(changedList, TraversalControl.ABORT);
    }

    private UnapplyResult handleListOfObjects(ListExecutionResultNode transformedNode, List<FieldTransformation> allTransformations, UnapplyEnvironment environment, NormalizedQueryField matchingNormalizedField) {
        // if we have more merged fields than transformations
        // we handle here a list of objects with each object containing one node

        // this is ListNode->ObjectNode(with exact one leaf child)->LeafNode

        ExecutionResultNode mappedNode = mapToOverallFieldAndTypes(transformedNode, allTransformations, matchingNormalizedField);
        ExecutionPath mappedPath = pathMapper.mapPath(mappedNode.getExecutionPath(), mappedNode.getResultKey(), environment);

        environment.parentNode = (ExecutionResultNode) transformedNode;
        ExecutionResultNode changedNode = mapChildren(mappedNode, objectChild -> {
            LeafExecutionResultNode leaf = (LeafExecutionResultNode) objectChild.getChildren().get(0);
            return unapplyLeafNode(leaf, allTransformations, environment, matchingNormalizedField, true);
        }).transform(builder -> builder.executionPath(mappedPath));

        return new UnapplyResult(changedNode, TraversalControl.ABORT);
    }


    private UnapplyResult handleListOfLeafs(ListExecutionResultNode listExecutionResultNode, List<FieldTransformation> allTransformations, UnapplyEnvironment environment, NormalizedQueryField matchingNormalizedField) {
        ExecutionResultNode mappedNode = mapToOverallFieldAndTypes(listExecutionResultNode, allTransformations, matchingNormalizedField);
        ExecutionPath mappedPath = pathMapper.mapPath(mappedNode.getExecutionPath(), mappedNode.getResultKey(), environment);

        environment.parentNode = (ExecutionResultNode) listExecutionResultNode;
        List<ExecutionResultNode> newChildren = new ArrayList<>();
        for (ExecutionResultNode leafNode : listExecutionResultNode.getChildren()) {
            LeafExecutionResultNode newChild = unapplyLeafNode((LeafExecutionResultNode) leafNode, allTransformations, environment, matchingNormalizedField, true);
            newChildren.add(newChild);
        }
        ExecutionResultNode changedNode = mappedNode.withNewChildren(newChildren).transform(builder -> builder.executionPath(mappedPath));
        return new UnapplyResult(changedNode, TraversalControl.ABORT);
    }


    private LeafExecutionResultNode unapplyLeafNode(LeafExecutionResultNode leafNode,
                                                    List<FieldTransformation> allTransformations,
                                                    UnapplyEnvironment environment,
                                                    NormalizedQueryField matchingNormalizedField,
                                                    boolean isListChild) {

        leafNode = (LeafExecutionResultNode) mapToOverallFieldAndTypes(leafNode, allTransformations, matchingNormalizedField);
        ExecutionPath mappedPath;
        if (isListChild) {
            mappedPath = pathMapper.mapPath(leafNode.getExecutionPath(), leafNode.getResultKey(), environment);
        } else {
            mappedPath = environment.parentNode.getExecutionPath().segment(leafNode.getResultKey());
        }
        leafNode = leafNode.transform(builder -> builder.executionPath(mappedPath));

        if (leafNode.isNullValue()) {
            return leafNode;
        } else {
            return newHydrationInputNode()
                    .hydrationTransformation(this)
                    .alias(leafNode.getAlias())
                    .fieldIds(leafNode.getFieldIds())
                    .objectType(leafNode.getObjectType())
                    .fieldDefinition(leafNode.getFieldDefinition())
                    .executionPath(leafNode.getExecutionPath())
                    .completedValue(leafNode.getCompletedValue())
                    .elapsedTime(leafNode.getElapsedTime())
                    .normalizedField(matchingNormalizedField)
                    .build();
        }
    }

}
