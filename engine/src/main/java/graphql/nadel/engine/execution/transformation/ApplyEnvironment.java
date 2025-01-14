package graphql.nadel.engine.execution.transformation;

import graphql.Internal;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.Node;
import graphql.nadel.engine.NadelContext;
import graphql.nadel.normalized.NormalizedQueryField;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLSchema;
import graphql.util.TraverserContext;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

@Internal
public class ApplyEnvironment {
    private final Field field;
    private final GraphQLFieldDefinition fieldDefinitionOverall;
    private final GraphQLFieldsContainer fieldsContainerOverall;
    private final TraverserContext<Node> traverserContext;
    private List<NormalizedQueryField> normalizedQueryFieldsOverall;
    private final Map<String, List<FieldMetadata>> metadataByFieldId;
    private Map<String, FragmentDefinition> fragmentDefinitionMap;
    private final GraphQLSchema underlyingSchema;
    private final NadelContext nadelContext;

    public ApplyEnvironment(Field field,
                            GraphQLFieldDefinition fieldDefinitionOverall,
                            GraphQLFieldsContainer fieldsContainerOverall,
                            TraverserContext<Node> traverserContext,
                            List<NormalizedQueryField> normalizedQueryFieldsOverall,
                            Map<String, List<FieldMetadata>> metadataByFieldId,
                            Map<String, FragmentDefinition> fragmentDefinitionMap,
                            @Nullable GraphQLSchema underlyingSchema,
                            NadelContext nadelContext
    ) {
        this.field = field;
        this.fieldDefinitionOverall = fieldDefinitionOverall;
        this.fieldsContainerOverall = fieldsContainerOverall;
        this.traverserContext = traverserContext;
        this.normalizedQueryFieldsOverall = normalizedQueryFieldsOverall;
        this.metadataByFieldId = metadataByFieldId;
        this.fragmentDefinitionMap = fragmentDefinitionMap;
        this.underlyingSchema = underlyingSchema;
        this.nadelContext = nadelContext;
    }

    public Field getField() {
        return field;
    }

    public GraphQLFieldDefinition getFieldDefinitionOverall() {
        return fieldDefinitionOverall;
    }

    public GraphQLFieldsContainer getFieldsContainerOverall() {
        return fieldsContainerOverall;
    }

    public TraverserContext<Node> getTraverserContext() {
        return traverserContext;
    }

    public List<NormalizedQueryField> getNormalizedQueryFieldsOverall() {
        return normalizedQueryFieldsOverall;
    }

    public Map<String, List<FieldMetadata>> getMetadataByFieldId() {
        return metadataByFieldId;
    }

    public Map<String, FragmentDefinition> getFragmentDefinitionMap() {
        return fragmentDefinitionMap;
    }

    public NadelContext getNadelContext() {
        return nadelContext;
    }

    public GraphQLSchema getUnderlyingSchema() {
        return underlyingSchema;
    }

}
