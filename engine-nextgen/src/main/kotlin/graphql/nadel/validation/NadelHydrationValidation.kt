package graphql.nadel.validation

import graphql.nadel.Service
import graphql.nadel.dsl.RemoteArgumentSource
import graphql.nadel.dsl.RemoteArgumentSource.SourceType.FIELD_ARGUMENT
import graphql.nadel.dsl.RemoteArgumentSource.SourceType.OBJECT_FIELD
import graphql.nadel.dsl.UnderlyingServiceHydration
import graphql.nadel.enginekt.util.getFieldAt
import graphql.nadel.enginekt.util.isList
import graphql.nadel.enginekt.util.isNonNull
import graphql.nadel.enginekt.util.pathToActorField
import graphql.nadel.enginekt.util.unwrapAll
import graphql.nadel.enginekt.util.unwrapNonNull
import graphql.nadel.validation.NadelSchemaValidationError.CannotRenameHydratedField
import graphql.nadel.validation.NadelSchemaValidationError.DuplicatedHydrationArgument
import graphql.nadel.validation.NadelSchemaValidationError.HydrationFieldMustBeNullable
import graphql.nadel.validation.NadelSchemaValidationError.MissingHydrationActorField
import graphql.nadel.validation.NadelSchemaValidationError.MissingHydrationActorFieldArgument
import graphql.nadel.validation.NadelSchemaValidationError.MissingHydrationActorService
import graphql.nadel.validation.NadelSchemaValidationError.MissingHydrationArgumentValueSource
import graphql.nadel.validation.NadelSchemaValidationError.MissingHydrationFieldValueSource
import graphql.nadel.validation.util.NadelSchemaUtil.getHydrations
import graphql.nadel.validation.util.NadelSchemaUtil.hasRename
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema

internal class NadelHydrationValidation(
    private val services: Map<String, Service>,
    private val typeValidation: NadelTypeValidation,
    private val overallSchema: GraphQLSchema,
) {
    fun validate(
        parent: NadelServiceSchemaElement,
        overallField: GraphQLFieldDefinition,
    ): List<NadelSchemaValidationError> {
        if (hasRename(overallField)) {
            return listOf(
                CannotRenameHydratedField(parent, overallField),
            )
        }

        val hydrations = getHydrations(overallField, overallSchema)
        if (hydrations.isEmpty()) {
            error("Don't invoke hydration validation if there is no hydration silly")
        }

        return hydrations
            .asSequence()
            .flatMap { hydration ->
                val actorService = services[hydration.serviceName]
                    ?: return@flatMap listOf(
                        MissingHydrationActorService(parent, overallField, hydration),
                    )

                val actorServiceQueryType = actorService.underlyingSchema.queryType
                val actorField = actorServiceQueryType.getFieldAt(hydration.pathToActorField)
                    ?: return@flatMap listOf(
                        MissingHydrationActorField(parent, overallField, hydration, actorServiceQueryType),
                    )

                getArgumentErrors(parent, overallField, hydration, actorServiceQueryType, actorField) +
                    getOutputTypeIssues(parent, overallField, actorService, actorField) +
                    getBatchHydrationErrors(parent, overallField, hydration, actorField)
            }
            .toList()
    }

    private fun getBatchHydrationErrors(
        parent: NadelServiceSchemaElement,
        overallField: GraphQLFieldDefinition,
        hydration: UnderlyingServiceHydration,
        actorField: GraphQLFieldDefinition,
    ): List<NadelSchemaValidationError> {
        if (!hydration.isBatched && !actorField.type.unwrapNonNull().isList) {
            return emptyList()
        }

        if (!actorField.type.unwrapNonNull().isList) {
            // todo return an error here
        }

        val sourceArgCount = hydration.arguments
            .count {
                it.remoteArgumentSource.sourceType == OBJECT_FIELD
            }

        if (sourceArgCount == 0) {
            // todo return an error here
        } else if (sourceArgCount > 1) {
            // todo return an error here
        }

        return emptyList()
    }

    private fun getOutputTypeIssues(
        parent: NadelServiceSchemaElement,
        overallField: GraphQLFieldDefinition,
        actorService: Service,
        actorField: GraphQLFieldDefinition,
    ): List<NadelSchemaValidationError> {
        // Ensures that the underlying type of the actor field matches with the expected overall output type
        val typeValidation = typeValidation.validate(
            NadelServiceSchemaElement(
                overall = overallField.type.unwrapAll(),
                underlying = actorField.type.unwrapAll(),
                service = actorService,
            )
        )

        // Hydrations can error out so they MUST always be nullable
        val outputTypeMustBeNullable = if (overallField.type.isNonNull) {
            listOf(
                HydrationFieldMustBeNullable(parent, overallField)
            )
        } else {
            emptyList()
        }

        return typeValidation + outputTypeMustBeNullable
    }

    private fun getArgumentErrors(
        parent: NadelServiceSchemaElement,
        overallField: GraphQLFieldDefinition,
        hydration: UnderlyingServiceHydration,
        actorServiceQueryType: GraphQLObjectType,
        actorField: GraphQLFieldDefinition,
    ): List<NadelSchemaValidationError> {
        // Can only provide one value for an argument
        val duplicatedArgumentsErrors = hydration.arguments
            .groupBy { it.name }
            .filterValues { it.size > 1 }
            .values
            .map {
                DuplicatedHydrationArgument(parent, overallField, it)
            }

        val remoteArgErrors = hydration.arguments.mapNotNull { remoteArg ->
            val actorFieldArgument = actorField.getArgument(remoteArg.name)
            if (actorFieldArgument == null) {
                MissingHydrationActorFieldArgument(
                    parent,
                    overallField,
                    hydration,
                    actorServiceQueryType,
                    argument = remoteArg.name,
                )
            } else {
                val remoteArgSource = remoteArg.remoteArgumentSource
                getRemoteArgErrors(parent, overallField, remoteArgSource)
            }
        }

        return duplicatedArgumentsErrors + remoteArgErrors
    }

    private fun getRemoteArgErrors(
        parent: NadelServiceSchemaElement,
        overallField: GraphQLFieldDefinition,
        remoteArgSource: RemoteArgumentSource,
    ): NadelSchemaValidationError? {
        return when (remoteArgSource.sourceType) {
            OBJECT_FIELD -> {
                val field = (parent.underlying as GraphQLFieldsContainer).getFieldAt(remoteArgSource.path)
                if (field == null) {
                    MissingHydrationFieldValueSource(parent, overallField, remoteArgSource)
                } else {
                    // TODO: check argument type is correct
                    null
                }
            }
            FIELD_ARGUMENT -> {
                val argument = overallField.getArgument(remoteArgSource.name)
                if (argument == null) {
                    MissingHydrationArgumentValueSource(parent, overallField, remoteArgSource)
                } else {
                    // TODO: check argument type is correct
                    null
                }
            }
            else -> {
                null
            }
        }
    }
}
