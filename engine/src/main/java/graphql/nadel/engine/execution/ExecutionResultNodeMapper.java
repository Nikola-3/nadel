package graphql.nadel.engine.execution;

import graphql.Internal;
import graphql.execution.ResultPath;
import graphql.nadel.engine.result.ExecutionResultNode;
import graphql.nadel.engine.result.ListExecutionResultNode;
import graphql.nadel.engine.result.ResultCounter;
import graphql.schema.GraphQLCompositeType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;

import java.util.Map;

import static graphql.Assert.assertNotNull;

@Internal
public class ExecutionResultNodeMapper {

    private PathMapper pathMapper = new PathMapper();

    public ExecutionResultNode mapERNFromUnderlyingToOverall(ExecutionResultNode node, UnapplyEnvironment environment, ResultCounter resultCounter) {

        Map<String, String> typeRenameMappings = environment.typeRenameMappings;
        GraphQLSchema overallSchema = environment.overallSchema;
        ResultPath mappedPath = pathMapper.mapPath(node.getResultPath(), node.getResultKey(), environment);
        GraphQLObjectType mappedObjectType;
        GraphQLFieldDefinition mappedFieldDefinition;
        if (environment.correctParentNode instanceof HydrationInputNode && mappedPath.isListSegment()) {
            mappedObjectType = environment.correctParentNode.getObjectType();
            mappedFieldDefinition = environment.correctParentNode.getFieldDefinition();
        } else {
            mappedObjectType = mapObjectType(node, typeRenameMappings, overallSchema, environment.correctParentNode);
            mappedFieldDefinition = getFieldDef(overallSchema, mappedObjectType, node.getFieldName());
        }

        int typeDecrementValue = node instanceof ListExecutionResultNode ? -node.getChildren().size() : 0;
        checkForTypeRename(mappedFieldDefinition, node.getFieldDefinition(), typeRenameMappings, resultCounter, typeDecrementValue);
        return node.transform(builder -> builder
                .resultPath(mappedPath)
                .objectType(mappedObjectType)
                .fieldDefinition(mappedFieldDefinition)
        );

    }

    private GraphQLObjectType mapObjectType(ExecutionResultNode node, Map<String, String> typeRenameMappings, GraphQLSchema overallSchema, ExecutionResultNode parentNode) {
        String objectTypeName = mapTypeName(typeRenameMappings, node.getObjectType().getName());
        GraphQLObjectType mappedObjectType = overallSchema.getObjectType(objectTypeName);
        assertNotNull(mappedObjectType, () -> String.format("object type %s not found in overall schema", objectTypeName));
        return mappedObjectType;
    }

    private String mapTypeName(Map<String, String> typeRenameMappings, String name) {
        return typeRenameMappings.getOrDefault(name, name);
    }


    public static GraphQLFieldDefinition getFieldDef(GraphQLSchema schema, GraphQLCompositeType parentType, String fieldName) {
        if (schema.getQueryType() == parentType) {
            if (fieldName.equals(schema.getIntrospectionSchemaFieldDefinition().getName())) {
                return schema.getIntrospectionSchemaFieldDefinition();
            }
            if (fieldName.equals(schema.getIntrospectionTypeFieldDefinition().getName())) {
                return schema.getIntrospectionTypeFieldDefinition();
            }
        }
        if (fieldName.equals(schema.getIntrospectionTypenameFieldDefinition().getName())) {
            return schema.getIntrospectionTypenameFieldDefinition();
        }
        GraphQLFieldsContainer fieldsContainer = (GraphQLFieldsContainer) parentType;
        GraphQLFieldDefinition fieldDefinition = schema.getCodeRegistry().getFieldVisibility().getFieldDefinition(fieldsContainer, fieldName);
        return assertNotNull(fieldDefinition, () -> String.format("field '%s' not found in container '%s'", fieldName, fieldsContainer));
    }

    public static void checkForTypeRename(GraphQLFieldDefinition mappedFieldDefinition, GraphQLFieldDefinition fieldDefinition, Map<String, String> typeRenameMappings, ResultCounter resultCounter, int typeDecrementValue) {
        String overallFieldType = GraphQLTypeUtil.unwrapAll(mappedFieldDefinition.getType()).getName();
        String underlyingFieldType = GraphQLTypeUtil.unwrapAll(fieldDefinition.getType()).getName();
        if (typeRenameMappings.getOrDefault(underlyingFieldType, "").equals(overallFieldType)) {
            resultCounter.incrementTypeRenameCount(typeDecrementValue + 1);
        } else if (typeRenameMappings.containsValue(overallFieldType) && !overallFieldType.equals(underlyingFieldType)) {
            // Handles edge cases where overall field could be renamed while the type is also renamed, or list inside hydration
            resultCounter.incrementTypeRenameCount(typeDecrementValue + 1);
        }
    }
}
