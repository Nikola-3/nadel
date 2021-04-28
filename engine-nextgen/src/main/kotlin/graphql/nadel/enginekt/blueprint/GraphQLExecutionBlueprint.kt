package graphql.nadel.enginekt.blueprint

import graphql.schema.FieldCoordinates

data class GraphQLExecutionBlueprint(
    val underlyingFields: Map<FieldCoordinates, GraphQLRenameInstruction>,
    val underlyingTypes: Map<String, GraphQLUnderlyingType>,
    val artificialFields: Map<FieldCoordinates, GraphQLInstruction>,
)
