package dev.haniya.x.core

import kotlinx.serialization.json.Json

/** Shared lenient JSON instance. */
val AppJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}
