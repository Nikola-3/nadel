package graphql.nadel.enginekt.transform.hydration

import graphql.nadel.Service
import graphql.nadel.enginekt.blueprint.NadelBatchHydrationFieldInstruction
import graphql.nadel.enginekt.blueprint.NadelGenericHydrationInstruction
import graphql.nadel.enginekt.blueprint.NadelHydrationFieldInstruction
import graphql.nadel.enginekt.blueprint.NadelOverallExecutionBlueprint
import graphql.nadel.enginekt.blueprint.hydration.NadelHydrationActorInputDef
import graphql.nadel.enginekt.transform.GraphQLObjectTypeName
import graphql.nadel.enginekt.transform.artificial.NadelAliasHelper
import graphql.nadel.enginekt.transform.hydration.batch.NadelBatchHydrationInputBuilder
import graphql.nadel.enginekt.transform.hydration.batch.NadelBatchHydrationObjectIdFieldBuilder.makeObjectIdField
import graphql.nadel.enginekt.transform.query.NFUtil
import graphql.nadel.enginekt.transform.result.json.JsonNode
import graphql.nadel.enginekt.util.deepClone
import graphql.nadel.hooks.ServiceExecutionHooks
import graphql.normalized.ExecutableNormalizedField
import graphql.normalized.NormalizedInputValue

internal object NadelHydrationFieldsBuilder {
    fun makeActorQueries(
        instruction: NadelHydrationFieldInstruction,
        aliasHelper: NadelAliasHelper,
        fieldToHydrate: ExecutableNormalizedField,
        parentNode: JsonNode,
    ): List<ExecutableNormalizedField> {
        return NadelHydrationInputBuilder.getInputValues(
            instruction = instruction,
            aliasHelper = aliasHelper,
            fieldToHydrate = fieldToHydrate,
            parentNode = parentNode,
        ).map { args ->
            makeActorQueries(
                instruction = instruction,
                fieldArguments = args,
                fieldChildren = deepClone(fields = fieldToHydrate.children),
            )
        }
    }

    fun makeBatchActorQueries(
        executionBlueprint: NadelOverallExecutionBlueprint,
        instruction: NadelBatchHydrationFieldInstruction,
        aliasHelper: NadelAliasHelper,
        hydratedField: ExecutableNormalizedField,
        parentNodes: List<JsonNode>,
        hooks: ServiceExecutionHooks,
    ): List<ExecutableNormalizedField> {
        val argBatches = NadelBatchHydrationInputBuilder.getInputValueBatches(
            instruction = instruction,
            aliasHelper = aliasHelper,
            hydrationField = hydratedField,
            parentNodes = parentNodes,
            hooks = hooks
        )

        val fieldChildren = deepClone(fields = hydratedField.children) +
            makeObjectIdField(executionBlueprint, aliasHelper, instruction)

        return argBatches.map { argBatch ->
            makeActorQueries(
                instruction = instruction,
                fieldArguments = argBatch.mapKeys { (inputDef: NadelHydrationActorInputDef) -> inputDef.name },
                fieldChildren = fieldChildren,
            )
        }
    }

    fun makeRequiredSourceFields(
        service: Service,
        executionBlueprint: NadelOverallExecutionBlueprint,
        aliasHelper: NadelAliasHelper,
        objectTypeName: GraphQLObjectTypeName,
        instructions: List<NadelGenericHydrationInstruction>,
    ): List<ExecutableNormalizedField> {
        val underlyingTypeName = executionBlueprint.getUnderlyingTypeName(service, overallTypeName = objectTypeName)
        val underlyingObjectType = service.underlyingSchema.getObjectType(underlyingTypeName)
            ?: error("No underlying object type")

        return instructions
            .asSequence()
            .flatMap { it.sourceFields }
            .map {
                aliasHelper.toArtificial(
                    NFUtil.createField(
                        schema = service.underlyingSchema,
                        parentType = underlyingObjectType,
                        queryPathToField = it,
                        fieldArguments = emptyMap(),
                        fieldChildren = emptyList(), // This must be a leaf node
                    ),
                )
            }
            .toList()
    }

    private fun makeActorQueries(
        instruction: NadelGenericHydrationInstruction,
        fieldArguments: Map<String, NormalizedInputValue>,
        fieldChildren: List<ExecutableNormalizedField>,
    ): ExecutableNormalizedField {
        return NFUtil.createField(
            schema = instruction.actorService.underlyingSchema,
            parentType = instruction.actorService.underlyingSchema.queryType,
            queryPathToField = instruction.queryPathToActorField,
            fieldArguments = fieldArguments,
            fieldChildren = fieldChildren,
        )
    }
}
