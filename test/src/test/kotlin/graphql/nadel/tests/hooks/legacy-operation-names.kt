package graphql.nadel.tests.hooks

import graphql.nadel.NadelExecutionInput
import graphql.nadel.Service
import graphql.nadel.ServiceExecutionResult
import graphql.nadel.enginekt.NadelExecutionContext
import graphql.nadel.enginekt.blueprint.NadelOverallExecutionBlueprint
import graphql.nadel.enginekt.transform.NadelTransform
import graphql.nadel.enginekt.transform.NadelTransformFieldResult
import graphql.nadel.enginekt.transform.query.NadelQueryTransformer
import graphql.nadel.enginekt.transform.result.NadelResultInstruction
import graphql.nadel.tests.EngineTestHook
import graphql.nadel.tests.UseHook
import graphql.nadel.tests.NadelEngineType
import graphql.normalized.ExecutableNormalizedField

abstract class `legacy-operation-names` : EngineTestHook {
    override fun makeExecutionInput(
        engineType: NadelEngineType,
        builder: NadelExecutionInput.Builder,
    ): NadelExecutionInput.Builder {
        return builder.transformExecutionHints { it.legacyOperationNames(true) }
    }
}

@UseHook
class `can-generate-legacy-operation-names` : `legacy-operation-names`() {
}

@UseHook
class `can-generate-legacy-operation-names-forwarding-original-name` : `legacy-operation-names`() {
}

@UseHook
class `can-generate-legacy-operation-name-on-hydration` : `legacy-operation-names`() {
}

@UseHook
class `can-generate-legacy-operation-name-on-batch-hydration` : `legacy-operation-names`() {
}

@UseHook
class `can-generate-legacy-operation-name-on-batch-hydration-for-specific-service` : EngineTestHook {
    override val customTransforms: List<NadelTransform<out Any>> = listOf(
        object : NadelTransform<Any> {
            override suspend fun isApplicable(
                executionContext: NadelExecutionContext,
                executionBlueprint: NadelOverallExecutionBlueprint,
                services: Map<String, Service>,
                service: Service,
                overallField: ExecutableNormalizedField,
            ): Any? {
                if (service.name == "service2") {
                    executionContext.hints.legacyOperationNames = true
                }
                return null
            }

            override suspend fun transformField(
                executionContext: NadelExecutionContext,
                transformer: NadelQueryTransformer,
                executionBlueprint: NadelOverallExecutionBlueprint,
                service: Service,
                field: ExecutableNormalizedField,
                state: Any,
            ): NadelTransformFieldResult {
                error("no-op")
            }

            override suspend fun getResultInstructions(
                executionContext: NadelExecutionContext,
                executionBlueprint: NadelOverallExecutionBlueprint,
                service: Service,
                overallField: ExecutableNormalizedField,
                underlyingParentField: ExecutableNormalizedField?,
                result: ServiceExecutionResult,
                state: Any,
            ): List<NadelResultInstruction> {
                error("no-op")
            }
        }
    )
}
