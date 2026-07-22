package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.accessibility.AndroidVerifiedAccessibilityDriver
import me.rerere.rikkahub.accessibility.DEFAULT_MAX_SCROLLS
import me.rerere.rikkahub.accessibility.DEFAULT_UI_WAIT_TIMEOUT_MS
import me.rerere.rikkahub.accessibility.DefaultVerifiedAccessibilityController
import me.rerere.rikkahub.accessibility.UiExpectation
import me.rerere.rikkahub.accessibility.UiNodeSelector
import me.rerere.rikkahub.accessibility.UiScrollDirection
import me.rerere.rikkahub.accessibility.VerifiedAccessibilityController
import me.rerere.rikkahub.accessibility.VerifiedUiResult
import me.rerere.rikkahub.data.ai.AgentTurnTracker
import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.privilege.PrivilegedSessionContext

private const val MAX_SELECTOR_VALUE_LENGTH = 512

val VERIFIED_ACCESSIBILITY_TOOL_NAMES: Set<String> = linkedSetOf(
    "ui_wait_for_window",
    "ui_wait_for_node",
    "ui_click_node_verified",
    "ui_set_text_verified",
    "ui_scroll_until",
)

val VERIFIED_ACCESSIBILITY_WRITE_TOOL_NAMES: Set<String> = setOf(
    "ui_click_node_verified",
    "ui_set_text_verified",
    "ui_scroll_until",
)

fun shouldInjectVerifiedAccessibilityTools(
    privilege: PrivilegedSessionContext,
    origin: ToolCallOrigin,
    isHeadless: Boolean,
): Boolean = privilege.isPrivileged &&
    InvocationSurfacePolicy.canInjectPrivilegedTools(origin, isHeadless)

fun uiWaitForWindowTool(
    controller: VerifiedAccessibilityController = defaultVerifiedAccessibilityController(),
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
    displayTargetResolver: DisplayTargetResolver? = null,
    controllerForDisplay: (Int) -> VerifiedAccessibilityController = ::defaultVerifiedAccessibilityControllerForDisplay,
): Tool = Tool(
    name = "ui_wait_for_window",
    description = "Wait up to 30 seconds for an accessibility window matching a package and/or title. Stops immediately on protected password, biometric, payment, transfer, factory-reset, or device-admin surfaces.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package_name", stringSchema("Exact foreground package name"))
                put("title_contains", stringSchema("Case-insensitive window title fragment"))
                put("timeout_ms", integerSchema("Wait timeout; default 10000, maximum 30000"))
                put(DisplayTargetResolver.DISPLAY_SESSION_ID, displaySessionIdSchema())
            },
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        obj.rejectUnknown(setOf("package_name", "title_contains", "timeout_ms", DisplayTargetResolver.DISPLAY_SESSION_ID))?.let {
            return@Tool textResult(it)
        }
        obj.rejectInvalidTypes(
            strings = setOf("package_name", "title_contains", DisplayTargetResolver.DISPLAY_SESSION_ID),
            longs = setOf("timeout_ms"),
        )
            ?.let { return@Tool textResult(it) }
        val packageName = obj.string("package_name")
        val title = obj.string("title_contains")
        val timeout = obj.long("timeout_ms") ?: DEFAULT_UI_WAIT_TIMEOUT_MS
        val targetController = resolveDisplayScopedController(
            input = input,
            invocationContext = invocationContext,
            displayTargetResolver = displayTargetResolver,
            primaryController = controller,
            controllerForDisplay = controllerForDisplay,
        ).getOrElse { return@Tool textResult(displayTargetResult(it)) }
        val result = targetController.waitForWindow(UiExpectation.WindowMatches(packageName, title), timeout)
        streamer.streamIfHeadless(invocationContext, "WaitForWindow")
        textResult(result)
    },
)

fun uiWaitForNodeTool(
    controller: VerifiedAccessibilityController = defaultVerifiedAccessibilityController(),
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
    displayTargetResolver: DisplayTargetResolver? = null,
    controllerForDisplay: (Int) -> VerifiedAccessibilityController = ::defaultVerifiedAccessibilityControllerForDisplay,
): Tool = Tool(
    name = "ui_wait_for_node",
    description = "Wait for a node to appear or disappear. Selectors may use view_id, exact text, content_description, class_name, and an optional ancestor selector. Every check reads a fresh accessibility tree.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("selector", selectorSchema(includeAncestor = true))
                put("present", booleanSchema("true to wait for presence; false for absence (default true)"))
                put("timeout_ms", integerSchema("Wait timeout; default 10000, maximum 30000"))
                put(DisplayTargetResolver.DISPLAY_SESSION_ID, displaySessionIdSchema())
            },
            required = listOf("selector"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        obj.rejectUnknown(setOf("selector", "present", "timeout_ms", DisplayTargetResolver.DISPLAY_SESSION_ID))?.let { return@Tool textResult(it) }
        obj.rejectInvalidTypes(
            strings = setOf(DisplayTargetResolver.DISPLAY_SESSION_ID),
            booleans = setOf("present"),
            longs = setOf("timeout_ms"),
        )
            ?.let { return@Tool textResult(it) }
        val selector = parseSelector(obj["selector"])
            ?: return@Tool textResult(invalidResult("selector is invalid"))
        val present = obj.boolean("present") ?: true
        val timeout = obj.long("timeout_ms") ?: DEFAULT_UI_WAIT_TIMEOUT_MS
        val targetController = resolveDisplayScopedController(
            input = input,
            invocationContext = invocationContext,
            displayTargetResolver = displayTargetResolver,
            primaryController = controller,
            controllerForDisplay = controllerForDisplay,
        ).getOrElse { return@Tool textResult(displayTargetResult(it)) }
        val result = targetController.waitForNode(selector, present, timeout)
        streamer.streamIfHeadless(invocationContext, "WaitForNode")
        textResult(result)
    },
)

fun uiClickNodeVerifiedTool(
    controller: VerifiedAccessibilityController = defaultVerifiedAccessibilityController(),
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
    displayTargetResolver: DisplayTargetResolver? = null,
    controllerForDisplay: (Int) -> VerifiedAccessibilityController = ::defaultVerifiedAccessibilityControllerForDisplay,
): Tool = Tool(
    name = "ui_click_node_verified",
    description = "Resolve a node from a fresh tree, click it, then verify a window/content change or an explicit node/window condition. Re-resolves stale targets up to two times, but never repeats a click Android already accepted.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("selector", selectorSchema(includeAncestor = true))
                put("nth", integerSchema("Zero-based match index; default 0"))
                put("expectation", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("window_changed"); add("window_matches"); add("node_present"); add("node_absent")
                    })
                    put("description", "Post-click verification; default window_changed")
                })
                put("expected_selector", selectorSchema(includeAncestor = true))
                put("expected_package_name", stringSchema("Package for window_matches"))
                put("expected_title_contains", stringSchema("Title fragment for window_matches"))
                put("timeout_ms", integerSchema("Whole action timeout; default 10000, maximum 30000"))
                put(DisplayTargetResolver.DISPLAY_SESSION_ID, displaySessionIdSchema())
            },
            required = listOf("selector"),
        )
    },
    execute = { input ->
        AgentTurnTracker.recordAutomationAction()
        val obj = input.jsonObject
        obj.rejectUnknown(
            setOf(
                "selector", "nth", "expectation", "expected_selector",
                "expected_package_name", "expected_title_contains", "timeout_ms",
                DisplayTargetResolver.DISPLAY_SESSION_ID,
            )
        )?.let { return@Tool textResult(it) }
        obj.rejectInvalidTypes(
            strings = setOf(
                "expectation", "expected_package_name", "expected_title_contains",
                DisplayTargetResolver.DISPLAY_SESSION_ID,
            ),
            ints = setOf("nth"),
            longs = setOf("timeout_ms"),
        )?.let { return@Tool textResult(it) }
        val selector = parseSelector(obj["selector"])
            ?: return@Tool textResult(invalidResult("selector is invalid"))
        val expectation = parseExpectation(obj)
            ?: return@Tool textResult(invalidResult("expectation arguments are incomplete or invalid"))
        val nth = obj.int("nth") ?: 0
        val timeout = obj.long("timeout_ms") ?: DEFAULT_UI_WAIT_TIMEOUT_MS
        val targetController = resolveDisplayScopedController(
            input = input,
            invocationContext = invocationContext,
            displayTargetResolver = displayTargetResolver,
            primaryController = controller,
            controllerForDisplay = controllerForDisplay,
        ).getOrElse { return@Tool textResult(displayTargetResult(it)) }
        val result = targetController.clickNodeVerified(selector, nth, expectation, timeout)
        streamer.streamIfHeadless(invocationContext, "ClickNodeVerified")
        textResult(result)
    },
)

fun uiSetTextVerifiedTool(
    controller: VerifiedAccessibilityController = defaultVerifiedAccessibilityController(),
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
    displayTargetResolver: DisplayTargetResolver? = null,
    controllerForDisplay: (Int) -> VerifiedAccessibilityController = ::defaultVerifiedAccessibilityControllerForDisplay,
): Tool = Tool(
    name = "ui_set_text_verified",
    description = "Set text on a freshly resolved editable node and re-read the tree until the value matches. Verification codes may be filled, but this tool does not confirm or submit them. Text is not echoed in the result or action summary.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("selector", selectorSchema(includeAncestor = true))
                put("text", stringSchema("Text to set; never logged by this tool"))
                put("nth", integerSchema("Zero-based match index; default 0"))
                put("timeout_ms", integerSchema("Whole action timeout; default 10000, maximum 30000"))
                put(DisplayTargetResolver.DISPLAY_SESSION_ID, displaySessionIdSchema())
            },
            required = listOf("selector", "text"),
        )
    },
    execute = { input ->
        AgentTurnTracker.recordAutomationAction()
        val obj = input.jsonObject
        obj.rejectUnknown(setOf("selector", "text", "nth", "timeout_ms", DisplayTargetResolver.DISPLAY_SESSION_ID))?.let {
            return@Tool textResult(it)
        }
        obj.rejectInvalidTypes(
            strings = setOf("text", DisplayTargetResolver.DISPLAY_SESSION_ID),
            ints = setOf("nth"),
            longs = setOf("timeout_ms"),
        )
            ?.let { return@Tool textResult(it) }
        val selector = parseSelector(obj["selector"])
            ?: return@Tool textResult(invalidResult("selector is invalid"))
        val text = obj.string("text")
            ?: return@Tool textResult(invalidResult("text is required"))
        if (text.length > MAX_TEXT_LENGTH || text.indexOf('\u0000') >= 0) {
            return@Tool textResult(invalidResult("text must be at most $MAX_TEXT_LENGTH characters and contain no NUL"))
        }
        val nth = obj.int("nth") ?: 0
        val timeout = obj.long("timeout_ms") ?: DEFAULT_UI_WAIT_TIMEOUT_MS
        val targetController = resolveDisplayScopedController(
            input = input,
            invocationContext = invocationContext,
            displayTargetResolver = displayTargetResolver,
            primaryController = controller,
            controllerForDisplay = controllerForDisplay,
        ).getOrElse { return@Tool textResult(displayTargetResult(it)) }
        val result = targetController.setTextVerified(selector, text, nth, timeout)
        streamer.streamIfHeadless(invocationContext, "SetTextVerified")
        textResult(result)
    },
)

fun uiScrollUntilTool(
    controller: VerifiedAccessibilityController = defaultVerifiedAccessibilityController(),
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
    displayTargetResolver: DisplayTargetResolver? = null,
    controllerForDisplay: (Int) -> VerifiedAccessibilityController = ::defaultVerifiedAccessibilityControllerForDisplay,
): Tool = Tool(
    name = "ui_scroll_until",
    description = "Scroll a freshly resolved container until a target node appears. Defaults to 8 scrolls (maximum 20), re-reading the complete accessibility snapshot after every action.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("selector", selectorSchema(includeAncestor = true))
                put("direction", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("up"); add("down"); add("left"); add("right") })
                })
                put("container_selector", selectorSchema(includeAncestor = true))
                put("max_scrolls", integerSchema("Default 8, maximum 20"))
                put("timeout_ms", integerSchema("Whole action timeout; default 10000, maximum 30000"))
                put(DisplayTargetResolver.DISPLAY_SESSION_ID, displaySessionIdSchema())
            },
            required = listOf("selector", "direction"),
        )
    },
    execute = { input ->
        AgentTurnTracker.recordAutomationAction()
        val obj = input.jsonObject
        obj.rejectUnknown(
            setOf(
                "selector", "direction", "container_selector", "max_scrolls", "timeout_ms",
                DisplayTargetResolver.DISPLAY_SESSION_ID,
            )
        )?.let {
            return@Tool textResult(it)
        }
        obj.rejectInvalidTypes(
            strings = setOf("direction", DisplayTargetResolver.DISPLAY_SESSION_ID),
            ints = setOf("max_scrolls"),
            longs = setOf("timeout_ms"),
        )
            ?.let { return@Tool textResult(it) }
        val selector = parseSelector(obj["selector"])
            ?: return@Tool textResult(invalidResult("selector is invalid"))
        val container = obj["container_selector"]?.let(::parseSelector)
        if (obj.containsKey("container_selector") && container == null) {
            return@Tool textResult(invalidResult("container_selector is invalid"))
        }
        val direction = when (obj.string("direction")) {
            "up" -> UiScrollDirection.UP
            "down" -> UiScrollDirection.DOWN
            "left" -> UiScrollDirection.LEFT
            "right" -> UiScrollDirection.RIGHT
            else -> return@Tool textResult(invalidResult("direction must be up, down, left, or right"))
        }
        val maxScrolls = obj.int("max_scrolls") ?: DEFAULT_MAX_SCROLLS
        val timeout = obj.long("timeout_ms") ?: DEFAULT_UI_WAIT_TIMEOUT_MS
        val targetController = resolveDisplayScopedController(
            input = input,
            invocationContext = invocationContext,
            displayTargetResolver = displayTargetResolver,
            primaryController = controller,
            controllerForDisplay = controllerForDisplay,
        ).getOrElse { return@Tool textResult(displayTargetResult(it)) }
        val result = targetController.scrollUntil(selector, direction, container, maxScrolls, timeout)
        streamer.streamIfHeadless(invocationContext, "ScrollUntil")
        textResult(result)
    },
)

/** Convenience entry point for the privileged-tool injector. */
fun verifiedAccessibilityTools(
    controller: VerifiedAccessibilityController = defaultVerifiedAccessibilityController(),
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
    displayTargetResolver: DisplayTargetResolver? = null,
    controllerForDisplay: (Int) -> VerifiedAccessibilityController = ::defaultVerifiedAccessibilityControllerForDisplay,
): List<Tool> = listOf(
    uiWaitForWindowTool(controller, invocationContext, streamer, displayTargetResolver, controllerForDisplay),
    uiWaitForNodeTool(controller, invocationContext, streamer, displayTargetResolver, controllerForDisplay),
    uiClickNodeVerifiedTool(controller, invocationContext, streamer, displayTargetResolver, controllerForDisplay),
    uiSetTextVerifiedTool(controller, invocationContext, streamer, displayTargetResolver, controllerForDisplay),
    uiScrollUntilTool(controller, invocationContext, streamer, displayTargetResolver, controllerForDisplay),
)

private fun defaultVerifiedAccessibilityController(): VerifiedAccessibilityController =
    DefaultVerifiedAccessibilityController(AndroidVerifiedAccessibilityDriver())

private fun defaultVerifiedAccessibilityControllerForDisplay(displayId: Int): VerifiedAccessibilityController =
    DefaultVerifiedAccessibilityController(AndroidVerifiedAccessibilityDriver(displayId))

private suspend fun resolveDisplayScopedController(
    input: JsonElement,
    invocationContext: ToolInvocationContext,
    displayTargetResolver: DisplayTargetResolver?,
    primaryController: VerifiedAccessibilityController,
    controllerForDisplay: (Int) -> VerifiedAccessibilityController,
): Result<VerifiedAccessibilityController> {
    val obj = input.jsonObject
    val rawSessionId = obj[DisplayTargetResolver.DISPLAY_SESSION_ID]
        ?.let { value -> (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull }
    if (!obj.containsKey(DisplayTargetResolver.DISPLAY_SESSION_ID)) return Result.success(primaryController)
    if (rawSessionId.isNullOrBlank()) return Result.failure(
        IllegalArgumentException("display_session_id_required")
    )
    return when (
        val resolution = resolveDisplayTargetOrPrimary(
            resolver = displayTargetResolver,
            input = input,
            invocationContext = invocationContext,
            requiredCapability = me.rerere.rikkahub.display.DisplayCapability.TREE,
        )
    ) {
        is DisplayTargetResolution.Resolved -> {
            if (resolution.target.isPrimary) {
                Result.failure(IllegalStateException("display_primary_forbidden"))
            } else {
                Result.success(controllerForDisplay(resolution.target.displayId))
            }
        }
        is DisplayTargetResolution.Error -> Result.failure(IllegalStateException(resolution.code))
    }
}

private fun displayTargetResult(error: Throwable): VerifiedUiResult = VerifiedUiResult(
    ok = false,
    code = error.message?.takeIf { it.matches(Regex("[a-z0-9_]{3,80}")) }
        ?: "display_resolution_failed",
    message = "The requested managed display cannot be used for this action.",
    step = me.rerere.rikkahub.accessibility.VerifiedUiStep.VALIDATE,
)

private fun selectorSchema(includeAncestor: Boolean): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("properties", buildJsonObject {
        put("view_id", stringSchema("Exact Android view ID"))
        put("text", stringSchema("Exact visible text"))
        put("content_description", stringSchema("Exact accessibility description"))
        put("class_name", stringSchema("Exact accessibility class name"))
        if (includeAncestor) put("ancestor", selectorSchema(includeAncestor = false))
    })
}

private fun parseSelector(element: JsonElement?): UiNodeSelector? = parseSelector(element, allowAncestor = true)

private fun parseSelector(element: JsonElement?, allowAncestor: Boolean): UiNodeSelector? {
    val obj = element as? JsonObject ?: return null
    val allowedFields = if (allowAncestor) SELECTOR_FIELDS else SELECTOR_FIELDS - "ancestor"
    if (obj.keys.any { it !in allowedFields }) return null
    val ancestor = obj["ancestor"]?.let { parseSelector(it, allowAncestor = false) }
    if (obj.containsKey("ancestor") && ancestor == null) return null
    val viewId = obj.string("view_id")?.validatedSelectorValue()
    val text = obj.string("text")?.validatedSelectorValue()
    val contentDescription = obj.string("content_description")?.validatedSelectorValue()
    val className = obj.string("class_name")?.validatedSelectorValue()
    if (obj.containsKey("view_id") && viewId == null) return null
    if (obj.containsKey("text") && text == null) return null
    if (obj.containsKey("content_description") && contentDescription == null) return null
    if (obj.containsKey("class_name") && className == null) return null
    val selector = UiNodeSelector(
        viewId = viewId,
        text = text,
        contentDescription = contentDescription,
        className = className,
        ancestor = ancestor,
    )
    return selector.takeIf(UiNodeSelector::isValid)
}

private fun String.validatedSelectorValue(): String? =
    takeIf { it.isNotBlank() && length <= MAX_SELECTOR_VALUE_LENGTH && indexOf('\u0000') < 0 }

private fun parseExpectation(obj: JsonObject): UiExpectation? {
    return when (obj.string("expectation") ?: "window_changed") {
        "window_changed" -> UiExpectation.WindowChanged
        "window_matches" -> UiExpectation.WindowMatches(
            packageName = obj.string("expected_package_name"),
            titleContains = obj.string("expected_title_contains"),
        ).takeIf { !it.packageName.isNullOrBlank() || !it.titleContains.isNullOrBlank() }
        "node_present", "node_absent" -> {
            val selector = parseSelector(obj["expected_selector"]) ?: return null
            UiExpectation.NodePresence(selector, obj.string("expectation") == "node_present")
        }
        else -> null
    }
}

private fun JsonObject.rejectUnknown(allowed: Set<String>): VerifiedUiResult? =
    keys.firstOrNull { it !in allowed }?.let { invalidResult("unknown field: $it") }

private fun JsonObject.rejectInvalidTypes(
    strings: Set<String> = emptySet(),
    ints: Set<String> = emptySet(),
    longs: Set<String> = emptySet(),
    booleans: Set<String> = emptySet(),
): VerifiedUiResult? {
    strings.firstOrNull { containsKey(it) && string(it) == null }?.let {
        return invalidResult("$it must be a string")
    }
    ints.firstOrNull { containsKey(it) && int(it) == null }?.let {
        return invalidResult("$it must be an integer")
    }
    longs.firstOrNull { containsKey(it) && long(it) == null }?.let {
        return invalidResult("$it must be an integer")
    }
    booleans.firstOrNull { containsKey(it) && boolean(it) == null }?.let {
        return invalidResult("$it must be a boolean")
    }
    return null
}

private fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

private fun JsonObject.long(key: String): Long? =
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

private fun JsonObject.boolean(key: String): Boolean? =
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

private fun invalidResult(message: String) = VerifiedUiResult(
    ok = false,
    code = "INVALID_ARGUMENT",
    message = message,
    step = me.rerere.rikkahub.accessibility.VerifiedUiStep.VALIDATE,
)

private fun textResult(result: VerifiedUiResult): List<UIMessagePart> =
    listOf(UIMessagePart.Text(result.toSafeJson().toString()))

private fun VerifiedUiResult.toSafeJson(): JsonObject = buildJsonObject {
    put("ok", ok)
    put("code", code)
    put("message", message)
    put("step", step.name.lowercase())
    put("attempts", attempts)
    put("scrolls", scrolls)
    window?.let { current ->
        put("window", buildJsonObject {
            put("package_name", current.packageName)
            current.title?.let { put("title", it.take(256)) }
            put("version", current.version)
        })
    }
    node?.let { current ->
        put("node", buildJsonObject {
            current.viewId?.let { put("view_id", it) }
            current.className?.let { put("class_name", it) }
            put("clickable", current.clickable)
            put("editable", current.editable)
            put("scrollable", current.scrollable)
        })
    }
}

private fun stringSchema(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun integerSchema(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

private fun booleanSchema(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

private val SELECTOR_FIELDS = setOf("view_id", "text", "content_description", "class_name", "ancestor")
private const val MAX_TEXT_LENGTH = 32 * 1024
