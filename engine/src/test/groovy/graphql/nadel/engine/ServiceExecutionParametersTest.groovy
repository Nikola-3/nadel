package graphql.nadel.engine

import graphql.cachecontrol.CacheControl
import graphql.execution.ExecutionId
import graphql.language.Document
import graphql.language.OperationDefinition
import graphql.nadel.ServiceExecutionHydrationDetails
import graphql.nadel.ServiceExecutionParameters
import graphql.nadel.engine.testutils.TestUtil
import spock.lang.Specification

class ServiceExecutionParametersTest extends Specification {

    def "builder works as expected"() {
        given:
        Document document = TestUtil.parseQuery("query { foo }")
        def opDef = document.getChildren()[0] as OperationDefinition
        def context = [some: "Context"]
        def variables = [variables: "okPresent"]
        def executionId = ExecutionId.generate()
        def cacheControl = CacheControl.newCacheControl()
        def hydrationDetails = new ServiceExecutionHydrationDetails(10,50)

        when:
        def parameters = ServiceExecutionParameters.newServiceExecutionParameters()
                .query(document)
                .context(context)
                .operationDefinition(opDef)
                .variables(variables)
                .executionId(executionId)
                .cacheControl(cacheControl)
                .executionHydrationDetails(hydrationDetails)
                .build()

        then:
        parameters.query == document
        parameters.context == context
        parameters.variables == variables
        parameters.executionId == executionId
        parameters.cacheControl == cacheControl
        parameters.hydrationCall
        parameters.hydrationDetails.getTimeout() == 10
        parameters.hydrationDetails.getBatchSize() == 50
    }
}
