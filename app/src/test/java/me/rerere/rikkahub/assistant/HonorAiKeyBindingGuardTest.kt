package me.rerere.rikkahub.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HonorAiKeyBindingGuardTest {
    @Test
    fun `camera bindings are repaired to the unlocked second-user overlay for every gesture`() {
        val cameraBinding = """{"packageName":"com.hihonor.camera","serviceId":"system_camera"}"""

        val repairs = planHonorAiKeyBindingRepairs(
            environment = HonorAiKeyBindingEnvironment(
                isSupportedDevice = true,
                isSystemUser = true,
                mayWriteGlobalSettings = true,
            ),
            currentBindings = HONOR_AI_KEY_SETTING_KEYS.associateWith { cameraBinding },
        )

        assertEquals(HONOR_AI_KEY_SETTING_KEYS.toSet(), repairs.keys)
        repairs.values.forEach { binding ->
            val json = Json.parseToJsonElement(binding).jsonObject
            assertEquals("me.rerere.rikkahub", json.getValue("packageName").jsonPrimitive.content)
            assertEquals(
                "rikka_second_user_overlay",
                json.getValue("serviceId").jsonPrimitive.content,
            )
            assertEquals("0", json.getValue("isSupportScreenLockStart").jsonPrimitive.content)
            assertTrue(json.getValue("lockScreenIntent").jsonPrimitive.content.isEmpty())
            assertTrue(
                json.getValue("commonIntent").jsonPrimitive.content.contains(
                    "me.rerere.rikkahub/.assistant.SystemAssistantOverlayEntryActivity"
                )
            )
        }
    }

    @Test
    fun `unsupported devices secondary users and missing grants cannot repair global settings`() {
        val current = HONOR_AI_KEY_SETTING_KEYS.associateWith { "camera" }
        val blockedEnvironments = listOf(
            HonorAiKeyBindingEnvironment(
                isSupportedDevice = false,
                isSystemUser = true,
                mayWriteGlobalSettings = true,
            ),
            HonorAiKeyBindingEnvironment(
                isSupportedDevice = true,
                isSystemUser = false,
                mayWriteGlobalSettings = true,
            ),
            HonorAiKeyBindingEnvironment(
                isSupportedDevice = true,
                isSystemUser = true,
                mayWriteGlobalSettings = false,
            ),
        )

        blockedEnvironments.forEach { environment ->
            assertTrue(planHonorAiKeyBindingRepairs(environment, current).isEmpty())
        }
    }

    @Test
    fun `running guard restores a gesture that drifts after startup`() {
        val store = FakeHonorAiKeyBindingStore(
            HONOR_AI_KEY_SETTING_KEYS.associateWith { HONOR_AI_KEY_DESIRED_BINDING }
        )
        val guard = HonorAiKeyBindingGuard(
            store = store,
            environment = {
                HonorAiKeyBindingEnvironment(
                    isSupportedDevice = true,
                    isSystemUser = true,
                    mayWriteGlobalSettings = true,
                )
            },
        )

        guard.start()
        store.drift("ai_key_long_service_info", "camera")

        assertEquals(HONOR_AI_KEY_DESIRED_BINDING, store.values["ai_key_long_service_info"])
        assertEquals(listOf("ai_key_long_service_info"), store.writes)
    }

    @Test
    fun `semantically identical reordered JSON does not create a write loop`() {
        val reordered = JsonObject(
            Json.parseToJsonElement(HONOR_AI_KEY_DESIRED_BINDING)
                .jsonObject
                .entries
                .reversed()
                .associate { it.toPair() }
        ).toString()

        val repairs = planHonorAiKeyBindingRepairs(
            environment = HonorAiKeyBindingEnvironment(
                isSupportedDevice = true,
                isSystemUser = true,
                mayWriteGlobalSettings = true,
            ),
            currentBindings = HONOR_AI_KEY_SETTING_KEYS.associateWith { reordered },
        )

        assertTrue(repairs.isEmpty())
    }

    @Test
    fun `start and close are idempotent and closing releases the observer`() {
        val store = FakeHonorAiKeyBindingStore(
            HONOR_AI_KEY_SETTING_KEYS.associateWith { HONOR_AI_KEY_DESIRED_BINDING }
        )
        val guard = HonorAiKeyBindingGuard(
            store = store,
            environment = {
                HonorAiKeyBindingEnvironment(true, true, true)
            },
        )

        guard.start()
        guard.start()
        guard.close()
        guard.close()
        store.drift("ai_key_short_service_info", "camera")

        assertEquals(1, store.observerStarts)
        assertEquals(1, store.observerStops)
        assertTrue(store.writes.isEmpty())
    }

    private class FakeHonorAiKeyBindingStore(
        initial: Map<String, String?>,
    ) : HonorAiKeyBindingStore {
        val values = initial.toMutableMap()
        val writes = mutableListOf<String>()
        var observerStarts = 0
            private set
        var observerStops = 0
            private set
        private var observer: (() -> Unit)? = null

        override fun readBindings(): Map<String, String?> = values.toMap()

        override fun writeBinding(key: String, value: String): Boolean {
            writes += key
            values[key] = value
            return true
        }

        override fun startObserving(onChanged: () -> Unit) {
            observerStarts++
            observer = onChanged
        }

        override fun stopObserving() {
            observerStops++
            observer = null
        }

        fun drift(key: String, value: String) {
            values[key] = value
            observer?.invoke()
        }
    }
}
