package graphql.nadel.enginekt.blueprint

import graphql.Scalars.GraphQLBoolean
import graphql.Scalars.GraphQLFloat
import graphql.Scalars.GraphQLID
import graphql.Scalars.GraphQLInt
import graphql.Scalars.GraphQLString
import graphql.language.EnumTypeDefinition
import graphql.language.FieldDefinition
import graphql.language.ImplementingTypeDefinition
import graphql.nadel.Service
import graphql.nadel.dsl.EnumTypeDefinitionWithTransformation
import graphql.nadel.dsl.ExtendedFieldDefinition
import graphql.nadel.dsl.FieldMappingDefinition
import graphql.nadel.dsl.InputObjectTypeDefinitionWithTransformation
import graphql.nadel.dsl.InterfaceTypeDefinitionWithTransformation
import graphql.nadel.dsl.ObjectTypeDefinitionWithTransformation
import graphql.nadel.dsl.RemoteArgumentSource.SourceType.FIELD_ARGUMENT
import graphql.nadel.dsl.RemoteArgumentSource.SourceType.OBJECT_FIELD
import graphql.nadel.dsl.TypeMappingDefinition
import graphql.nadel.dsl.UnderlyingServiceHydration
import graphql.nadel.enginekt.blueprint.hydration.NadelBatchHydrationMatchStrategy
import graphql.nadel.enginekt.blueprint.hydration.NadelHydrationActorInputDef
import graphql.nadel.enginekt.blueprint.hydration.NadelHydrationStrategy
import graphql.nadel.enginekt.transform.query.NadelQueryPath
import graphql.nadel.enginekt.util.AnyImplementingTypeDefinition
import graphql.nadel.enginekt.util.AnyNamedNode
import graphql.nadel.enginekt.util.emptyOrSingle
import graphql.nadel.enginekt.util.getFieldAt
import graphql.nadel.enginekt.util.getFieldsAlong
import graphql.nadel.enginekt.util.getOperationType
import graphql.nadel.enginekt.util.isConcrete
import graphql.nadel.enginekt.util.isExtensionDef
import graphql.nadel.enginekt.util.isList
import graphql.nadel.enginekt.util.isNotExtensionDef
import graphql.nadel.enginekt.util.makeFieldCoordinates
import graphql.nadel.enginekt.util.mapFrom
import graphql.nadel.enginekt.util.strictAssociateBy
import graphql.nadel.enginekt.util.toMapStrictly
import graphql.nadel.enginekt.util.unwrapAll
import graphql.nadel.enginekt.util.unwrapNonNull
import graphql.nadel.schema.NadelDirectives
import graphql.schema.FieldCoordinates
import graphql.schema.GraphQLDirectiveContainer
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLUnionType
import graphql.schema.GraphQLUnmodifiedType

const val sharedServiceName = "shared"

internal object NadelExecutionBlueprintFactory {
    fun create(overallSchema: GraphQLSchema, services: List<Service>): NadelOverallExecutionBlueprint {
        return Factory(overallSchema, services).make()
    }
}

private class Factory(
    private val overallSchema: GraphQLSchema,
    private val services: List<Service>,
) {
    private val definitionNamesToService: Map<String, Service> = makeDefinitionNamesToService()
    private val coordinatesToService: Map<FieldCoordinates, Service> = makeCoordinatesToService()
    private val typeRenameInstructions = makeTypeRenameInstructions()
    private val typesToServices: Map<String, Set<Service>> =
        TypeOwnership(overallSchema, services, typeRenameInstructions)
            .getTypesToServicesThatDefineThem()

    fun make(): NadelOverallExecutionBlueprint {
        val typeRenameInstructionsByOverallName = typeRenameInstructions.strictAssociateBy {
            it.overallName
        }
        val fieldInstructions = makeFieldInstructions().strictAssociateBy {
            it.location
        }
        val furtherTypeRenameInstructions = typeRenameInstructions +
            SharedTypesAnalysis(overallSchema, services, fieldInstructions, typeRenameInstructionsByOverallName)
                .getTypeRenames()

        return NadelOverallExecutionBlueprint(
            schema = overallSchema,
            fieldInstructions = fieldInstructions,
            underlyingBlueprints = deriveUnderlyingBlueprints(furtherTypeRenameInstructions),
            coordinatesToService = coordinatesToService,
        )
    }

    private fun makeFieldInstructions(): List<NadelFieldInstruction> {
        return overallSchema.typeMap.values
            .asSequence()
            .filterIsInstance<GraphQLObjectType>()
            .flatMap { type ->
                type.fields
                    .asSequence()
                    // Get the field mapping def
                    .mapNotNull { field ->
                        when (val mappingDefinition = getFieldMappingDefinition(field)) {
                            null -> when (val hydration = getUnderlyingServiceHydration(field)) {
                                null -> null
                                else -> makeHydrationFieldInstruction(type, field, hydration)
                            }
                            else -> when (mappingDefinition.inputPath.size) {
                                1 -> makeRenameInstruction(type, field, mappingDefinition)
                                else -> makeDeepRenameFieldInstruction(type, field, mappingDefinition)
                            }
                        }
                    }
            }
            .toList()
    }

    private fun makeDeepRenameFieldInstruction(
        parentType: GraphQLObjectType,
        field: GraphQLFieldDefinition,
        mappingDefinition: FieldMappingDefinition,
    ): NadelFieldInstruction {
        val location = makeFieldCoordinates(parentType, field)

        return NadelDeepRenameFieldInstruction(
            location,
            NadelQueryPath(mappingDefinition.inputPath),
        )
    }

    private fun makeHydrationFieldInstruction(
        hydratedFieldParentType: GraphQLObjectType,
        hydratedFieldDef: GraphQLFieldDefinition,
        hydration: UnderlyingServiceHydration,
    ): NadelFieldInstruction {
        val hydrationActorService = services.single { it.name == hydration.serviceName }
        val actorFieldSchema = hydrationActorService.underlyingSchema

        val queryPathToActorField = listOfNotNull(hydration.syntheticField, hydration.topLevelField)
        val actorFieldDef = actorFieldSchema.queryType.getFieldAt(queryPathToActorField)!!

        if (hydration.isBatched || /*deprecated*/ actorFieldDef.type.unwrapNonNull().isList) {
            require(actorFieldDef.type.unwrapNonNull().isList) { "Batched hydration at '$queryPathToActorField' requires a list output type" }
            return makeBatchHydrationFieldInstruction(
                parentType = hydratedFieldParentType,
                hydratedFieldDef = hydratedFieldDef,
                actorFieldDef = actorFieldDef,
                hydration = hydration,
                actorService = hydrationActorService,
            )
        }

        val actorInputValueDefs = getHydrationArguments(
            hydration = hydration,
            hydratedFieldParentType = hydratedFieldParentType,
            hydratedFieldDef = hydratedFieldDef,
            actorFieldDef = actorFieldDef,
        )
        return NadelHydrationFieldInstruction(
            location = makeFieldCoordinates(hydratedFieldParentType, hydratedFieldDef),
            hydratedFieldDef = hydratedFieldDef,
            actorService = hydrationActorService,
            queryPathToActorField = NadelQueryPath(queryPathToActorField),
            actorFieldDef = actorFieldDef,
            actorInputValueDefs = actorInputValueDefs,
            hydrationStrategy = getHydrationStrategy(
                hydratedFieldParentType = hydratedFieldParentType,
                hydratedFieldDef = hydratedFieldDef,
                actorFieldDef = actorFieldDef,
                actorInputValueDefs = actorInputValueDefs,
            ),
        )
    }

    private fun getHydrationStrategy(
        hydratedFieldParentType: GraphQLObjectType,
        hydratedFieldDef: GraphQLFieldDefinition,
        actorFieldDef: GraphQLFieldDefinition,
        actorInputValueDefs: List<NadelHydrationActorInputDef>,
    ): NadelHydrationStrategy {
        val manyToOneInputDef = actorInputValueDefs
            .asSequence()
            .mapNotNull { inputValueDef ->
                if (inputValueDef.valueSource !is NadelHydrationActorInputDef.ValueSource.FieldResultValue) {
                    return@mapNotNull null
                }

                val fieldDefs = mapFirstNotNullFromUnderlyingType(hydratedFieldParentType) { underlyingParentType ->
                    // Makes sure that we get one of the underlying types with the info we need to extract out of it defined
                    underlyingParentType.getFieldsAlong(inputValueDef.valueSource.queryPathToField.segments)
                } ?: error("No underlying type for: ${hydratedFieldParentType.name}")

                inputValueDef.takeIf {
                    fieldDefs.any { fieldDef ->
                        fieldDef.type.unwrapNonNull().isList
                            && !actorFieldDef.getArgument(inputValueDef.name).type.unwrapNonNull().isList
                    }
                }
            }
            .emptyOrSingle()

        return if (manyToOneInputDef != null) {
            if (!hydratedFieldDef.type.unwrapNonNull().isList) {
                error("Illegal hydration declaration")
            }
            NadelHydrationStrategy.ManyToOne(manyToOneInputDef)
        } else {
            NadelHydrationStrategy.OneToOne
        }
    }

    private fun makeBatchHydrationFieldInstruction(
        parentType: GraphQLObjectType,
        hydratedFieldDef: GraphQLFieldDefinition,
        actorFieldDef: GraphQLFieldDefinition,
        hydration: UnderlyingServiceHydration,
        actorService: Service,
    ): NadelFieldInstruction {
        val location = makeFieldCoordinates(parentType, hydratedFieldDef)

        return NadelBatchHydrationFieldInstruction(
            location = location,
            hydratedFieldDef = hydratedFieldDef,
            actorService = actorService,
            queryPathToActorField = NadelQueryPath(listOfNotNull(hydration.syntheticField, hydration.topLevelField)),
            actorFieldDef = actorFieldDef,
            actorInputValueDefs = getHydrationArguments(hydration, parentType, hydratedFieldDef, actorFieldDef),
            batchSize = hydration.batchSize ?: 50,
            batchHydrationMatchStrategy = if (hydration.isObjectMatchByIndex) {
                NadelBatchHydrationMatchStrategy.MatchIndex
            } else {
                NadelBatchHydrationMatchStrategy.MatchObjectIdentifier(objectId = hydration.objectIdentifier)
            },
        )
    }

    private fun makeRenameInstruction(
        parentType: GraphQLObjectType,
        field: GraphQLFieldDefinition,
        mappingDefinition: FieldMappingDefinition,
    ): NadelRenameFieldInstruction {
        return NadelRenameFieldInstruction(
            location = makeFieldCoordinates(parentType, field),
            underlyingName = mappingDefinition.inputPath.single(),
        )
    }

    private fun makeTypeRenameInstructions(): List<NadelTypeRenameInstruction> {
        return overallSchema.typeMap.values
            .asSequence()
            .filterIsInstance<GraphQLDirectiveContainer>()
            .mapNotNull(this::makeTypeRenameInstruction)
            .toList()
    }

    private fun makeTypeRenameInstruction(type: GraphQLDirectiveContainer): NadelTypeRenameInstruction? {
        return when (val def = type.definition) {
            is ObjectTypeDefinitionWithTransformation -> makeTypeRenameInstruction(def.typeMappingDefinition)
            is InterfaceTypeDefinitionWithTransformation -> makeTypeRenameInstruction(def.typeMappingDefinition)
            is InputObjectTypeDefinitionWithTransformation -> makeTypeRenameInstruction(def.typeMappingDefinition)
            is EnumTypeDefinitionWithTransformation -> makeTypeRenameInstruction(def.typeMappingDefinition)
            else -> when (val typeMappingDef = NadelDirectives.createTypeMapping(type)) {
                null -> null
                else -> makeTypeRenameInstruction(typeMappingDef)
            }
        }
    }

    private fun makeTypeRenameInstruction(typeMappingDefinition: TypeMappingDefinition): NadelTypeRenameInstruction {
        val overallName = typeMappingDefinition.overallName

        return NadelTypeRenameInstruction(
            service = definitionNamesToService[overallName]
                ?: error("Unable to determine what service owns type: $overallName"),
            overallName = overallName,
            underlyingName = typeMappingDefinition.underlyingName,
        )
    }

    private fun getHydrationArguments(
        hydration: UnderlyingServiceHydration,
        hydratedFieldParentType: GraphQLObjectType,
        hydratedFieldDef: GraphQLFieldDefinition,
        actorFieldDef: GraphQLFieldDefinition,
    ): List<NadelHydrationActorInputDef> {
        return hydration.arguments.map { remoteArgDef ->
            val valueSource = when (val argSourceType = remoteArgDef.remoteArgumentSource.sourceType) {
                FIELD_ARGUMENT -> {
                    val argumentName = remoteArgDef.remoteArgumentSource.name
                    NadelHydrationActorInputDef.ValueSource.ArgumentValue(
                        argumentName = argumentName,
                        argumentDefinition = hydratedFieldDef.getArgument(argumentName)
                            ?: error("No argument '$argumentName' on field ${hydratedFieldParentType.name}.${hydratedFieldDef.name}"),
                    )
                }
                OBJECT_FIELD -> {
                    val pathToField = remoteArgDef.remoteArgumentSource.path
                    NadelHydrationActorInputDef.ValueSource.FieldResultValue(
                        queryPathToField = NadelQueryPath(pathToField),
                        fieldDefinition = mapFirstNotNullFromUnderlyingType(hydratedFieldParentType) { underlyingParentType ->
                            underlyingParentType.getFieldAt(pathToField)
                        }
                            ?: error("No field defined at: ${hydratedFieldParentType.name}.${pathToField.joinToString(".")}"),
                    )
                }
                else -> error("Unsupported remote argument source type: '$argSourceType'")
            }

            NadelHydrationActorInputDef(
                name = remoteArgDef.name,
                actorArgumentDef = actorFieldDef.getArgument(remoteArgDef.name),
                valueSource = valueSource,
            )
        }
    }

    /**
     * Ok this looks at all the possible underlying types. Feeds the underlying type
     * into [mapFunc] and then if the [mapFunc] return something not null, the function
     * will return that.
     */
    private fun <T : GraphQLType, R> mapFirstNotNullFromUnderlyingType(
        overallType: T,
        mapFunc: (T) -> R?,
    ): R? {
        val unwrappedType: GraphQLUnmodifiedType = overallType.unwrapAll()
        // I tried reusing this value and the Kotlin compiler complained
        val directivesContainer = overallType.unwrapAll() as GraphQLDirectiveContainer
        val renameInstruction = makeTypeRenameInstruction(directivesContainer)

        return typesToServices[unwrappedType.name]
            ?.mapNotNull { service ->
                val underlyingName = renameInstruction?.underlyingName ?: unwrappedType.name
                service.underlyingSchema.getTypeAs<T>(underlyingName)
            }
            ?.map(mapFunc)
            ?.firstOrNull()
    }

    private fun getFieldMappingDefinition(field: GraphQLFieldDefinition): FieldMappingDefinition? {
        val extendedDef = field.definition as? ExtendedFieldDefinition
        return extendedDef?.fieldTransformation?.fieldMappingDefinition
            ?: NadelDirectives.createFieldMapping(field)
    }

    private fun getUnderlyingServiceHydration(field: GraphQLFieldDefinition): UnderlyingServiceHydration? {
        val extendedDef = field.definition as? ExtendedFieldDefinition
        return extendedDef?.fieldTransformation?.underlyingServiceHydration
            ?: NadelDirectives.createUnderlyingServiceHydration(field)
    }

    private fun deriveUnderlyingBlueprints(
        typeRenameInstructions: List<NadelTypeRenameInstruction>,
    ): Map<String, NadelUnderlyingExecutionBlueprint> {
        val typeInstructionsByServiceName = typeRenameInstructions
            .groupBy { instruction ->
                instruction.service.name
            }

        return mapFrom(
            services.map { service ->
                service.name to NadelUnderlyingExecutionBlueprint(
                    service,
                    schema = service.underlyingSchema,
                    typeInstructions = typeInstructionsByServiceName[service.name] ?: emptyList(),
                )
            }
        )
    }

    private fun makeDefinitionNamesToService(): Map<String, Service> {
        return mapFrom(
            services.flatMap { service ->
                val operationTypes = service.definitionRegistry.operationMap.values.flatten()
                service.definitionRegistry.definitions
                    .filterIsInstance<AnyNamedNode>()
                    .filterNot { it.isExtensionDef }
                    .filterNot { def -> def in operationTypes }
                    .map { def -> def.name to service }
            }
        )
    }

    private fun makeCoordinatesToService(): Map<FieldCoordinates, Service> {
        return mapFrom(
            services.flatMap { service ->
                service.definitionRegistry.definitions
                    .filterIsInstance<AnyNamedNode>()
                    .flatMap { typeDef ->
                        when (typeDef) {
                            is EnumTypeDefinition -> typeDef.enumValueDefinitions.map { enumValue ->
                                makeFieldCoordinates(typeDef.name, enumValue.name)
                            }
                            is ImplementingTypeDefinition -> typeDef.fieldDefinitions.map { fieldDef ->
                                makeFieldCoordinates(typeDef.name, fieldDef.name)
                            }
                            else -> emptyList()
                        }
                    }
                    .map { coordinates -> coordinates to service }
            }
        )
    }
}

/**
 * So in Nadel we have an problem where our type rename syntax is not sufficient for shared types.
 *
 * For example, given an overall schema:
 *
 * ```graphql
 * service A {
 *   type SharedThing @renamed(from: "Thing") {
 *     id: ID
 *   }
 * }
 * service B {
 *   type Query {
 *     test: SharedThing
 *   }
 * }
 * ```
 *
 * And underlying schemas:
 *
 * ```graphql
 * # Service A's underlying schema
 * type Thing {
 *   id: ID
 * }
 *
 * # Service B's underlying schema
 * type Query {
 *   test: NewThing
 * }
 * type NewThing {
 *   id: ID
 * }
 * ```
 *
 * The question for this schema is: how do we know that `NewThing` from service B is actually
 * `SharedThing` in the overall schema?
 *
 * Ideally we would have:
 *
 * ```graphql
 * type SharedThing @renamed(from: [ {service: "A", type: "Thing"}, {service: "B", type: "NewThing"} ]) {
 *   id: ID
 * }
 * ```
 *
 * But we haven't even fully moved to the new directive based syntax yet. This code bridges that.
 *
 * The code looks at top level fields, and then children fields of the output type, recursively.
 * Then it compares the output type from the overall and underlying schema to build the missing
 * rename definition.
 *
 * e.g. for `test: NewThing` we can see that the overall schema defines it as `test: Shared` so
 * we can assume that `NewThing` was renamed to `Shared` in the overall schema.
 */
private class SharedTypesAnalysis(
    private val overallSchema: GraphQLSchema,
    private val services: List<Service>,
    private val fieldInstructions: Map<FieldCoordinates, NadelFieldInstruction>,
    private val typeRenameInstructions: Map<String, NadelTypeRenameInstruction>,
) {
    companion object {
        private val scalarTypeNames = sequenceOf(GraphQLInt, GraphQLFloat, GraphQLString, GraphQLBoolean, GraphQLID)
            .map { it.name }
            // This is required because of edge cases with central schema ppl turning a Date into a DateTime…
            .plus(sequenceOf("Date", "DateTime"))
            .toSet()
    }

    fun getTypeRenames(): Set<NadelTypeRenameInstruction> {
        return services
            .asSequence()
            .flatMap { service ->
                // Keeps track of visited types to avoid stackoverflow
                val visitedTypes = mutableSetOf<String>()

                val serviceDefinedTypes = service.definitionRegistry.definitions
                    .asSequence()
                    .filterIsInstance<AnyNamedNode>()
                    .filterNot { it.isExtensionDef }
                    .map { it.name }
                    .toSet() + scalarTypeNames

                service.definitionRegistry.operationMap
                    .asSequence()
                    .flatMap forOperation@{ (operationKind, overallOperationTypes) ->
                        val underlyingOperationType = service.underlyingSchema.getOperationType(operationKind)
                            ?: return@forOperation emptySequence()

                        overallOperationTypes
                            .asSequence()
                            .flatMap { overallOperationType ->
                                investigateTypeRenames(
                                    visitedTypes,
                                    service,
                                    serviceDefinedTypes,
                                    overallType = overallOperationType,
                                    underlyingType = underlyingOperationType,
                                    isOperationType = true,
                                )
                            }
                    }
            }
            .toSet()
    }

    private fun investigateTypeRenames(
        visitedTypes: MutableSet<String>,
        service: Service,
        serviceDefinedTypes: Set<String>,
        overallType: AnyImplementingTypeDefinition,
        underlyingType: GraphQLFieldsContainer,
        isOperationType: Boolean = false,
    ): List<NadelTypeRenameInstruction> {
        // Record visited types to avoid stack overflow
        if (!isOperationType && overallType.name in visitedTypes) {
            return emptyList()
        }
        visitedTypes.add(overallType.name)

        return overallType.fieldDefinitions.flatMap { overallField ->
            investigateTypeRenames(
                visitedTypes,
                service,
                serviceDefinedTypes,
                overallField = overallField,
                overallParentType = overallType,
                underlyingParentType = underlyingType,
            )
        }
    }

    private fun investigateTypeRenames(
        visitedTypes: MutableSet<String>,
        service: Service,
        serviceDefinedTypes: Set<String>,
        overallField: FieldDefinition,
        overallParentType: AnyImplementingTypeDefinition,
        underlyingParentType: GraphQLFieldsContainer,
    ): List<NadelTypeRenameInstruction> {
        val overallOutputTypeName = overallField.type.unwrapAll().name

        val underlyingField = getUnderlyingField(overallField, overallParentType, underlyingParentType)
            ?: return emptyList()

        val renameInstruction = if (overallOutputTypeName !in serviceDefinedTypes) {
            // Service does not own type, it is shared
            // If the name is  different than the overall type, then we mark the rename
            when (val underlyingOutputTypeName = underlyingField.type.unwrapAll().name) {
                overallOutputTypeName -> null
                in scalarTypeNames -> null
                else -> when (typeRenameInstructions[overallOutputTypeName]) {
                    null -> error("Nadel does not allow implicit renames")
                    else -> NadelTypeRenameInstruction(
                        service,
                        overallName = overallOutputTypeName,
                        underlyingName = underlyingOutputTypeName,
                    )
                }
            }
        } else {
            null
        }

        val overallOutputType = overallSchema.getType(overallOutputTypeName)
            // Ensure type exists, schema transformation can delete types, so let's just ignore it
            .let { it ?: return emptyList() }
            // Return if not field container
            .let { it as? GraphQLFieldsContainer ?: return emptyList() }
            .let { it.definition as AnyImplementingTypeDefinition }

        return listOfNotNull(renameInstruction) + investigateTypeRenames(
            visitedTypes,
            service,
            serviceDefinedTypes,
            overallType = overallOutputType,
            underlyingType = underlyingField.type.unwrapAll() as GraphQLFieldsContainer,
        )
    }

    private fun getUnderlyingField(
        overallField: FieldDefinition,
        overallParentType: AnyImplementingTypeDefinition,
        underlyingParentType: GraphQLFieldsContainer,
    ): GraphQLFieldDefinition? {
        // Access instruction via overall schema coordinates
        val overallCoordinates = makeFieldCoordinates(overallParentType.name, overallField.name)

        // Honestly, it would be nice stricter validation here, but it's so cooked that we can't
        return when (val instruction = fieldInstructions[overallCoordinates]) {
            null -> underlyingParentType.getField(overallField.name)
            is NadelRenameFieldInstruction -> underlyingParentType.getField(instruction.underlyingName)
            is NadelDeepRenameFieldInstruction -> underlyingParentType.getFieldAt(instruction.queryPathToField.segments)
            else -> null
        }
    }
}

private class TypeOwnership(
    private val overallSchema: GraphQLSchema,
    private val services: List<Service>,
    private val typeRenames: List<NadelTypeRenameInstruction>,
) {
    fun getTypesToServicesThatDefineThem(): Map<String, Set<Service>> {
        return getTypesThatServiceDefines()
            .entries
            .asSequence()
            .flatMap { (service, typesDefined) ->
                typesDefined
                    .asSequence()
                    .map {
                        it to service
                    }
            }
            .groupBy {
                it.first
            }
            .mapValues { it: Map.Entry<String, List<Pair<String, Service>>> ->
                it.value // Get the List<Pair<…>> out
                    .asSequence()
                    .map {
                        it.second // Get the Service out of the Pair<String, Service>
                    }
                    .toSet()
            }
    }

    fun getTypesThatServiceDefines(): Map<Service, Set<String>> {
        return services
            .map { service ->
                service to getTypeThatServiceDefined(service)
            }
            .toMapStrictly()
    }

    private fun getTypeThatServiceDefined(service: Service): Set<String> {
        val typesDefined = service.definitionRegistry.definitions
            .asSequence()
            .filterIsInstance<AnyNamedNode>()
            .filterNot { it.isExtensionDef }
            .map { it.name }
            .toHashSet()

        val rawTypesUsedAsOutputTypes = service.definitionRegistry.definitions
            .asSequence()
            .filterIsInstance<AnyNamedNode>()
            .filter {
                // If the definition is an extension, only consider it if it is extending a type the service owns
                it.isNotExtensionDef || it.name in typesDefined
            }
            .filterIsInstance<AnyImplementingTypeDefinition>()
            .flatMap {
                it.fieldDefinitions
            }
            .filterNot {
                isHydrationField(it)
            }
            .map {
                it.type.unwrapAll().name
            }
            .toHashSet()

        // Resolve the types to reachable types i.e. interfaces to their implementations, unions etc.
        val outputTypesResolved = rawTypesUsedAsOutputTypes
            .asSequence()
            .flatMap { rawTypeName ->
                resolveType(rawTypeName) { missingTypeName ->
                    error("Service tried to reference type '$missingTypeName' that does not exist")
                }
            }
            // Determine ownership of these types
            .filter { typeName ->
                if (typeName in typesDefined || typeName in rawTypesUsedAsOutputTypes) {
                    true
                } else {
                    val underlyingTypeNameOrNull = typeRenames
                        .asSequence()
                        .filter { it.service.name == service.name && it.overallName == typeName }
                        .map { it.underlyingName }
                        .emptyOrSingle()

                    if (underlyingTypeNameOrNull != null) {
                        service.underlyingSchema.getType(underlyingTypeNameOrNull) != null
                    } else {
                        // In this case, we need to find the type and it cannot have been exposed
                        // Note that this is a dumb heuristic and we need better solutions in the future
                        service.underlyingSchema.getType(typeName) != null && typeRenames.none { typeRename ->
                            typeRename.service.name == service.name && typeRename.underlyingName == typeName
                        }
                    }
                }
            }
            .toList()

        return sequenceOf(typesDefined, rawTypesUsedAsOutputTypes, outputTypesResolved)
            .flatten()
            .toHashSet()
    }

    private fun isHydrationField(def: FieldDefinition): Boolean {
        val asExtended = def as? ExtendedFieldDefinition
        if (asExtended?.fieldTransformation?.underlyingServiceHydration != null) {
            return true
        }
        return def.directivesByName.any {
            it.key == NadelDirectives.HYDRATED_DIRECTIVE_DEFINITION.name
        }
    }

    private fun resolveType(
        typeName: String,
        onTypeNotFound: (String) -> Nothing,
    ): List<String> {
        val type = overallSchema.getType(typeName) as GraphQLNamedType?
            ?: onTypeNotFound(typeName)

        return if (type.isConcrete) {
            listOf(type.name)
        } else {
            when (type) {
                is GraphQLUnionType -> type.types.flatMap {
                    // Could be an interface in a union
                    resolveType(it.name, onTypeNotFound)
                }
                is GraphQLInterfaceType -> overallSchema.getImplementations(type).map {
                    // These are object types, nothing further to resolve
                    it.name
                }
                else -> error("There are only two abstract types in GraphQL")
            }
        }
    }
}
