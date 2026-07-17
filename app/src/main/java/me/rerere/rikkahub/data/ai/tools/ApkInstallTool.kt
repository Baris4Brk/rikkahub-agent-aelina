package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.packageinstaller.ApkInstallController
import me.rerere.rikkahub.data.packageinstaller.ApkInstallResult

fun createInstallApkTool(controller: ApkInstallController): Tool = Tool(
    name = "install_apk",
    description = """
        Validate an existing APK and open Android's system package installer for user confirmation.
        This never performs a silent install. source may be an app-private absolute path,
        ~/ relative to RikkaHub's files directory, an approved content:// URI, or a readable
        APK in the shared RikkaHubExchange directory.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("source", buildJsonObject {
                    put("type", "string")
                    put("description", "APK path or content:// URI")
                })
            },
            required = listOf("source"),
        )
    },
    execute = { input ->
        val source = input.jsonObject["source"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (source.isEmpty() || source.length > 4096 || source.indexOf('\u0000') >= 0) {
            privilegedToolResult(false, "INVALID_SOURCE", "source must contain 1 to 4096 safe characters.")
        } else {
            when (val result = controller.requestInstall(source)) {
                is ApkInstallResult.Launched -> privilegedToolResult(
                    true,
                    "INSTALLER_OPENED",
                    "Android's package installer was opened. The user must confirm installation.",
                    buildJsonObject { result.packageName?.let { put("package_name", it) } },
                )
                is ApkInstallResult.ActionRequired -> privilegedToolResult(
                    false,
                    "ACTION_REQUIRED",
                    result.message,
                )
                is ApkInstallResult.Rejected -> privilegedToolResult(false, result.code, result.message)
            }
        }
    },
)
