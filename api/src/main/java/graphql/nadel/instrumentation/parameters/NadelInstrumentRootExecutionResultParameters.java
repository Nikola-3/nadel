package graphql.nadel.instrumentation.parameters;

import graphql.PublicApi;
import graphql.execution.ExecutionContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.nadel.normalized.NormalizedQueryFromAst;

/**
 * Parameters sent to {@link graphql.nadel.instrumentation.NadelInstrumentation} methods
 */
@PublicApi
public class NadelInstrumentRootExecutionResultParameters {

    private final ExecutionContext executionContext;
    private final InstrumentationState instrumentationState;
    private final NormalizedQueryFromAst normalizedQueryFromAst;

    public NadelInstrumentRootExecutionResultParameters(ExecutionContext executionContext, NormalizedQueryFromAst normalizedQueryFromAst, InstrumentationState instrumentationState) {
        this.executionContext = executionContext;
        this.instrumentationState = instrumentationState;
        this.normalizedQueryFromAst = normalizedQueryFromAst;
    }

    /**
     * Returns a cloned parameters object with the new state
     *
     * @param instrumentationState the new state for this parameters object
     * @return a new parameters object with the new state
     */
    public NadelInstrumentRootExecutionResultParameters withNewState(InstrumentationState instrumentationState) {
        return new NadelInstrumentRootExecutionResultParameters(executionContext, normalizedQueryFromAst, instrumentationState);
    }

    public ExecutionContext getExecutionContext() {
        return executionContext;
    }

    public <T extends InstrumentationState> T getInstrumentationState() {
        // noinspection unchecked
        return (T) instrumentationState;
    }

}
