package graphql.nadel.validation.util

import graphql.nadel.Service
import graphql.nadel.enginekt.util.unwrapAll
import graphql.nadel.validation.util.NadelCombinedTypeUtil.getFieldsThatServiceContributed
import graphql.nadel.validation.util.NadelCombinedTypeUtil.isCombinedType
import graphql.nadel.validation.util.NadelSchemaUtil.hasHydration
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLDirectiveContainer
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLInputFieldsContainer
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLModifiedType
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLNullableType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLSchemaElement
import graphql.schema.GraphQLTypeReference
import graphql.schema.GraphQLTypeVisitorStub
import graphql.schema.GraphQLUnionType
import graphql.schema.GraphQLUnmodifiedType
import graphql.schema.SchemaTraverser
import graphql.util.TraversalControl
import graphql.util.TraversalControl.ABORT
import graphql.util.TraversalControl.CONTINUE
import graphql.util.TraverserContext

internal fun getReachableTypeNames(
    overallSchema: GraphQLSchema,
    service: Service,
    definitionNames: Set<String>,
): Set<String> {
    // We need a mutable Set so we can keep track of what types we've visited to avoid StackOverflows
    val reachableTypeNames = mutableSetOf<String>()

    // We keep these so we can add breakpoints to see where a type came from
    fun add(name: String) = reachableTypeNames.add(name)
    fun addAll(elements: Collection<String>) = reachableTypeNames.addAll(elements)

    val serviceDefinitions = overallSchema.typeMap.values.filter { it.name in definitionNames }
    val traverser = object : GraphQLTypeVisitorStub() {
        override fun visitGraphQLArgument(
            node: GraphQLArgument,
            context: TraverserContext<GraphQLSchemaElement>,
        ): TraversalControl {
            add(node.type.unwrapAll().name)
            return CONTINUE
        }

        override fun visitGraphQLUnionType(
            node: GraphQLUnionType,
            context: TraverserContext<GraphQLSchemaElement>,
        ): TraversalControl {
            addAll(node.types.map { it.name })
            return CONTINUE
        }

        override fun visitGraphQLInterfaceType(
            node: GraphQLInterfaceType,
            context: TraverserContext<GraphQLSchemaElement>,
        ): TraversalControl {
            add(node.name)
            return CONTINUE
        }

        override fun visitGraphQLEnumType(
            node: GraphQLEnumType,
            context: TraverserContext<GraphQLSchemaElement>,
        ): TraversalControl {
            add(node.name)
            return CONTINUE
        }

        override fun visitGraphQLFieldDefinition(
            node: GraphQLFieldDefinition,
            context: TraverserContext<GraphQLSchemaElement>,
        ): TraversalControl {
            return if (hasHydration(node)) {
                // Do not collect output type, hydrations do not require the type to be defined in the service
                ABORT
            } else {
                // Will visit the output type
                CONTINUE
            }
        }

        override fun visitGraphQLInputObjectField(
            node: GraphQLInputObjectField,
            context: TraverserContext<GraphQLSchemaElement>,
        ): TraversalControl {
            add(node.type.unwrapAll().name)
            return CONTINUE
        }

        override fun visitGraphQLInputObjectType(
            node: GraphQLInputObjectType,
            context: TraverserContext<GraphQLSchemaElement>,
        ): TraversalControl {
            add(node.name)
            return CONTINUE
        }

        override fun visitGraphQLList(
            node: GraphQLList,
            context: TraverserContext<GraphQLSchemaElement>,
        ): TraversalControl {
            add(node.unwrapAll().name)
            return CONTINUE
        }

        override fun visitGraphQLNonNull(
            node: GraphQLNonNull,
            context: TraverserContext<GraphQLSchemaElement>,
        ): TraversalControl {
            add(node.unwrapAll().name)
            return CONTINUE
        }

        override fun visitGraphQLObjectType(
            node: GraphQLObjectType,
            context: TraverserContext<GraphQLSchemaElement>,
        ): TraversalControl {
            add(node.name)
            return CONTINUE
        }

        override fun visitGraphQLScalarType(
            node: GraphQLScalarType,
            context: TraverserContext<GraphQLSchemaElement>,
        ): TraversalControl {
            add(node.name)
            return CONTINUE
        }

        override fun visitGraphQLTypeReference(
            node: GraphQLTypeReference,
            context: TraverserContext<GraphQLSchemaElement>,
        ): TraversalControl {
            add(node.name)
            return CONTINUE
        }

        override fun visitGraphQLModifiedType(
            node: GraphQLModifiedType,
            context: TraverserContext<GraphQLSchemaElement>,
        ): TraversalControl {
            add(node.unwrapAll().name)
            return CONTINUE
        }

        override fun visitGraphQLCompositeType(
            node: GraphQLCompositeType,
            context: TraverserContext<GraphQLSchemaElement>,
        ): TraversalControl {
            add(node.name)
            return CONTINUE
        }

        override fun visitGraphQLDirective(
            node: GraphQLDirective,
            context: TraverserContext<GraphQLSchemaElement>,
        ): TraversalControl {
            // We don't care about directives, not a Nadel runtime concern
            // GraphQL Java will do validation on them for us
            return ABORT
        }

        override fun visitGraphQLDirectiveContainer(
            node: GraphQLDirectiveContainer,
            context: TraverserContext<GraphQLSchemaElement>,
        ): TraversalControl {
            add(node.name)
            return CONTINUE
        }

        override fun visitGraphQLFieldsContainer(
            node: GraphQLFieldsContainer,
            context: TraverserContext<GraphQLSchemaElement>,
        ): TraversalControl {
            add(node.name)
            return CONTINUE
        }

        override fun visitGraphQLInputFieldsContainer(
            node: GraphQLInputFieldsContainer,
            context: TraverserContext<GraphQLSchemaElement>,
        ): TraversalControl {
            add(node.name)
            return CONTINUE
        }

        override fun visitGraphQLNullableType(
            node: GraphQLNullableType,
            context: TraverserContext<GraphQLSchemaElement>,
        ): TraversalControl {
            add(node.unwrapAll().name)
            return CONTINUE
        }

        override fun visitGraphQLOutputType(
            node: GraphQLOutputType,
            context: TraverserContext<GraphQLSchemaElement>,
        ): TraversalControl {
            add(node.unwrapAll().name)
            return CONTINUE
        }

        override fun visitGraphQLUnmodifiedType(
            node: GraphQLUnmodifiedType,
            context: TraverserContext<GraphQLSchemaElement>,
        ): TraversalControl {
            add(node.name)
            return CONTINUE
        }
    }

    SchemaTraverser { element ->
        // Handle combined types
        if (element is GraphQLNamedSchemaElement && isCombinedType(overallSchema, element)) {
            val fieldsThatServiceContributed = getFieldsThatServiceContributed(service, element)
            // Don't visit fields that service did not contribute i.e. unreachable
            element.children.filter { children ->
                when (children) {
                    is GraphQLFieldDefinition -> children.name in fieldsThatServiceContributed
                    else -> false
                }
            }
        } else {
            element.children
        }
    }.depthFirst(traverser, serviceDefinitions)

    return reachableTypeNames
}
