package graphql.nadel.enginekt.transform.hydration.batch

import graphql.nadel.enginekt.blueprint.NadelBatchHydrationFieldInstruction
import graphql.nadel.enginekt.blueprint.hydration.NadelBatchHydrationMatchStrategy
import graphql.nadel.enginekt.blueprint.hydration.NadelHydrationActorInput
import graphql.nadel.enginekt.blueprint.hydration.NadelHydrationArgumentValueSource
import graphql.nadel.enginekt.transform.artificial.AliasHelper
import graphql.nadel.enginekt.transform.hydration.NadelHydrationInputBuilder.valueToAstValue
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

/**
 * README
 *
 * Please ensure that the batch arguments are ordered according to the input.
 * This is required for [NadelBatchHydrationMatchStrategy.MatchIndex].
 */
internal object NadelBatchHydrationInputBuilder {
    fun getInputValueBatches(
        aliasHelper: AliasHelper,
        instruction: NadelBatchHydrationFieldInstruction,
        hydrationField: NormalizedField,
        parentNodes: List<JsonNode>,
    ): List<Map<NadelHydrationActorInput, NormalizedInputValue>> {
        val sourceFieldDefinition = NadelHydrationUtil.getSourceFieldDefinition(instruction)

        val nonBatchArgs = getNonBatchInputValues(instruction, hydrationField)
        val batchArgs = getBatchInputValues(sourceFieldDefinition, instruction, parentNodes, aliasHelper)

        return batchArgs.map { nonBatchArgs + it }
    }

    private fun getNonBatchInputValues(
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
                    // These are batch values, ignore them
                    is NadelHydrationArgumentValueSource.FieldResultValue -> null
                }
            },
        )
    }

    private fun getBatchInputValues(
        sourceFieldDefinition: GraphQLFieldDefinition,
        instruction: NadelBatchHydrationFieldInstruction,
        parentNodes: List<JsonNode>,
        aliasHelper: AliasHelper,
    ): List<Pair<NadelHydrationActorInput, NormalizedInputValue>> {
        val batchSize = instruction.batchSize

        val (batchArg, fieldResultValueSource) = instruction.actorInputValues
            .asSequence()
            .mapNotNull {
                when (val valueSource = it.valueSource) {
                    is NadelHydrationArgumentValueSource.FieldResultValue -> it to valueSource
                    else -> null
                }
            }
            .emptyOrSingle() ?: return emptyList()

        val batchArgDef = sourceFieldDefinition.getArgument(batchArg.name)

        return getFieldResultValues(fieldResultValueSource, parentNodes, aliasHelper)
            .chunked(size = batchSize)
            .map { chunk ->
                batchArg to NormalizedInputValue(
                    GraphQLTypeUtil.simplePrint(batchArgDef.type),
                    valueToAstValue(chunk),
                )
            }
    }

    private fun getFieldResultValues(
        valueSource: NadelHydrationArgumentValueSource.FieldResultValue,
        parentNodes: List<JsonNode>,
        aliasHelper: AliasHelper,
    ): List<Any?> {
        return parentNodes.flatMap { parentNode ->
            val nodes = JsonNodeExtractor.getNodesAt(
                rootNode = parentNode,
                queryPath = aliasHelper.mapQueryPathRespectingResultKey(valueSource.queryPathToField),
                flatten = true,
            )

            nodes.asSequence()
                .map { it.value }
                .flatten(recursively = true)
                .toList()
        }
    }
}
