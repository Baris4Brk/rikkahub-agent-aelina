package me.rerere.rikkahub.privilege

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
import me.rerere.rikkahub.data.ai.tools.StartableTool
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.ToolResult
import me.rerere.rikkahub.data.ai.tools.ToolTerminationState
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegeBridgeStatus
import kotlin.time.Duration
import kotlin.uuid.Uuid

val STRUCTURED_PRIVILEGED_TOOL_NAMES: Set<String> = linkedSetOf(
    "privileged_settings_get",
    "privileged_settings_put",
    "privileged_settings_delete",
    "privileged_appop_get",
    "privileged_appop_set",
    "privileged_appop_reset",
    "privileged_permission_status",
    "privileged_permission_grant",
    "privileged_permission_revoke",
    "privileged_package_inspect",
    "privileged_dumpsys",
    "privileged_process_list",
    "privileged_service_status",
)

val STRUCTURED_PRIVILEGED_WRITE_TOOL_NAMES: Set<String> = setOf(
    "privileged_settings_put",
    "privileged_settings_delete",
    "privileged_appop_set",
    "privileged_appop_reset",
    "privileged_permission_grant",
    "privileged_permission_revoke",
)

data class StructuredPrivilegedToolRegistration(
    val definitions: List<Tool>,
    val startables: Map<String, StartableTool>,
)

fun shouldInjectStructuredPrivilegedTools(
    privilege: PrivilegedSessionContext,
    origin: ToolCallOrigin,
    isHeadless: Boolean,
    privilegedBridgeEnabled: Boolean,
    bridgeStatus: ExternalPrivilegeBridgeStatus,
): Boolean = privilege.isPrivileged &&
    InvocationSurfacePolicy.canInjectPrivilegedTools(origin, isHeadless) &&
    privilegedBridgeEnabled &&
    bridgeStatus.binderAvailable &&
    bridgeStatus.permissionGranted &&
    bridgeStatus.userServiceAvailable

fun createStructuredPrivilegedTools(
    executor: StructuredPrivilegedCommandExecutor,
): StructuredPrivilegedToolRegistration = createStructuredToolRegistration(
    specs = structuredToolSpecs(),
    executor = executor,
)

internal fun createStructuredToolRegistration(
    specs: List<StructuredToolSpec>,
    executor: StructuredPrivilegedCommandExecutor,
): StructuredPrivilegedToolRegistration =
    StructuredPrivilegedToolRegistration(
        definitions = specs.map { spec ->
            Tool(
                name = spec.name,
                description = spec.description,
                parameters = {
                    InputSchema.Obj(
                        properties = spec.properties,
                        required = spec.required.toList(),
                    )
                },
                needsApproval = { true },
                execute = { args -> startStructuredTool(spec, args, executor).awaitResult() },
            )
        },
        startables = specs.associate { spec ->
            spec.name to object : StartableTool {
                override suspend fun start(
                    args: JsonElement,
                    context: ToolExecutionContext,
                ): ToolExecutionHandle = startStructuredTool(spec, args, executor)
            }
        },
    )

internal suspend fun startStructuredTool(
    spec: StructuredToolSpec,
    args: JsonElement,
    executor: StructuredPrivilegedCommandExecutor,
): ToolExecutionHandle = runCatching { spec.decode(args.strictObject(spec)) }.fold(
    onSuccess = { executor.start(it) },
    onFailure = { error -> InvalidStructuredToolHandle(invalidStructuredInput(error)) },
)

internal data class StructuredToolSpec(
    val name: String,
    val description: String,
    val properties: JsonObject,
    val required: Set<String>,
    val decode: (JsonObject) -> StructuredPrivilegedOperation,
)

private fun structuredToolSpecs(): List<StructuredToolSpec> = listOf(
    spec(
        name = "privileged_settings_get",
        description = "Read one Android system, secure, or global setting for the current Android user.",
        required = setOf("namespace", "key"),
        properties = settingProperties(),
    ) { obj ->
        StructuredPrivilegedOperation.SettingGet(obj.settingNamespace(), obj.string("key"))
    },
    spec(
        name = "privileged_settings_put",
        description = "Write and verify one non-protected Android setting for the current Android user.",
        required = setOf("namespace", "key", "value"),
        properties = settingProperties(includeValue = true, includeVerify = true),
    ) { obj ->
        StructuredPrivilegedOperation.SettingPut(
            namespace = obj.settingNamespace(),
            key = obj.string("key"),
            value = obj.string("value"),
            verify = obj.boolean("verify", true),
        )
    },
    spec(
        name = "privileged_settings_delete",
        description = "Delete and verify one non-protected Android setting for the current Android user.",
        required = setOf("namespace", "key"),
        properties = settingProperties(includeVerify = true),
    ) { obj ->
        StructuredPrivilegedOperation.SettingDelete(
            namespace = obj.settingNamespace(),
            key = obj.string("key"),
            verify = obj.boolean("verify", true),
        )
    },
    spec(
        name = "privileged_appop_get",
        description = "Read one AppOp mode for an installed package in the current Android user.",
        required = setOf("package_name", "op"),
        properties = packageAndAppOpProperties(),
    ) { obj ->
        StructuredPrivilegedOperation.AppOpGet(obj.string("package_name"), obj.string("op"))
    },
    spec(
        name = "privileged_appop_set",
        description = "Set one AppOp mode and verify the actual mode. Protected apps may only be strengthened with allow.",
        required = setOf("package_name", "op", "mode"),
        properties = packageAndAppOpProperties(includeMode = true, includeVerify = true),
    ) { obj ->
        StructuredPrivilegedOperation.AppOpSet(
            packageName = obj.string("package_name"),
            op = obj.string("op"),
            mode = obj.appOpMode(),
            verify = obj.boolean("verify", true),
        )
    },
    spec(
        name = "privileged_appop_reset",
        description = "Set one AppOp to default and verify it; this never resets every AppOp for a package.",
        required = setOf("package_name", "op"),
        properties = packageAndAppOpProperties(includeVerify = true),
    ) { obj ->
        StructuredPrivilegedOperation.AppOpReset(
            packageName = obj.string("package_name"),
            op = obj.string("op"),
            verify = obj.boolean("verify", true),
        )
    },
    spec(
        name = "privileged_permission_status",
        description = "Inspect whether an installed package declares and currently holds one runtime permission.",
        required = setOf("package_name", "permission"),
        properties = permissionProperties(),
    ) { obj ->
        StructuredPrivilegedOperation.PermissionStatus(
            obj.string("package_name"),
            obj.string("permission"),
        )
    },
    spec(
        name = "privileged_permission_grant",
        description = "Grant and verify one shell-manageable dangerous runtime permission to an installed package.",
        required = setOf("package_name", "permission"),
        properties = permissionProperties(includeVerify = true),
    ) { obj ->
        StructuredPrivilegedOperation.PermissionGrant(
            packageName = obj.string("package_name"),
            permission = obj.string("permission"),
            verify = obj.boolean("verify", true),
        )
    },
    spec(
        name = "privileged_permission_revoke",
        description = "Revoke and verify one shell-manageable dangerous runtime permission from a non-protected package.",
        required = setOf("package_name", "permission"),
        properties = permissionProperties(includeVerify = true),
    ) { obj ->
        StructuredPrivilegedOperation.PermissionRevoke(
            packageName = obj.string("package_name"),
            permission = obj.string("permission"),
            verify = obj.boolean("verify", true),
        )
    },
    spec(
        name = "privileged_package_inspect",
        description = "Inspect bounded package metadata, runtime permissions, install source, and AppOps for the current user.",
        required = setOf("package_name"),
        properties = buildJsonObject { stringProperty("package_name", "Installed Android package name.") },
    ) { obj -> StructuredPrivilegedOperation.PackageInspect(obj.string("package_name")) },
    spec(
        name = "privileged_dumpsys",
        description = "Read bounded output from an allowlisted Android dumpsys service; optional filtering is plain text in the app.",
        required = setOf("service"),
        properties = buildJsonObject {
            put("service", enumStringProperty(StructuredPrivilegedCommandExecutor.DUMPSYS_SERVICES.sorted()))
            stringProperty("filter", "Optional case-sensitive plain-text line filter.")
            integerProperty(
                "max_output_bytes",
                minimum = 1,
                maximum = PrivilegedCommandLimits.MAX_COMBINED_OUTPUT_BYTES,
            )
        },
    ) { obj ->
        StructuredPrivilegedOperation.Dumpsys(
            service = obj.string("service"),
            filter = obj.optionalString("filter").orEmpty(),
            maxOutputBytes = obj.optionalInt("max_output_bytes")
                ?: PrivilegedCommandLimits.DEFAULT_OUTPUT_BYTES,
        )
    },
    spec(
        name = "privileged_process_list",
        description = "List up to 500 processes using structured ps output with a compatible fallback.",
        properties = buildJsonObject { integerProperty("max_processes", 1, 500) },
    ) { obj -> StructuredPrivilegedOperation.ProcessList(obj.optionalInt("max_processes") ?: 500) },
    spec(
        name = "privileged_service_status",
        description = "Report directly observable state for an allowlisted RikkaHub or Android service target.",
        required = setOf("target"),
        properties = buildJsonObject {
            put("target", enumStringProperty(StructuredPrivilegedCommandExecutor.SERVICE_STATUS_TARGETS.sorted()))
            stringProperty("service_name", "Required only when target is android_binder; must be allowlisted.")
        },
    ) { obj ->
        StructuredPrivilegedOperation.ServiceStatus(
            target = obj.string("target"),
            serviceName = obj.optionalString("service_name"),
        )
    },
)

private fun spec(
    name: String,
    description: String,
    properties: JsonObject,
    required: Set<String> = emptySet(),
    decode: (JsonObject) -> StructuredPrivilegedOperation,
) = StructuredToolSpec(name, description, properties, required, decode)

private fun JsonElement.strictObject(spec: StructuredToolSpec): JsonObject {
    val obj = this as? JsonObject ?: throw SerializationException("Tool input must be a JSON object.")
    val unknown = obj.keys - spec.properties.keys
    if (unknown.isNotEmpty()) {
        throw SerializationException("Unknown field(s): ${unknown.sorted().joinToString(", ")}.")
    }
    val missing = spec.required - obj.keys
    if (missing.isNotEmpty()) {
        throw SerializationException("Missing required field(s): ${missing.sorted().joinToString(", ")}.")
    }
    return obj
}

private fun JsonObject.string(name: String): String = optionalString(name)
    ?: throw SerializationException("$name must be a string.")

private fun JsonObject.optionalString(name: String): String? {
    val value = this[name] ?: return null
    val primitive = value as? JsonPrimitive
        ?: throw SerializationException("$name must be a string.")
    if (!primitive.isString) throw SerializationException("$name must be a string.")
    return primitive.contentOrNull ?: throw SerializationException("$name must be a string.")
}

private fun JsonObject.boolean(name: String, default: Boolean): Boolean {
    val value = this[name] ?: return default
    return (value as? JsonPrimitive)?.booleanOrNull
        ?: throw SerializationException("$name must be a boolean.")
}

private fun JsonObject.optionalInt(name: String): Int? {
    val value = this[name] ?: return null
    val primitive = value as? JsonPrimitive
        ?: throw SerializationException("$name must be an integer.")
    if (primitive.isString) throw SerializationException("$name must be an integer.")
    return primitive.intOrNull ?: throw SerializationException("$name must be an integer.")
}

private fun JsonObject.settingNamespace(): StructuredSettingNamespace {
    val value = string("namespace")
    return StructuredSettingNamespace.entries.firstOrNull { it.wire == value }
        ?: throw SerializationException("namespace must be system, secure, or global.")
}

private fun JsonObject.appOpMode(): StructuredAppOpMode {
    val value = string("mode")
    return StructuredAppOpMode.entries.firstOrNull { it.wire == value }
        ?: throw SerializationException("mode is not supported.")
}

private fun settingProperties(
    includeValue: Boolean = false,
    includeVerify: Boolean = false,
) = buildJsonObject {
    put("namespace", enumStringProperty(StructuredSettingNamespace.entries.map { it.wire }))
    stringProperty("key", "Android setting key.")
    if (includeValue) stringProperty("value", "Setting value, at most 4 KiB and without NUL.")
    if (includeVerify) booleanProperty("verify", "Must remain true; writes are always verified.")
}

private fun packageAndAppOpProperties(
    includeMode: Boolean = false,
    includeVerify: Boolean = false,
) = buildJsonObject {
    stringProperty("package_name", "Installed Android package name.")
    stringProperty("op", "Canonical AppOp symbolic name, such as CAMERA or READ_CLIPBOARD.")
    if (includeMode) {
        put("mode", enumStringProperty(StructuredAppOpMode.entries.map { it.wire }))
    }
    if (includeVerify) booleanProperty("verify", "Must remain true; writes are always verified.")
}

private fun permissionProperties(includeVerify: Boolean = false) = buildJsonObject {
    stringProperty("package_name", "Installed Android package name.")
    stringProperty("permission", "Fully qualified dangerous runtime permission name.")
    if (includeVerify) booleanProperty("verify", "Must remain true; writes are always verified.")
}

private fun kotlinx.serialization.json.JsonObjectBuilder.stringProperty(name: String, description: String) {
    put(name, buildJsonObject {
        put("type", "string")
        put("description", description)
    })
}

private fun kotlinx.serialization.json.JsonObjectBuilder.booleanProperty(name: String, description: String) {
    put(name, buildJsonObject {
        put("type", "boolean")
        put("description", description)
        put("default", true)
    })
}

private fun kotlinx.serialization.json.JsonObjectBuilder.integerProperty(
    name: String,
    minimum: Int,
    maximum: Int,
) {
    put(name, buildJsonObject {
        put("type", "integer")
        put("minimum", minimum)
        put("maximum", maximum)
    })
}

private fun enumStringProperty(values: List<String>) = buildJsonObject {
    put("type", "string")
    put("enum", buildJsonArray { values.forEach(::add) })
}

private fun invalidStructuredInput(error: Throwable): ToolResult = listOf(
    UIMessagePart.Text(
        STRUCTURED_TOOL_JSON.encodeToString(
            StructuredPrivilegedResult(
                ok = false,
                code = "INVALID_ARGUMENT",
                message = error.message?.take(300) ?: "Invalid structured privileged tool input.",
                verified = false,
            ),
        ),
    ),
)

private class InvalidStructuredToolHandle(
    private val result: ToolResult,
    override val executionId: String = "structured_invalid_${Uuid.random()}",
) : ToolExecutionHandle {
    override suspend fun awaitResult(): ToolResult = result

    override fun requestCancel(reason: ToolCancelReason): CancelRequestResult = CancelRequestResult.NotFound

    override suspend fun awaitTermination(gracePeriod: Duration): ToolTerminationState =
        ToolTerminationState.StoppedConfirmed
}

private val STRUCTURED_TOOL_JSON = Json {
    encodeDefaults = true
    explicitNulls = false
}
