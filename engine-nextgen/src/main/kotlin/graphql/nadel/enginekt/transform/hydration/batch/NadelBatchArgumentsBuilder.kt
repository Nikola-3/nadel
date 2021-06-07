package graphql.nadel.enginekt.transform.hydration.batch

import graphql.nadel.enginekt.blueprint.NadelBatchHydrationFieldInstruction
import graphql.nadel.enginekt.blueprint.hydration.NadelHydrationActorInput
import graphql.nadel.enginekt.blueprint.hydration.NadelHydrationArgumentValueSource
import graphql.nadel.enginekt.transform.artificial.ArtificialFields
import graphql.nadel.enginekt.transform.hydration.NadelHydrationArgumentsBuilder.valueToAstValue
import graphql.nadel.enginekt.transform.hydration.NadelHydrationUtil
import graphql.nadel.enginekt.transform.result.json.JsonNode
import graphql.nadel.enginekt.transform.result.json.JsonNodeExtractor
import graphql.nadel.enginekt.util.emptyOrSingle
import graphql.nadel.enginekt.util.flatten
import graphql.nadel.enginekt.util.mapFrom
import graphql.normalized.NormalizedField
import graphql.normalized.NormalizedInputValue
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLTypeUtil

internal object NadelBatchArgumentsBuilder {
    fun getArgumentBatches(
        artificialFields: ArtificialFields,
        instruction: NadelBatchHydrationFieldInstruction,
        hydrationField: NormalizedField,
        parentNodes: List<JsonNode>,
    ): List<Map<NadelHydrationActorInput, NormalizedInputValue>> {
        val sourceFieldDefinition = NadelHydrationUtil.getSourceFieldDefinition(instruction)

        val nonBatchArgs = getNonBatchArgs(instruction, hydrationField)
        val batchArgs = getBatchArgs(sourceFieldDefinition, instruction, parentNodes, artificialFields)

        return batchArgs.map { nonBatchArgs + it }
    }

    private fun getNonBatchArgs(
        instruction: NadelBatchHydrationFieldInstruction,
        hydrationField: NormalizedField,
    ): Map<NadelHydrationActorInput, NormalizedInputValue> {
        return mapFrom(
            instruction.actorInputValues.mapNotNull { sourceFieldArg ->
                when (val valueSource = sourceFieldArg.valueSource) {
                    is NadelHydrationArgumentValueSource.ArgumentValue -> {
                        when (val argValue = hydrationField.normalizedArguments[valueSource.argumentName]) {
                            null -> null
                            else -> sourceFieldArg to argValue
                        }
                    }
                    is NadelHydrationArgumentValueSource.QueriedFieldValue -> null
                }
            },
        )
    }

    private fun getBatchArgs(
        sourceFieldDefinition: GraphQLFieldDefinition,
        instruction: NadelBatchHydrationFieldInstruction,
        parentNodes: List<JsonNode>,
        artificialFields: ArtificialFields,
    ): List<Pair<NadelHydrationActorInput, NormalizedInputValue>> {
        val batchSize = instruction.batchSize

        val (batchArg, valueSource) = instruction.actorInputValues
            .asSequence()
            .mapNotNull {
                when (val valueSource = it.valueSource) {
                    is NadelHydrationArgumentValueSource.QueriedFieldValue -> it to valueSource
                    else -> null
                }
            }
            .emptyOrSingle() ?: return emptyList()

        val batchArgDef = sourceFieldDefinition.getArgument(batchArg.name)

        return getFieldValues(valueSource, parentNodes, artificialFields)
            .chunked(size = batchSize)
            .map { chunk ->
                batchArg to NormalizedInputValue(
                    GraphQLTypeUtil.simplePrint(batchArgDef.type),
                    valueToAstValue(chunk),
                )
            }
    }

    private fun getFieldValues(
        valueSourceQueried: NadelHydrationArgumentValueSource.QueriedFieldValue,
        parentNodes: List<JsonNode>,
        artificialFields: ArtificialFields,
    ): List<Any?> {
        return parentNodes.flatMap { parentNode ->
            val nodes = JsonNodeExtractor.getNodesAt(
                rootNode = parentNode,
                queryPath = artificialFields.mapQueryPathRespectingResultKey(valueSourceQueried.queryPath),
                flatten = true,
            )

            nodes.asSequence()
                .map { it.value }
                .flatten(recursively = true)
                .toList()
        }
    }
}
