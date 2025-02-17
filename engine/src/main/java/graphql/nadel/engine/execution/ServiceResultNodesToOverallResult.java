package graphql.nadel.engine.execution;

import graphql.GraphQLError;
import graphql.Internal;
import graphql.execution.ExecutionId;
import graphql.execution.MergedField;
import graphql.execution.ResultPath;
import graphql.language.AbstractNode;
import graphql.nadel.dsl.NodeId;
import graphql.nadel.dsl.UnderlyingServiceHydration;
import graphql.nadel.engine.NadelContext;
import graphql.nadel.engine.Tuples;
import graphql.nadel.engine.TuplesTwo;
import graphql.nadel.engine.execution.transformation.FieldMetadata;
import graphql.nadel.engine.execution.transformation.FieldTransformation;
import graphql.nadel.engine.execution.transformation.HydrationTransformation;
import graphql.nadel.engine.execution.transformation.TransformationMetadata;
import graphql.nadel.engine.execution.transformation.TransformationMetadata.NormalizedFieldAndError;
import graphql.nadel.engine.execution.transformation.UnapplyResult;
import graphql.nadel.engine.result.ExecutionResultNode;
import graphql.nadel.engine.result.LeafExecutionResultNode;
import graphql.nadel.engine.result.ListExecutionResultNode;
import graphql.nadel.engine.result.ObjectExecutionResultNode;
import graphql.nadel.engine.result.ResultCounter;
import graphql.nadel.engine.result.RootExecutionResultNode;
import graphql.nadel.normalized.NormalizedQueryField;
import graphql.nadel.normalized.NormalizedQueryFromAst;
import graphql.nadel.util.FpKit;
import graphql.schema.GraphQLSchema;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import graphql.util.TraverserVisitorStub;
import graphql.util.TreeTransformerUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static graphql.Assert.assertNotNull;
import static graphql.Assert.assertShouldNeverHappen;
import static graphql.Assert.assertTrue;
import static graphql.nadel.engine.execution.ExecutionResultNodeMapper.checkForTypeRename;
import static graphql.nadel.engine.execution.StrategyUtil.changeFieldIsInResultNode;
import static graphql.util.FpKit.groupingBy;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;

@Internal
public class ServiceResultNodesToOverallResult {

    ExecutionResultNodeMapper executionResultNodeMapper = new ExecutionResultNodeMapper();

    ResolvedValueMapper resolvedValueMapper = new ResolvedValueMapper();

    ResultNodesTransformer resultNodesTransformer = new ResultNodesTransformer();

    @SuppressWarnings("UnnecessaryLocalVariable")
    public ExecutionResultNode convert(ExecutionId executionId,
                                       ExecutionResultNode resultNode,
                                       GraphQLSchema overallSchema,
                                       ExecutionResultNode correctRootNode,
                                       Map<String, FieldTransformation> transformationIdToTransformation,
                                       Map<FieldTransformation, String> transformationToFieldId,
                                       Map<String, String> typeRenameMappings,
                                       NadelContext nadelContext,
                                       TransformationMetadata transformationMetadata,
                                       Set<ResultPath> hydrationInputPaths) {
        return convertImpl(executionId, resultNode, null, overallSchema, correctRootNode, false, false, transformationIdToTransformation, transformationToFieldId, typeRenameMappings, false, nadelContext, transformationMetadata, hydrationInputPaths);
    }

    public ExecutionResultNode convertChildren(ExecutionId executionId,
                                               ExecutionResultNode root,
                                               NormalizedQueryField normalizedRootField,
                                               GraphQLSchema overallSchema,
                                               ExecutionResultNode correctRootNode,
                                               boolean isHydrationTransformation,
                                               boolean batched,
                                               Map<String, FieldTransformation> transformationIdToTransformation,
                                               Map<FieldTransformation, String> transformationToFieldId,
                                               Map<String, String> typeRenameMappings,
                                               NadelContext nadelContext,
                                               TransformationMetadata transformationMetadata,
                                               Set<ResultPath> hydrationInputPaths) {
        return convertImpl(executionId, root, normalizedRootField, overallSchema, correctRootNode, isHydrationTransformation, batched, transformationIdToTransformation, transformationToFieldId, typeRenameMappings, true, nadelContext, transformationMetadata, hydrationInputPaths);
    }

    private ExecutionResultNode convertImpl(ExecutionId executionId,
                                            ExecutionResultNode root,
                                            NormalizedQueryField normalizedRootField,
                                            GraphQLSchema overallSchema,
                                            ExecutionResultNode correctRootNode,
                                            boolean isHydrationTransformation,
                                            boolean batched,
                                            Map<String, FieldTransformation> transformationIdToTransformation,
                                            Map<FieldTransformation, String> transformationToFieldId,
                                            Map<String, String> typeRenameMappings,
                                            boolean onlyChildren,
                                            NadelContext nadelContext,
                                            TransformationMetadata transformationMetadata,
                                            Set<ResultPath> hydrationInputPaths) {
        ResultCounter resultCounter = new ResultCounter();

        HandleResult handleResult = convertSingleNode(root,
                null/*not for root*/,
                null,
                executionId,
                root,
                normalizedRootField,
                overallSchema,
                isHydrationTransformation,
                batched,
                transformationIdToTransformation,
                transformationToFieldId,
                typeRenameMappings,
                onlyChildren,
                nadelContext,
                transformationMetadata,
                resultCounter,
                hydrationInputPaths);
        assertNotNull(handleResult, () -> "can't delete root");
        assertTrue(handleResult.siblings.isEmpty(), () -> "can't add siblings to root");

        ExecutionResultNode changedNode = handleResult.changedNode;
        List<ExecutionResultNode> newChildren = new ArrayList<>();
        for (ExecutionResultNode child : changedNode.getChildren()) {
            // pass in the correct root node as parent, not root
            HandleResult handleResultChild = convertRecursively(child,
                    correctRootNode,
                    changedNode,
                    executionId,
                    root,
                    normalizedRootField,
                    overallSchema,
                    isHydrationTransformation,
                    batched,
                    transformationIdToTransformation,
                    transformationToFieldId,
                    typeRenameMappings,
                    onlyChildren,
                    nadelContext,
                    transformationMetadata,
                    resultCounter,
                    hydrationInputPaths);
            if (handleResultChild == null) {
                continue;
            }
            newChildren.add(handleResultChild.changedNode);
            newChildren.addAll(handleResultChild.siblings);
        }

        return changedNode.transform(
                builder -> builder
                        .children(newChildren)
                        .totalNodeCount(resultCounter.getNodeCount())
                        .totalFieldRenameCount(resultCounter.getFieldRenameCount())
                        .totalTypeRenameCount(resultCounter.getTypeRenameCount())
        );
    }

    private HandleResult convertRecursively(ExecutionResultNode node,
                                            ExecutionResultNode correctParentNode,
                                            ExecutionResultNode directParentNode,
                                            ExecutionId executionId,
                                            ExecutionResultNode root,
                                            NormalizedQueryField normalizedRootField,
                                            GraphQLSchema overallSchema,
                                            boolean isHydrationTransformation,
                                            boolean batched,
                                            Map<String, FieldTransformation> transformationIdToTransformation,
                                            Map<FieldTransformation, String> transformationToFieldId,
                                            Map<String, String> typeRenameMappings,
                                            boolean onlyChildren,
                                            NadelContext nadelContext,
                                            TransformationMetadata transformationMetadata,
                                            ResultCounter resultCounter,
                                            Set<ResultPath> hydrationInputPaths) {
        HandleResult handleResult = convertSingleNode(node, correctParentNode, directParentNode, executionId, root, normalizedRootField, overallSchema, isHydrationTransformation, batched, transformationIdToTransformation, transformationToFieldId, typeRenameMappings, onlyChildren, nadelContext, transformationMetadata, resultCounter, hydrationInputPaths);
        if (handleResult == null) {
            return null;
        }
        if (handleResult.traversalControl == TraversalControl.ABORT) {
            return handleResult;
        }
        ExecutionResultNode changedNode = handleResult.changedNode;
        List<ExecutionResultNode> newChildren = new ArrayList<>();
        for (ExecutionResultNode child : changedNode.getChildren()) {
            HandleResult handleResultChild = convertRecursively(child, changedNode, changedNode, executionId, root, normalizedRootField, overallSchema, isHydrationTransformation, batched, transformationIdToTransformation, transformationToFieldId, typeRenameMappings, onlyChildren, nadelContext, transformationMetadata, resultCounter, hydrationInputPaths);
            if (handleResultChild == null) {
                continue;
            }
            newChildren.add(handleResultChild.changedNode);
            // additional siblings are not descended, just added
            newChildren.addAll(handleResultChild.siblings);
        }
        handleResult.changedNode = changedNode.withNewChildren(newChildren);
        return handleResult;
    }

    private HandleResult convertSingleNode(ExecutionResultNode node,
                                           ExecutionResultNode correctParentNode,
                                           ExecutionResultNode directParentNode,
                                           ExecutionId executionId,
                                           ExecutionResultNode root,
                                           NormalizedQueryField normalizedRootField,
                                           GraphQLSchema overallSchema,
                                           boolean isHydrationTransformation,
                                           boolean batched,
                                           Map<String, FieldTransformation> transformationIdToTransformation,
                                           Map<FieldTransformation, String> transformationToFieldId,
                                           Map<String, String> typeRenameMappings,
                                           boolean onlyChildren,
                                           NadelContext nadelContext,
                                           TransformationMetadata transformationMetadata,
                                           ResultCounter resultCounter,
                                           Set<ResultPath> hydrationInputPaths) {
        resultCounter.incrementNodeCount();

        if (onlyChildren && node == root) {
            // Could be a possible type rename if this is hydrated
            if (normalizedRootField != null) {
                checkForTypeRename(normalizedRootField.getFieldDefinition(), node.getFieldDefinition(), typeRenameMappings, resultCounter, 0);
            }
            if (root instanceof ObjectExecutionResultNode) {
                ExecutionResultNode executionResultNode = addDeletedChildren((ObjectExecutionResultNode) node, normalizedRootField, nadelContext, transformationMetadata);
                return HandleResult.simple(executionResultNode);
            } else {
                return HandleResult.simple(node);
            }
        }

        if (node instanceof RootExecutionResultNode) {
            ExecutionResultNode convertedNode = mapRootResultNode((RootExecutionResultNode) node);
            return HandleResult.simple(convertedNode);
        }
        if (node instanceof LeafExecutionResultNode) {
            if (ArtificialFieldUtils.isArtificialField(nadelContext, node.getAlias())) {
                resultCounter.decrementNodeCount();
                return null;
            }
        }

        TuplesTwo<Set<FieldTransformation>, List<String>> transformationsAndNotTransformedFields =
                getTransformationsAndNotTransformedFields(node, transformationIdToTransformation, transformationMetadata);

        List<FieldTransformation> transformations = new ArrayList<>(transformationsAndNotTransformedFields.getT1());

        UnapplyEnvironment unapplyEnvironment = new UnapplyEnvironment(
                correctParentNode,
                directParentNode,
                isHydrationTransformation,
                batched,
                typeRenameMappings,
                overallSchema
        );
        HandleResult result;
        if (transformations.isEmpty()) {
            result = HandleResult.simple(mapNode(node, unapplyEnvironment, resultCounter));
        } else {
            result = unapplyTransformations(executionId, node, transformations, unapplyEnvironment, transformationIdToTransformation, transformationToFieldId, nadelContext, transformationMetadata, resultCounter, hydrationInputPaths);
            if (result == null) {
                return null;
            }
        }

        if (result.changedNode instanceof ObjectExecutionResultNode && !(correctParentNode instanceof HydrationInputNode)) {
            result.changedNode = addDeletedChildren((ObjectExecutionResultNode) result.changedNode, null, nadelContext, transformationMetadata);
        }
        return result;
    }

    private ExecutionResultNode addDeletedChildren(ObjectExecutionResultNode resultNode,
                                                   NormalizedQueryField normalizedQueryField,
                                                   NadelContext nadelContext,
                                                   TransformationMetadata transformationMetadata
    ) {
        if (normalizedQueryField == null) {
            normalizedQueryField = getNormalizedQueryFieldForResultNode(resultNode, nadelContext.getNormalizedOverallQuery());
        }
        List<NormalizedFieldAndError> removedFields = transformationMetadata.getRemovedFieldsForParent(normalizedQueryField);

        if (!removedFields.isEmpty()) {
            boolean isFirstNode = isFirstNode(resultNode);

            for (NormalizedFieldAndError normalizedFieldAndError : removedFields) {
                NormalizedQueryField field = normalizedFieldAndError.getNormalizedField();
                GraphQLError error = isFirstNode ? normalizedFieldAndError.getError() : null;

                MergedField mergedField = nadelContext.getNormalizedOverallQuery().getMergedFieldByNormalizedFields().get(field);
                LeafExecutionResultNode newChild = createRemovedFieldResult(resultNode, mergedField, field, error);
                resultNode = resultNode.transform(b -> b.addChild(newChild));
            }
        }

        return resultNode;
    }

    private LeafExecutionResultNode createRemovedFieldResult(ExecutionResultNode parent,
                                                             MergedField mergedField,
                                                             NormalizedQueryField normalizedQueryField,
                                                             GraphQLError error) {
        ResultPath parentPath = parent.getResultPath();
        ResultPath executionPath = parentPath.segment(normalizedQueryField.getResultKey());

        LeafExecutionResultNode removedNode = LeafExecutionResultNode.newLeafExecutionResultNode()
                .resultPath(executionPath)
                .alias(mergedField.getSingleField().getAlias())
                .fieldIds(NodeId.getIds(mergedField))
                .objectType(normalizedQueryField.getObjectType())
                .fieldDefinition(normalizedQueryField.getFieldDefinition())
                .completedValue(null)
                .errors(error != null ? singletonList(error) : emptyList())
                .build();
        return removedNode;
    }

    private HandleResult unapplyTransformations(ExecutionId executionId,
                                                ExecutionResultNode node,
                                                List<FieldTransformation> transformations,
                                                UnapplyEnvironment unapplyEnvironment,
                                                Map<String, FieldTransformation> transformationIdToTransformation,
                                                Map<FieldTransformation, String> transformationToFieldId,
                                                NadelContext nadelContext,
                                                TransformationMetadata transformationMetadata,
                                                ResultCounter resultCounter,
                                                Set<ResultPath> hydrationInputPaths) {

        if (isArtificialHydrationNode(node.getFieldIds(), new HashSet<>(transformationToFieldId.values()))) {
            if (getFieldIdsWithoutTransformations(node, transformationMetadata).isEmpty()) {
                return null;
            }
            HandleResult handleResult = HandleResult.simple(nodesWithTransformationIds(node, null, transformationMetadata));
            handleResult.traversalControl = TraversalControl.ABORT;
            return handleResult;
        }

        Map<AbstractNode, ? extends List<FieldTransformation>> transformationByDefinition = groupingBy(transformations, FieldTransformation::getDefinition);
        TuplesTwo<ExecutionResultNode, Map<AbstractNode, List<ExecutionResultNode>>> splittedNodes = splitTreeByTransformationDefinition(node, unapplyEnvironment.directParentNode, transformationIdToTransformation, transformationToFieldId, transformationMetadata);
        ExecutionResultNode notTransformedTree = splittedNodes.getT1();
        Map<AbstractNode, List<ExecutionResultNode>> nodesWithTransformedFields = splittedNodes.getT2();

        List<UnapplyResult> unapplyResults = new ArrayList<>();

        for (AbstractNode definition : nodesWithTransformedFields.keySet()) {
            List<FieldTransformation> transformationsForDefinition = transformationByDefinition.get(definition);
            FieldTransformation transformation = transformationsForDefinition.get(0);
            boolean isHydrationTransformation = transformation instanceof HydrationTransformation;

            List<ExecutionResultNode> transformedNodes = nodesWithTransformedFields.get(definition);
            ExecutionResultNode resultNode = transformedNodes.get(0);

            if (isHydrationTransformation) {
                resultNode = mergeHydrationNodes(transformedNodes, resultNode);
            }
            UnapplyResult unapplyResult = transformation.unapplyResultNode(resultNode, transformationsForDefinition, unapplyEnvironment);

            if (isHydrationTransformation) {
                // For every list node, it's children will also have a renamed type so the type rename count is decremented based on
                // the size of it's children.
                // //E.g. /foo , /foo[0], /foo[1], /foo[2] => type rename count becomes -2, -1, 0, 1
                int typeDecrementValue = unapplyResult.getNode() instanceof ListExecutionResultNode ? -unapplyResult.getNode().getChildren().size() : -1;
                checkForTypeRename(unapplyResult.getNode().getFieldDefinition(), node.getFieldDefinition(), unapplyEnvironment.typeRenameMappings, resultCounter, typeDecrementValue);
                hydrationInputPaths.add(unapplyResult.getNode().getResultPath());
            } else {
                // typeDecrementAmount = 0 because for a field rename it's children will not know about the underlying type.
                checkForTypeRename(unapplyResult.getNode().getFieldDefinition(), node.getFieldDefinition(), unapplyEnvironment.typeRenameMappings, resultCounter, 0);
                resultCounter.incrementFieldRenameCount();
            }
            unapplyResults.add(unapplyResult);
        }

        HandleResult handleResult = HandleResult.newHandleResultWithSiblings();
        boolean first = true;
        // the not transformed part should simply continue to be converted
        if (notTransformedTree != null) {
            ExecutionResultNode mappedNode = mapNode(notTransformedTree, unapplyEnvironment, resultCounter);
            mappedNode = convertChildren(executionId,
                    mappedNode,
                    null,
                    unapplyEnvironment.overallSchema,
                    unapplyEnvironment.correctParentNode,
                    unapplyEnvironment.isHydrationTransformation,
                    unapplyEnvironment.batched,
                    transformationIdToTransformation,
                    transformationToFieldId,
                    unapplyEnvironment.typeRenameMappings,
                    nadelContext,
                    transformationMetadata,
                    hydrationInputPaths);
            handleResult.changedNode = mappedNode;
            resultCounter.incrementFieldRenameCount(mappedNode.getTotalFieldRenameCount());
            resultCounter.incrementTypeRenameCount(mappedNode.getTotalTypeRenameCount());
            resultCounter.incrementNodeCount(mappedNode.getTotalNodeCount() - 1);
            first = false;
        }

        // each unapply result is either continued to processed
        for (UnapplyResult unapplyResult : unapplyResults) {
            ExecutionResultNode transformedResult;
            if (unapplyResult.getTraversalControl() != TraversalControl.CONTINUE) {
                transformedResult = unapplyResult.getNode();
            } else {
                ExecutionResultNode unapplyResultNode = unapplyResult.getNode();
                transformedResult = convertChildren(executionId,
                        unapplyResultNode,
                        null,
                        unapplyEnvironment.overallSchema,
                        unapplyResultNode,
                        unapplyEnvironment.isHydrationTransformation,
                        unapplyEnvironment.batched,
                        transformationIdToTransformation,
                        transformationToFieldId,
                        unapplyEnvironment.typeRenameMappings,
                        nadelContext,
                        transformationMetadata,
                        hydrationInputPaths);
            }
            resultCounter.incrementFieldRenameCount(transformedResult.getTotalFieldRenameCount());
            resultCounter.incrementTypeRenameCount(transformedResult.getTotalTypeRenameCount());

            if (first) {
                handleResult.changedNode = transformedResult;
                first = false;
            } else {
                handleResult.siblings.add(transformedResult);
            }
        }
        handleResult.traversalControl = TraversalControl.ABORT;
        return handleResult;
    }

    private ExecutionResultNode mergeHydrationNodes(List<ExecutionResultNode> nodesWithTransformedFields, ExecutionResultNode primaryNode) {
        if (primaryNode.isNullValue()) {
            return primaryNode;
        }

        Map<String, Object> completedValues = new LinkedHashMap<>();
        boolean isListSource = primaryNode instanceof ListExecutionResultNode;

        for (ExecutionResultNode hydrationNode : nodesWithTransformedFields) {
            Object value = hydrationNode.getCompletedValue();
            String resultKey = hydrationNode.getResultKey();
            completedValues.put(resultKey, value);

            if (hydrationNode.getResultPath().equals(primaryNode.getResultPath())) {
                primaryNode = hydrationNode;
            }
            if (isListSource) {
                assertTrue(hydrationNode instanceof ListExecutionResultNode, () -> String.format("Expected source argument %s to return a list of values", hydrationNode.getResultKey()));
            } else {
                assertTrue(!(hydrationNode instanceof ListExecutionResultNode), () -> String.format("Expected source argument %s to return a single value", hydrationNode.getResultKey()));
            }
        }
        if (isListSource) {
            return mergeListHydrationNodeValues(primaryNode, completedValues);
        } else if (primaryNode instanceof ObjectExecutionResultNode) {
            return mergeObjectHydrationNodeValues(primaryNode, completedValues);
        }
        return primaryNode.withNewCompletedValue(completedValues);
    }

    private ExecutionResultNode mergeListHydrationNodeValues(ExecutionResultNode primaryNode, Map<String, Object> completedValues) {
        List<ExecutionResultNode> newChildren = new ArrayList<>();
        for (int index = 0; index < primaryNode.getChildren().size(); index++) {
            ExecutionResultNode child = primaryNode.getChildren().get(index);
            Map<String, Object> childCompletedValues = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : completedValues.entrySet()) {
                Object value = entry.getValue();
                value = (index < ((List) value).size()) ? ((List) value).get(index) : null;
                childCompletedValues.put(entry.getKey(), value);
            }
            if (child instanceof ObjectExecutionResultNode) {
                child = mergeObjectHydrationNodeValues(child, childCompletedValues);
            } else {
                if (!child.isNullValue()) {
                    child = child.withNewCompletedValue(childCompletedValues);
                }
            }
            newChildren.add(child);
        }
        return primaryNode.withNewChildren(newChildren).transform(builder -> builder.fieldId(primaryNode.getFieldIds().get(0)));
    }

    private ExecutionResultNode mergeObjectHydrationNodeValues(ExecutionResultNode node, Map<String, Object> completedValues) {
        return changeLeafValueInObjectNode(node, completedValues).get(0);
    }

    private List<ExecutionResultNode> changeLeafValueInObjectNode(ExecutionResultNode node, Map<String, Object> completedValues) {
        if (node instanceof LeafExecutionResultNode) {
            if (!node.isNullValue()) {
                node = node.withNewCompletedValue(completedValues);
            }
            return new ArrayList<>(Collections.singletonList(node));
        }

        List<ExecutionResultNode> modifiedChildren = changeLeafValueInObjectNode(node.getChildren().get(0), completedValues);
        return new ArrayList<>(Collections.singletonList(node.transform(builder -> builder.children(modifiedChildren))));
    }

    private TuplesTwo<ExecutionResultNode, Map<AbstractNode, List<ExecutionResultNode>>> splitTreeByTransformationDefinition(
            ExecutionResultNode executionResultNode,
            ExecutionResultNode directParentNode,
            Map<String, FieldTransformation> transformationIdToTransformation,
            Map<FieldTransformation, String> transformationToFieldId,
            TransformationMetadata transformationMetadata) {
        if (executionResultNode instanceof RootExecutionResultNode) {
            return Tuples.of(executionResultNode, emptyMap());
        }

        Map<AbstractNode, Set<String>> transformationIdsByTransformationDefinition = new LinkedHashMap<>();
        List<String> fieldIds = executionResultNode.getFieldIds();
        Set<String> rootTransformationIdsForNode = new LinkedHashSet<>();
        for (String fieldId : fieldIds) {
            List<String> rootTransformationIdsForFieldId = FieldMetadataUtil.getRootOfTransformationIds(fieldId, transformationMetadata.getMetadataByFieldId());
            for (String rootTransformationId : rootTransformationIdsForFieldId) {
                FieldTransformation fieldTransformation = assertNotNull(transformationIdToTransformation.get(rootTransformationId));
                rootTransformationIdsForNode.addAll(rootTransformationIdsForFieldId);
                // This checks that the current fieldTransformation is for this specific fieldID as we can have a n:1 mapping of transformations to fieldIds
                // due to multiple source arguments
                if (transformationToFieldId.containsKey(fieldTransformation) && transformationToFieldId.get(fieldTransformation).equals(fieldId)) {
                    AbstractNode definition = fieldTransformation.getDefinition();
                    transformationIdsByTransformationDefinition.putIfAbsent(definition, new LinkedHashSet<>());
                    transformationIdsByTransformationDefinition.get(definition).add(rootTransformationId);
                }
            }
        }

        Map<AbstractNode, List<ExecutionResultNode>> treesByTransformationDefinition = new LinkedHashMap<>();
        Set<AbstractNode> definitions = transformationIdsByTransformationDefinition.keySet();

        boolean canSkipTraversal = canSkipTraversal(definitions, executionResultNode, transformationMetadata);
        if (canSkipTraversal) {
            treesByTransformationDefinition.put(definitions.iterator().next(), singletonList(executionResultNode));
        } else {
            for (AbstractNode definition : definitions) {
                Set<String> transformationIds = transformationIdsByTransformationDefinition.get(definition);
                treesByTransformationDefinition.putIfAbsent(definition, new ArrayList<>());
                treesByTransformationDefinition.get(definition).add(nodesWithTransformationIds(executionResultNode, transformationIds, transformationMetadata));

                if (definition instanceof UnderlyingServiceHydration) {
                    for (ExecutionResultNode child : directParentNode.getChildren()) {
                        // Makes sure no unnecessary traversals occur
                        if (child != executionResultNode && !getFieldIdsWithTransformationIds(child, transformationIds, transformationMetadata).isEmpty()
                        ) {
                            ExecutionResultNode resultNode = nodesWithTransformationIds(child, transformationIds, transformationMetadata);
                            treesByTransformationDefinition.get(definition).add(resultNode);
                        }
                    }
                }
            }
        }
        ExecutionResultNode treeWithoutRootTransformations = canSkipTraversal
                ? null
                : nodesWithoutTransformationIds(executionResultNode, rootTransformationIdsForNode, transformationMetadata);

        return Tuples.of(treeWithoutRootTransformations, treesByTransformationDefinition);
    }

    /**
     * Skips 2 sub-tree traversals if there is ONLY 1 rename transformation and 0 not-transformed sub-trees
     */
    private boolean canSkipTraversal(Set<AbstractNode> definitions, ExecutionResultNode executionResultNode, TransformationMetadata transformationMetadata) {
        return definitions.size() == 1 &&
                !(definitions.iterator().next() instanceof UnderlyingServiceHydration) &&
                getFieldIdsWithoutTransformations(executionResultNode, transformationMetadata).isEmpty();
    }

    private ExecutionResultNode nodesWithTransformationIds(ExecutionResultNode executionResultNode, Set<String> transformationIds, TransformationMetadata transformationMetadata) {
        return resultNodesTransformer.transform(executionResultNode, new TraverserVisitorStub<ExecutionResultNode>() {

            @Override
            public TraversalControl enter(TraverserContext<ExecutionResultNode> context) {
                ExecutionResultNode node = context.thisNode();
                List<String> fieldIdsWithId;
                if (transformationIds == null) {
                    fieldIdsWithId = getFieldIdsWithoutTransformations(node, transformationMetadata);
                } else {
                    fieldIdsWithId = getFieldIdsWithTransformationIds(node, transformationIds, transformationMetadata);
                }

                if (fieldIdsWithId.isEmpty()) {
                    return TreeTransformerUtil.deleteNode(context);
                }
                ExecutionResultNode changedNode = changeFieldIsInResultNode(node, fieldIdsWithId);
                return TreeTransformerUtil.changeNode(context, changedNode);
            }
        });
    }

    private ExecutionResultNode nodesWithoutTransformationIds(ExecutionResultNode executionResultNode, Set<String> transformationIds, TransformationMetadata transformationMetadata) {

        return resultNodesTransformer.transform(executionResultNode, new TraverserVisitorStub<ExecutionResultNode>() {

            @Override
            public TraversalControl enter(TraverserContext<ExecutionResultNode> context) {
                ExecutionResultNode node = context.thisNode();
                List<String> fieldIdsWithoutTransformationIds = getFieldIdsWithoutTransformationIds(node, transformationIds, transformationMetadata);
                if (fieldIdsWithoutTransformationIds.isEmpty()) {
                    return TreeTransformerUtil.deleteNode(context);
                }
                ExecutionResultNode changedNode = changeFieldIsInResultNode(node, fieldIdsWithoutTransformationIds);
                return TreeTransformerUtil.changeNode(context, changedNode);
            }
        });
    }

    private List<String> getFieldIdsWithoutTransformationIds(ExecutionResultNode node, Set<String> transformationIds, TransformationMetadata transformationMetadata) {
        return FpKit.filter(node.getFieldIds(), fieldId -> {
            Map<String, List<FieldMetadata>> metadataByFieldId = transformationMetadata.getMetadataByFieldId();
            List<String> transformationIdsForFieldId = FieldMetadataUtil.getTransformationIds(fieldId, metadataByFieldId);
            return Collections.disjoint(transformationIdsForFieldId, transformationIds);
        });
    }

    private List<String> getFieldIdsWithoutTransformations(ExecutionResultNode node, TransformationMetadata transformationMetadata) {
        return FpKit.filter(
                node.getFieldIds(),
                fieldId -> {
                    Map<String, List<FieldMetadata>> metadataByFieldId = transformationMetadata.getMetadataByFieldId();
                    List<String> transformationIdsForFieldId = FieldMetadataUtil.getTransformationIds(fieldId, metadataByFieldId);
                    return transformationIdsForFieldId.isEmpty();
                });
    }

    private List<String> getFieldIdsWithTransformationIds(
            ExecutionResultNode node, Set<String> transformationIds, TransformationMetadata transformationMetadata
    ) {
        return FpKit.filter(node.getFieldIds(), fieldId -> {
            List<String> transformationIdsForField = FieldMetadataUtil.getTransformationIds(fieldId, transformationMetadata.getMetadataByFieldId());
            return transformationIdsForField.stream().anyMatch(transformationIds::contains);
        });
    }

    private ExecutionResultNode mapNode(ExecutionResultNode node, UnapplyEnvironment environment, ResultCounter resultCounter) {
        ExecutionResultNode mappedNode = executionResultNodeMapper.mapERNFromUnderlyingToOverall(node, environment, resultCounter);
        mappedNode = resolvedValueMapper.mapCompletedValue(mappedNode, environment);
        return mappedNode;
    }

    private TuplesTwo<Set<FieldTransformation>, List<String>> getTransformationsAndNotTransformedFields(
            ExecutionResultNode node,
            Map<String, FieldTransformation> transformationIdToTransformation,
            TransformationMetadata transformationMetadata
    ) {
        Set<FieldTransformation> transformations = new LinkedHashSet<>();
        List<String> notTransformedFields = new ArrayList<>();
        for (String fieldId : node.getFieldIds()) {

            if (node.getResultPath().isListSegment()) {
                notTransformedFields.add(fieldId);
                continue;
            }

            List<String> rootTransformationIds = FieldMetadataUtil.getRootOfTransformationIds(fieldId, transformationMetadata.getMetadataByFieldId());
            if (rootTransformationIds.isEmpty()) {
                notTransformedFields.add(fieldId);
                continue;
            }
            for (String transformationId : rootTransformationIds) {
                FieldTransformation fieldTransformation = transformationIdToTransformation.get(transformationId);
                transformations.add(fieldTransformation);
            }
        }
        return Tuples.of(transformations, notTransformedFields);
    }

    private RootExecutionResultNode mapRootResultNode(RootExecutionResultNode resultNode) {
        return RootExecutionResultNode.newRootExecutionResultNode()
                .children(resultNode.getChildren())
                .errors(resultNode.getErrors())
                .extensions(resultNode.getExtensions())
                .elapsedTime(resultNode.getElapsedTime())
                .build();
    }

    private NormalizedQueryField getNormalizedQueryFieldForResultNode(ObjectExecutionResultNode resultNode,
                                                                      NormalizedQueryFromAst normalizedQueryFromAst) {
        String id = resultNode.getFieldIds().get(0);
        List<NormalizedQueryField> normalizedFields = assertNotNull(normalizedQueryFromAst.getNormalizedFieldsByFieldId(id));

        for (NormalizedQueryField normalizedField : normalizedFields) {
            if (resultNode.getObjectType() == normalizedField.getObjectType() &&
                    resultNode.getFieldDefinition() == normalizedField.getFieldDefinition()) {
                return normalizedField;
            }
        }
        return assertShouldNeverHappen("Can't find normalized query field");
    }

    private boolean isArtificialHydrationNode(List<String> fieldIds, Set<String> transformationToFieldIds) {
        return fieldIds.stream().noneMatch(transformationToFieldIds::contains);
    }

    public static class HandleResult {
        ExecutionResultNode changedNode;
        List<ExecutionResultNode> siblings = emptyList();
        TraversalControl traversalControl = TraversalControl.CONTINUE;

        public HandleResult() {

        }

        public static HandleResult newHandleResultWithSiblings() {
            HandleResult handleResult = new HandleResult();
            handleResult.siblings = new ArrayList<>();
            return handleResult;
        }

        public HandleResult(ExecutionResultNode changedNode, List<ExecutionResultNode> siblings, TraversalControl traversalControl) {
            this.changedNode = changedNode;
            this.siblings = siblings;
            this.traversalControl = traversalControl;
        }

        public static HandleResult simple(ExecutionResultNode executionResultNode) {
            return new HandleResult(executionResultNode, emptyList(), TraversalControl.CONTINUE);
        }
    }

    /**
     * @see #isFirstNode(ResultPath)
     */
    private boolean isFirstNode(ObjectExecutionResultNode node) {
        ResultPath path = node.getResultPath();
        return isFirstNode(path);
    }

    /**
     * Returns whether in the entire {@link ResultPath} all the indices are 0 i.e. for this specific path, it
     * represents the first Node.
     *
     * @param resultPath the path to the node in question
     * @return whether the node at the given path is the first node
     */
    private boolean isFirstNode(ResultPath resultPath) {
        ResultPath segment = resultPath;
        while (segment != null) {
            if (segment.isListSegment() && segment.getSegmentIndex() != 0) {
                return false;
            }

            segment = segment.getParent();
        }

        return true;
    }
}
