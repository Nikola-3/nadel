package graphql.nadel.engine;

import graphql.Internal;
import graphql.execution.ExecutionPath;
import graphql.language.TypeName;
import graphql.nadel.result.ExecutionResultNode;
import graphql.schema.GraphQLCompositeType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static graphql.Assert.assertNotNull;
import static graphql.introspection.Introspection.SchemaMetaFieldDef;
import static graphql.introspection.Introspection.TypeMetaFieldDef;
import static graphql.introspection.Introspection.TypeNameMetaFieldDef;

@Internal
public class ExecutionResultNodeMapper {

    private PathMapper pathMapper = new PathMapper();

    public ExecutionResultNode mapERNFromUnderlyingToOverall(ExecutionResultNode node, UnapplyEnvironment environment, AtomicInteger typeRenameCount) {

        Map<String, String> typeRenameMappings = environment.typeRenameMappings;
        GraphQLSchema overallSchema = environment.overallSchema;
        ExecutionPath mappedPath = pathMapper.mapPath(node.getExecutionPath(), node.getResultKey(), environment);
        GraphQLObjectType mappedObjectType = mapObjectType(node, typeRenameMappings, overallSchema, typeRenameCount);
        GraphQLFieldDefinition mappedFieldDefinition = getFieldDef(overallSchema, mappedObjectType, node.getFieldName());
        checkForTypeRename(mappedFieldDefinition, node.getFieldDefinition(),typeRenameMappings, typeRenameCount);
        return node.transform(builder -> builder
                .executionPath(mappedPath)
                .objectType(mappedObjectType)
                .fieldDefinition(mappedFieldDefinition)
        );

    }

    private GraphQLObjectType mapObjectType(ExecutionResultNode node, Map<String, String> typeRenameMappings, GraphQLSchema overallSchema, AtomicInteger typeRenameCount) {
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
            if (fieldName.equals(SchemaMetaFieldDef.getName())) {
                return SchemaMetaFieldDef;
            }
            if (fieldName.equals(TypeMetaFieldDef.getName())) {
                return TypeMetaFieldDef;
            }
        }
        if (fieldName.equals(TypeNameMetaFieldDef.getName())) {
            return TypeNameMetaFieldDef;
        }
        GraphQLFieldsContainer fieldsContainer = (GraphQLFieldsContainer) parentType;
        GraphQLFieldDefinition fieldDefinition = schema.getCodeRegistry().getFieldVisibility().getFieldDefinition(fieldsContainer, fieldName);
        return assertNotNull(fieldDefinition, () -> String.format("field '%s' not found in container '%s'", fieldName, fieldsContainer));
    }

    public static void checkForTypeRename(GraphQLFieldDefinition mappedFieldDefinition, GraphQLFieldDefinition fieldDefinition, Map<String, String> typeRenameMappings, AtomicInteger typeRenameCount) {
        String overallFieldType = GraphQLTypeUtil.unwrapAll(mappedFieldDefinition.getType()).getName();
        String underlyingFieldType = GraphQLTypeUtil.unwrapAll(fieldDefinition.getType()).getName();
        if (typeRenameMappings.containsKey(underlyingFieldType) && typeRenameMappings.get(underlyingFieldType).equals(overallFieldType)) {
            typeRenameCount.getAndIncrement();
        }
    }
}
