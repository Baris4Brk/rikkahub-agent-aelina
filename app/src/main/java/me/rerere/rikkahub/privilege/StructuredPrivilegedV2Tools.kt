package me.rerere.rikkahub.privilege

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegeBridgeStatus

val STRUCTURED_PRIVILEGED_V2_TOOL_NAMES: Set<String> = linkedSetOf(
    "privileged_package_enable",
    "privileged_package_disable",
    "privileged_package_suspend",
    "privileged_package_unsuspend",
    "privileged_package_uninstall",
    "privileged_resolve_intent",
    "privileged_query_activities",
    "privileged_start_activity",
    "privileged_send_broadcast",
    "privileged_logcat_read",
    "privileged_window_state",
    "privileged_job_status",
    "privileged_alarm_status",
)

val STRUCTURED_PRIVILEGED_V2_WRITE_TOOL_NAMES: Set<String> = setOf(
    "privileged_package_enable",
    "privileged_package_disable",
    "privileged_package_suspend",
    "privileged_package_unsuspend",
    "privileged_package_uninstall",
    "privileged_start_activity",
    "privileged_send_broadcast",
)

fun shouldInjectStructuredPrivilegedV2Tools(
    privilege: PrivilegedSessionContext,
    origin: ToolCallOrigin,
    isHeadless: Boolean,
    privilegedBridgeEnabled: Boolean,
    bridgeStatus: ExternalPrivilegeBridgeStatus,
    deviceLocked: Boolean = false,
): Boolean = shouldInjectStructuredPrivilegedTools(
    privilege = privilege,
    origin = origin,
    isHeadless = isHeadless,
    privilegedBridgeEnabled = privilegedBridgeEnabled,
    bridgeStatus = bridgeStatus,
) && !deviceLocked

fun createStructuredPrivilegedV2Tools(
    executor: StructuredPrivilegedCommandExecutor,
): StructuredPrivilegedToolRegistration = createStructuredToolRegistration(
    specs = structuredPrivilegedV2Specs(),
    executor = executor,
)

private fun structuredPrivilegedV2Specs(): List<StructuredToolSpec> = listOf(
    packageSpec(
        name = "privileged_package_enable",
        description = "Enable and verify one installed package for the current Android user.",
    ) { StructuredPrivilegedOperation.PackageEnable(it) },
    packageSpec(
        name = "privileged_package_disable",
        description = "Disable and verify one non-protected package for the current Android user.",
    ) { StructuredPrivilegedOperation.PackageDisable(it) },
    packageSpec(
        name = "privileged_package_suspend",
        description = "Temporarily suspend and verify one non-protected package for the current Android user.",
    ) { StructuredPrivilegedOperation.PackageSuspend(it) },
    packageSpec(
        name = "privileged_package_unsuspend",
        description = "Unsuspend and verify one installed package for the current Android user.",
    ) { StructuredPrivilegedOperation.PackageUnsuspend(it) },
    packageSpec(
        name = "privileged_package_uninstall",
        description = "Uninstall one non-protected package for the current Android user only and verify removal.",
    ) { StructuredPrivilegedOperation.PackageUninstall(it) },
    intentSpec(
        name = "privileged_resolve_intent",
        description = "Resolve one strongly typed Android intent for the current user without accepting raw shell flags.",
    ) { obj -> StructuredPrivilegedOperation.ResolveIntent(obj.decodeIntent()) },
    intentSpec(
        name = "privileged_query_activities",
        description = "List bounded activities that can handle one strongly typed Android intent for the current user.",
        extraProperties = buildJsonObject {
            integerProperty("max_results", "Maximum activities to return.", 1, 100)
        },
    ) { obj ->
        StructuredPrivilegedOperation.QueryActivities(
            intent = obj.decodeIntent(),
            maxResults = obj.optionalInt("max_results") ?: 100,
        )
    },
    intentSpec(
        name = "privileged_start_activity",
        description = "Start one strongly typed activity as the current Android user; raw command flags are not accepted.",
    ) { obj -> StructuredPrivilegedOperation.StartActivity(obj.decodeIntent()) },
    intentSpec(
        name = "privileged_send_broadcast",
        description = "Send one strongly typed broadcast as the current Android user; an explicit action is required.",
        required = setOf("action"),
    ) { obj -> StructuredPrivilegedOperation.SendBroadcast(obj.decodeIntent()) },
    StructuredToolSpec(
        name = "privileged_logcat_read",
        description = "Read bounded recent logcat output. Filtering is literal text in RikkaHub, not shell syntax.",
        properties = buildJsonObject {
            stringProperty("filter", "Optional case-sensitive literal line filter.")
            integerProperty("max_lines", "Maximum recent log lines to request.", 1, 2_000)
            outputLimitProperty()
        },
        required = emptySet(),
        decode = { obj ->
            StructuredPrivilegedOperation.LogcatRead(
                filter = obj.optionalString("filter").orEmpty(),
                maxLines = obj.optionalInt("max_lines") ?: 200,
                maxOutputBytes = obj.optionalInt("max_output_bytes")
                    ?: PrivilegedCommandLimits.DEFAULT_OUTPUT_BYTES,
            )
        },
    ),
    StructuredToolSpec(
        name = "privileged_window_state",
        description = "Read bounded current-focus and focused-app state from Android's window service.",
        properties = buildJsonObject { outputLimitProperty() },
        required = emptySet(),
        decode = { obj ->
            StructuredPrivilegedOperation.WindowState(
                maxOutputBytes = obj.optionalInt("max_output_bytes")
                    ?: PrivilegedCommandLimits.DEFAULT_OUTPUT_BYTES,
            )
        },
    ),
    diagnosticSpec(
        name = "privileged_job_status",
        description = "Read bounded JobScheduler state, optionally narrowed to an installed package and literal filter.",
    ) { packageName, filter, maxOutputBytes ->
        StructuredPrivilegedOperation.JobStatus(packageName, filter, maxOutputBytes)
    },
    diagnosticSpec(
        name = "privileged_alarm_status",
        description = "Read bounded AlarmManager state, optionally narrowed to an installed package and literal filter.",
    ) { packageName, filter, maxOutputBytes ->
        StructuredPrivilegedOperation.AlarmStatus(packageName, filter, maxOutputBytes)
    },
)

private fun packageSpec(
    name: String,
    description: String,
    decode: (String) -> StructuredPrivilegedOperation,
) = StructuredToolSpec(
    name = name,
    description = description,
    properties = buildJsonObject {
        stringProperty("package_name", "Exact installed Android package name.")
    },
    required = setOf("package_name"),
    decode = { obj -> decode(obj.string("package_name")) },
)

private fun intentSpec(
    name: String,
    description: String,
    extraProperties: JsonObject = buildJsonObject { },
    required: Set<String> = emptySet(),
    decode: (JsonObject) -> StructuredPrivilegedOperation,
) = StructuredToolSpec(
    name = name,
    description = description,
    properties = JsonObject(intentProperties() + extraProperties),
    required = required,
    decode = decode,
)

private fun diagnosticSpec(
    name: String,
    description: String,
    decode: (String?, String, Int) -> StructuredPrivilegedOperation,
) = StructuredToolSpec(
    name = name,
    description = description,
    properties = buildJsonObject {
        stringProperty("package_name", "Optional installed package used as a literal output filter.")
        stringProperty("filter", "Optional additional case-sensitive literal line filter.")
        outputLimitProperty()
    },
    required = emptySet(),
    decode = { obj ->
        decode(
            obj.optionalString("package_name"),
            obj.optionalString("filter").orEmpty(),
            obj.optionalInt("max_output_bytes") ?: PrivilegedCommandLimits.DEFAULT_OUTPUT_BYTES,
        )
    },
)

private fun intentProperties() = buildJsonObject {
    stringProperty("action", "Android intent action, for example android.intent.action.VIEW.")
    stringProperty("component", "Optional explicit component in package/class form.")
    stringProperty("package_name", "Optional exact target package.")
    stringProperty("data_uri", "Optional URI with an explicit scheme.")
    stringProperty("mime_type", "Optional MIME type.")
    put("categories", buildJsonObject {
        put("type", "array")
        put("maxItems", 16)
        put("items", buildJsonObject { put("type", "string") })
    })
    put("extras", buildJsonObject {
        put("type", "object")
        put("maxProperties", 32)
        put("additionalProperties", buildJsonObject {
            put("oneOf", buildJsonArray {
                listOf("string", "boolean", "integer", "number").forEach { type ->
                    add(buildJsonObject { put("type", type) })
                }
            })
        })
    })
}

private fun JsonObject.decodeIntent(): StructuredIntentSpec {
    val categories = when (val element = this["categories"]) {
        null -> emptyList()
        is JsonArray -> element.mapIndexed { index, value ->
            val primitive = value as? JsonPrimitive
                ?: throw SerializationException("categories[$index] must be a string.")
            if (!primitive.isString) throw SerializationException("categories[$index] must be a string.")
            primitive.contentOrNull ?: throw SerializationException("categories[$index] must be a string.")
        }
        else -> throw SerializationException("categories must be an array of strings.")
    }
    val extras = when (val element = this["extras"]) {
        null -> emptyMap()
        is JsonObject -> element.mapValues { (key, value) -> decodeExtra(key, value) }
        else -> throw SerializationException("extras must be an object of primitive values.")
    }
    return StructuredIntentSpec(
        action = optionalString("action"),
        component = optionalString("component"),
        packageName = optionalString("package_name"),
        dataUri = optionalString("data_uri"),
        mimeType = optionalString("mime_type"),
        categories = categories,
        extras = extras,
    )
}

private fun decodeExtra(key: String, value: JsonElement): StructuredIntentExtraValue {
    val primitive = value as? JsonPrimitive
        ?: throw SerializationException("extras.$key must be a string, boolean, integer, or number.")
    if (primitive.isString) {
        return StructuredIntentExtraValue.Text(
            primitive.contentOrNull ?: throw SerializationException("extras.$key must be a string."),
        )
    }
    primitive.booleanOrNull?.let { return StructuredIntentExtraValue.BooleanValue(it) }
    primitive.longOrNull?.let { return StructuredIntentExtraValue.LongValue(it) }
    primitive.doubleOrNull?.let {
        if (!it.isFinite()) throw SerializationException("extras.$key must be finite.")
        return StructuredIntentExtraValue.DoubleValue(it)
    }
    throw SerializationException("extras.$key must be a string, boolean, integer, or number.")
}

private fun JsonObject.string(name: String): String = optionalString(name)
    ?: throw SerializationException("$name must be a string.")

private fun JsonObject.optionalString(name: String): String? {
    val value = this[name] ?: return null
    val primitive = value as? JsonPrimitive ?: throw SerializationException("$name must be a string.")
    if (!primitive.isString) throw SerializationException("$name must be a string.")
    return primitive.contentOrNull ?: throw SerializationException("$name must be a string.")
}

private fun JsonObject.optionalInt(name: String): Int? {
    val value = this[name] ?: return null
    val primitive = value as? JsonPrimitive ?: throw SerializationException("$name must be an integer.")
    if (primitive.isString) throw SerializationException("$name must be an integer.")
    return primitive.intOrNull ?: throw SerializationException("$name must be an integer.")
}

private fun kotlinx.serialization.json.JsonObjectBuilder.stringProperty(name: String, description: String) {
    put(name, buildJsonObject {
        put("type", "string")
        put("description", description)
    })
}

private fun kotlinx.serialization.json.JsonObjectBuilder.integerProperty(
    name: String,
    description: String,
    minimum: Int,
    maximum: Int,
) {
    put(name, buildJsonObject {
        put("type", "integer")
        put("description", description)
        put("minimum", minimum)
        put("maximum", maximum)
    })
}

private fun kotlinx.serialization.json.JsonObjectBuilder.outputLimitProperty() {
    integerProperty(
        name = "max_output_bytes",
        description = "Combined bridge output limit.",
        minimum = 1,
        maximum = PrivilegedCommandLimits.MAX_COMBINED_OUTPUT_BYTES,
    )
}
