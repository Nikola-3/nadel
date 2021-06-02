package graphql.nadel.enginekt.util

import graphql.nadel.OperationKind
import graphql.normalized.NormalizedField
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema

fun GraphQLSchema.getOperationType(kind: OperationKind): GraphQLObjectType? {
    return when (kind) {
        OperationKind.QUERY -> queryType
        OperationKind.MUTATION -> mutationType
        OperationKind.SUBSCRIPTION -> subscriptionType
    }
}

fun GraphQLFieldsContainer.getFieldAt(
    pathToField: List<String>,
): GraphQLFieldDefinition? {
    return getFieldAt(pathToField, pathIndex = 0)
}

private fun GraphQLFieldsContainer.getFieldAt(
    pathToField: List<String>,
    pathIndex: Int,
): GraphQLFieldDefinition? {
    val field = getField(pathToField[pathIndex])

    return if (pathIndex == pathToField.lastIndex) {
        field
    } else {
        val fieldOutputType = field.type as GraphQLFieldsContainer
        fieldOutputType.getFieldAt(pathToField, pathIndex + 1)
    }
}

fun NormalizedField.toBuilder(): NormalizedField.Builder {
    var builder: NormalizedField.Builder? = null
    transform { builder = it }
    return builder!!
}

fun NormalizedField.copyWithChildren(children: List<NormalizedField>): NormalizedField {
    fun fixParents(old: NormalizedField?, new: NormalizedField?) {
        if (old == null || new == null || new.parent == null) {
            return
        }
        val newParent = new.parent.toBuilder()
            .children(old.parent.children.filter { it !== old } + new)
            .build()
        new.replaceParent(newParent)
        // Do recursively for all ancestors
        fixParents(old = old.parent, new = newParent)
    }

    children.forEach {
        it.replaceParent(this)
    }

    return toBuilder()
        .children(children)
        .build()
        .also {
            fixParents(old = this, new = it)
        }
}
