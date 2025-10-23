package org.cc.tool.utils

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer

val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false // 忽略null值
}

inline fun <reified T> String.parse(): T {
    return json.decodeFromString(this)
}

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> T.toJson(): String {
    return json.encodeToString(json.serializersModule.serializer(), this)
}

inline fun <reified T> JsonElement.parse(): T {
    return json.decodeFromJsonElement(this)
}

inline fun <reified T> T.toJsonElement(): JsonElement {
    return json.encodeToJsonElement(this)
}



