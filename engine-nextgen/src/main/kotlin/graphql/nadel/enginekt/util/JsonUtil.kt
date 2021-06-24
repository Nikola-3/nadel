package graphql.nadel.enginekt.util

internal fun AnyMap.asJsonMap(): JsonMap {
    @Suppress("UNCHECKED_CAST")
    return this as JsonMap
}

internal fun AnyMap?.asNullableJsonMap(): JsonMap? {
    @Suppress("UNCHECKED_CAST")
    return this as JsonMap?
}
