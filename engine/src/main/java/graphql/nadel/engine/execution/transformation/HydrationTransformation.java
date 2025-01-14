package graphql.nadel.engine.execution.transformation;

import graphql.Internal;
import graphql.execution.ResultPath;
import graphql.language.AbstractNode;
import graphql.language.Field;
import graphql.language.Node;
import graphql.language.SelectionSet;
import graphql.nadel.dsl.ObjectTypeDefinitionWithTransformation;
import graphql.nadel.dsl.RemoteArgumentDefinition;
import graphql.nadel.dsl.RemoteArgumentSource;
import graphql.nadel.dsl.UnderlyingServiceHydration;
import graphql.nadel.engine.execution.ArtificialFieldUtils;
import graphql.nadel.engine.execution.ExecutionResultNodeMapper;
import graphql.nadel.engine.execution.HydrationInputNode;
import graphql.nadel.engine.execution.PathMapper;
import graphql.nadel.engine.execution.UnapplyEnvironment;
import graphql.nadel.engine.result.ExecutionResultNode;
import graphql.nadel.engine.result.LeafExecutionResultNode;
import graphql.nadel.engine.result.ListExecutionResultNode;
import graphql.nadel.engine.result.ObjectExecutionResultNode;
import graphql.nadel.normalized.NormalizedQueryField;
import graphql.nadel.schema.NadelDirectives;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnmodifiedType;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static graphql.Assert.assertNotNull;
import static graphql.Assert.assertShouldNeverHappen;
import static graphql.Assert.assertTrue;
import static graphql.nadel.util.FpKit.filter;
import static graphql.util.TreeTransformerUtil.changeNode;
import static graphql.util.TreeTransformerUtil.insertAfter;

@Internal
public class HydrationTransformation extends FieldTransformation {

    private final UnderlyingServiceHydration underlyingServiceHydration;

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

        RemoteArgumentSource remoteArgumentSource = sourceValues.get(0).getRemoteArgumentSource();
        List<String> hydrationSourceName = remoteArgumentSource.getPath();
        String transformationId = getTransformationId();
        Field newField = FieldUtils.pathToFields(hydrationSourceName, environment.getField(), transformationId, Collections.emptyList(), true, environment.getMetadataByFieldId());

        newField = maybeAddTypenameToInterfaceField(environment, hydrationSourceName, newField).orElse(newField);

        List<RemoteArgumentDefinition> argumentValues = filter(arguments, argument -> argument.getRemoteArgumentSource().getSourceType() == RemoteArgumentSource.SourceType.FIELD_ARGUMENT);
        assertTrue(sourceValues.size() + argumentValues.size() == arguments.size(), () -> "only $source and $argument values for arguments are supported");
        changeNode(context, newField);

        if (sourceValues.size() > 1) {
            addExtraSourceArgumentFields(environment, transformationId, sourceValues.subList(1, sourceValues.size()));
        }
        return new ApplyResult(TraversalControl.ABORT);
    }

    public void addExtraSourceArgumentFields(ApplyEnvironment environment, String transformationId, List<RemoteArgumentDefinition> remoteArgumentDefinitions) {
        for (RemoteArgumentDefinition remoteArgumentDefinition : remoteArgumentDefinitions) {
            List<String> hydrationSourceName = remoteArgumentDefinition.getRemoteArgumentSource().getPath();
            Field extraSourceArgumentField = FieldUtils.pathToFields(hydrationSourceName, environment.getField(), transformationId, Collections.emptyList(), true, environment.getMetadataByFieldId());
            insertAfter(environment.getTraverserContext(), extraSourceArgumentField);
        }
    }

    public UnderlyingServiceHydration getUnderlyingServiceHydration() {
        return underlyingServiceHydration;
    }

    @Override
    public UnapplyResult unapplyResultNode(ExecutionResultNode node, List<FieldTransformation> allTransformations, UnapplyEnvironment environment) {

        /*
         * The goal here is to return a flat list HydrationInputNodes which then can be used to create the hydration query.
         */

        NormalizedQueryField matchingNormalizedOverallField = getMatchingNormalizedQueryFieldBasedOnParent(environment.correctParentNode);

        // we can have a list of hydration inputs. E.g.: $source.userIds (this is a list of leafs)
        // or we can have a list of things inside the path: e.g.: $source.issues.userIds (this is a list of objects)
        if (node instanceof ListExecutionResultNode) {
            if (node.getChildren().size() == 0) {
                return handleEmptyList((ListExecutionResultNode) node, allTransformations, environment, matchingNormalizedOverallField);
            }

            // if the correct parent node is the direct-parent then we can change it to the current node
            // OTHERWISE this means we had a hydration inside another hydration and
            // we want to use the correct-parent node in this case and not the current node
            if (environment.correctParentNode == environment.directParentNode) {
                environment.correctParentNode = node;
            }
            ExecutionResultNode child = node.getChildren().get(0);
            if (child instanceof LeafExecutionResultNode) {
                return handleListOfLeafs((ListExecutionResultNode) node, allTransformations, environment, matchingNormalizedOverallField);
            } else if (child instanceof ObjectExecutionResultNode) {
                return handleListOfObjects((ListExecutionResultNode) node, allTransformations, environment, matchingNormalizedOverallField);
            } else {
                return assertShouldNeverHappen("Not implemented yet");
            }
        }

        LeafExecutionResultNode leafNode = FieldUtils.getFirstLeafNode(node);
        LeafExecutionResultNode changedNode = unapplyLeafNode(leafNode, allTransformations, environment, matchingNormalizedOverallField);
        return new UnapplyResult(changedNode, TraversalControl.ABORT);
    }

    private UnapplyResult handleEmptyList(ListExecutionResultNode listNode,
                                          List<FieldTransformation> allTransformations,
                                          UnapplyEnvironment environment,
                                          NormalizedQueryField matchingNormalizedOverallField) {
        ExecutionResultNode changedList = mapToOverallFieldAndTypes(listNode, allTransformations, matchingNormalizedOverallField);
        return new UnapplyResult(changedList, TraversalControl.ABORT);
    }

    private UnapplyResult handleListOfObjects(ListExecutionResultNode transformedNode, List<FieldTransformation> allTransformations, UnapplyEnvironment environment, NormalizedQueryField matchingNormalizedField) {
        // if we have more merged fields than transformations
        // we handle here a list of objects with each object containing one node

        // this is ListNode->ObjectNode(with exact one leaf child)->LeafNode

        ExecutionResultNode mappedNode = mapToOverallFieldAndTypes(transformedNode, allTransformations, matchingNormalizedField);

        ExecutionResultNode changedNode = FieldUtils.mapChildren(mappedNode, objectChild -> {
            LeafExecutionResultNode leaf = (LeafExecutionResultNode) objectChild.getChildren().get(0);
            return unapplyLeafNode(leaf, allTransformations, environment, matchingNormalizedField);
        });

        return new UnapplyResult(changedNode, TraversalControl.ABORT);
    }

    private UnapplyResult handleListOfLeafs(ListExecutionResultNode listExecutionResultNode, List<FieldTransformation> allTransformations, UnapplyEnvironment environment, NormalizedQueryField matchingNormalizedField) {
        ExecutionResultNode mappedNode = mapToOverallFieldAndTypes(listExecutionResultNode, allTransformations, matchingNormalizedField);

        List<ExecutionResultNode> newChildren = new ArrayList<>();
        for (ExecutionResultNode leafNode : listExecutionResultNode.getChildren()) {
            LeafExecutionResultNode newChild = unapplyLeafNode((LeafExecutionResultNode) leafNode, allTransformations, environment, matchingNormalizedField);
            newChildren.add(newChild);
        }
        ExecutionResultNode changedNode = mappedNode.withNewChildren(newChildren);
        return new UnapplyResult(changedNode, TraversalControl.ABORT);
    }

    private LeafExecutionResultNode unapplyLeafNode(LeafExecutionResultNode leafNode,
                                                    List<FieldTransformation> allTransformations,
                                                    UnapplyEnvironment environment,
                                                    NormalizedQueryField matchingNormalizedField) {

        leafNode = (LeafExecutionResultNode) mapToOverallFieldAndTypes(leafNode, allTransformations, matchingNormalizedField);
        ResultPath executionPath = pathMapper.mapPath(leafNode.getResultPath(), leafNode.getResultKey(), environment);
        leafNode = leafNode.transform(builder -> builder.resultPath(executionPath));

        SelectionSet.Builder selectionSetBuilder = SelectionSet.newSelectionSet();
        for (FieldTransformation fieldTransformation : allTransformations) {
            fieldTransformation.getOriginalField().getSelectionSet().getSelections().forEach(selectionSetBuilder::selection);
        }
        SelectionSet selectionSet = selectionSetBuilder.build();

        if (leafNode.isNullValue()) {
            return leafNode;
        } else {
            return HydrationInputNode.newHydrationInputNode()
                    .hydrationTransformation(this)
                    .selectionSet(selectionSet)
                    .alias(leafNode.getAlias())
                    .fieldIds(leafNode.getFieldIds())
                    .objectType(leafNode.getObjectType())
                    .fieldDefinition(leafNode.getFieldDefinition())
                    .resultPath(leafNode.getResultPath())
                    .completedValue(leafNode.getCompletedValue())
                    .elapsedTime(leafNode.getElapsedTime())
                    .normalizedField(matchingNormalizedField)
                    .build();
        }
    }

    /**
     * Checks if the source value used for hydration is defined in an interface type, and if
     * it is, adds the aliased __typename field to the query.
     */
    private static Optional<Field> maybeAddTypenameToInterfaceField(ApplyEnvironment environment, List<String> hydrationSourceName, Field newField) {
        // If the source name has only 1 part, there's no way we're dealing with an interface
        final boolean isDeepSourceHydration = hydrationSourceName.size() > 1;

        if (!isDeepSourceHydration) {
            return Optional.empty();
        }

        GraphQLFieldsContainer fieldsContainerOverall = environment.getFieldsContainerOverall();
        Node definition = fieldsContainerOverall.getDefinition();
        String underlyingTypeName = fieldsContainerOverall.getName();
        if (definition instanceof ObjectTypeDefinitionWithTransformation) {
            underlyingTypeName = ((ObjectTypeDefinitionWithTransformation) definition).getTypeMappingDefinition().getUnderlyingName();
        } else if (fieldsContainerOverall instanceof GraphQLDirectiveContainer) {
            var typeMapping = NadelDirectives.createTypeMapping((GraphQLDirectiveContainer) fieldsContainerOverall);
            if (typeMapping != null) {
                underlyingTypeName = typeMapping.getUnderlyingName();
            }
        }
        String fieldToHydrateFrom = hydrationSourceName.get(0);
        String finalUnderlyingTypeName = underlyingTypeName;
        GraphQLFieldsContainer underlyingParentType = (GraphQLFieldsContainer) assertNotNull(environment.getUnderlyingSchema().getType(underlyingTypeName),
                () -> String.format("No underlying type found for %s", finalUnderlyingTypeName));
        GraphQLFieldDefinition fieldDefinition = assertNotNull(underlyingParentType.getFieldDefinition(fieldToHydrateFrom),
                () -> String.format("No field named %s found for type %s", fieldToHydrateFrom, underlyingParentType.getName()));
        GraphQLUnmodifiedType underlyingType = GraphQLTypeUtil.unwrapAll(fieldDefinition.getType());
        if (underlyingType instanceof GraphQLInterfaceType) {
            return Optional.of(ArtificialFieldUtils.maybeAddUnderscoreTypeName(environment.getNadelContext(), newField, (GraphQLInterfaceType) underlyingType));
        }

        return Optional.empty();
    }

}
