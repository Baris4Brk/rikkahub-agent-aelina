package me.rerere.rikkahub.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SystemAssistantManifestTest {
    @Test
    fun `voice service remains assist eligible but cannot launch from keyguard`() {
        val voiceConfig = parseXml(File("src/main/res/xml/voice_interaction_service.xml"))
            .documentElement

        assertEquals("true", voiceConfig.androidSupportsAssist)
        assertEquals("false", voiceConfig.androidSupportsLaunchFromKeyguard)
    }

    @Test
    fun `voice assistant uses an explicit non discoverable recognition service`() {
        val manifest = parseXml(File("src/main/AndroidManifest.xml"))
        val voiceConfig = parseXml(File("src/main/res/xml/voice_interaction_service.xml"))

        val voiceService = manifest
            .getElementsByTagName("service")
            .asElements()
            .singleOrNull { it.androidName == VOICE_SERVICE }
        assertNotNull("VoiceInteractionService must remain declared", voiceService)
        assertTrue(
            "VoiceInteractionService must retain its android.voice_interaction metadata",
            voiceService!!.getElementsByTagName("meta-data").asElements().any {
                it.androidName == "android.voice_interaction" &&
                    it.androidResource == "@xml/voice_interaction_service"
            },
        )

        assertEquals(RECOGNITION_SERVICE, voiceConfig.documentElement.androidRecognitionService)

        val recognitionService = manifest
            .getElementsByTagName("service")
            .asElements()
            .singleOrNull { it.androidName == MANIFEST_RECOGNITION_SERVICE }
        assertNotNull("The explicitly referenced RecognitionService must remain declared", recognitionService)
        assertEquals(
            "android.permission.BIND_SPEECH_RECOGNITION",
            recognitionService!!.androidPermission,
        )
        assertFalse(
            "The no-op service must not be discoverable as a default voice-input provider",
            recognitionService.hasAction("android.speech.RecognitionService"),
        )
    }

    @Test
    fun `test invocation receiver is private and stays in the lightweight process`() {
        val manifest = parseXml(File("src/main/AndroidManifest.xml"))
        val receiver = manifest
            .getElementsByTagName("receiver")
            .asElements()
            .singleOrNull { it.androidName == TEST_INVOCATION_RECEIVER }

        assertNotNull("The active voice service needs a private test request receiver", receiver)
        assertEquals("false", receiver!!.androidExported)
        assertEquals(":voice_interactor", receiver.androidProcess)
        assertTrue(receiver.hasAction(TEST_INVOCATION_ACTION))
        assertTrue(receiver.hasAction(ACCESSIBILITY_INVOCATION_ACTION))
    }

    @Test
    fun `assistant button service requests only the standard navigation button`() {
        val manifest = parseXml(File("src/main/AndroidManifest.xml"))
        val buttonService = manifest
            .getElementsByTagName("service")
            .asElements()
            .singleOrNull { it.androidName == ACCESSIBILITY_BUTTON_SERVICE }
        assertNotNull("The assistant needs a dedicated accessibility-button service", buttonService)
        assertTrue(
            buttonService!!.getElementsByTagName("meta-data").asElements().any {
                it.androidName == "android.accessibilityservice" &&
                    it.androidResource == "@xml/system_assistant_accessibility_button_service"
            },
        )
        assertEquals(
            "The shortcut-only accessibility service must not start the full app graph",
            ":voice_interactor",
            buttonService.androidProcess,
        )
        assertEquals("@string/system_assistant_shortcut_title", buttonService.androidLabel)
        assertEquals("@string/system_assistant_shortcut_desc", buttonService.androidDescription)

        val accessibilityConfig = parseXml(
            File("src/main/res/xml/system_assistant_accessibility_button_service.xml")
        ).documentElement
        val flags = accessibilityConfig
            .getAttribute("android:accessibilityFlags")
            .split('|')

        assertTrue(flags.contains("flagRequestAccessibilityButton"))
        assertEquals("false", accessibilityConfig.getAttribute("android:canRetrieveWindowContent"))
        assertEquals("false", accessibilityConfig.getAttribute("android:canPerformGestures"))
        assertEquals("false", accessibilityConfig.getAttribute("android:canTakeScreenshot"))
    }

    @Test
    fun `hardware key overlay entry is lightweight explicit and absent from history`() {
        val manifest = parseXml(File("src/main/AndroidManifest.xml"))
        val entry = manifest
            .getElementsByTagName("activity")
            .asElements()
            .singleOrNull { it.androidName == HARDWARE_OVERLAY_ENTRY_ACTIVITY }

        assertNotNull("The Honor hardware key needs a dedicated overlay entry", entry)
        assertEquals("true", entry!!.androidExported)
        assertEquals(":voice_interactor", entry.androidProcess)
        assertEquals("true", entry.androidNoHistory)
        assertEquals("true", entry.androidExcludeFromRecents)
        assertTrue(
            "The AI-key entry must explicitly opt out of the main RikkaHub task affinity",
            entry.hasAttribute("android:taskAffinity"),
        )
        assertEquals("", entry.androidTaskAffinity)
        assertTrue(entry.hasAction(HARDWARE_OVERLAY_ACTION))

        val overlay = manifest
            .getElementsByTagName("activity")
            .asElements()
            .singleOrNull { it.androidName == HARDWARE_OVERLAY_ACTIVITY }
        assertNotNull("The Honor hardware key needs a main-process surface fallback", overlay)
        assertEquals("false", overlay!!.androidExported)
        assertEquals("", overlay.androidProcess)
        assertEquals("true", overlay.androidNoHistory)
        assertEquals("true", overlay.androidExcludeFromRecents)
        assertEquals("singleTask", overlay.androidLaunchMode)
        assertTrue(
            "The translucent surface must not resurrect the existing RikkaHub task",
            overlay.hasAttribute("android:taskAffinity"),
        )
        assertEquals("", overlay.androidTaskAffinity)
    }

    @Test
    fun `honor ai key drift guard declares the protected global-settings grant`() {
        val manifest = parseXml(File("src/main/AndroidManifest.xml"))
        val requestedPermissions = manifest
            .getElementsByTagName("uses-permission")
            .asElements()
            .map { it.androidName }

        assertTrue(requestedPermissions.contains("android.permission.WRITE_SECURE_SETTINGS"))
    }

    @Test
    fun `static shortcuts keep the second user assistant entry`() {
        val shortcuts = parseXml(File("src/main/res/xml/shortcuts.xml"))
        val shortcut = shortcuts
            .getElementsByTagName("shortcut")
            .asElements()
            .singleOrNull { it.androidShortcutId == "second_user_assistant" }

        assertNotNull("The launcher must keep the second-user assistant shortcut", shortcut)
        assertEquals("true", shortcut!!.androidEnabled)

        val intent = shortcut
            .getElementsByTagName("intent")
            .asElements()
            .single()
        assertEquals(SECOND_USER_ASSISTANT_ACTION, intent.androidAction)
        assertEquals(SECOND_USER_ASSISTANT_ACTIVITY, intent.androidTargetClass)
    }

    private fun parseXml(file: File) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(file)

    private fun Element.hasAction(name: String): Boolean =
        getElementsByTagName("action").asElements().any { it.androidName == name }

    private val Element.androidName: String
        get() = getAttribute("android:name")

    private val Element.androidPermission: String
        get() = getAttribute("android:permission")

    private val Element.androidExported: String
        get() = getAttribute("android:exported")

    private val Element.androidProcess: String
        get() = getAttribute("android:process")

    private val Element.androidNoHistory: String
        get() = getAttribute("android:noHistory")

    private val Element.androidExcludeFromRecents: String
        get() = getAttribute("android:excludeFromRecents")

    private val Element.androidTaskAffinity: String
        get() = getAttribute("android:taskAffinity")

    private val Element.androidLaunchMode: String
        get() = getAttribute("android:launchMode")

    private val Element.androidLabel: String
        get() = getAttribute("android:label")

    private val Element.androidDescription: String
        get() = getAttribute("android:description")

    private val Element.androidShortcutId: String
        get() = getAttribute("android:shortcutId")

    private val Element.androidEnabled: String
        get() = getAttribute("android:enabled")

    private val Element.androidAction: String
        get() = getAttribute("android:action")

    private val Element.androidTargetClass: String
        get() = getAttribute("android:targetClass")

    private val Element.androidResource: String
        get() = getAttribute("android:resource")

    private val Element.androidRecognitionService: String
        get() = getAttribute("android:recognitionService")

    private val Element.androidSupportsAssist: String
        get() = getAttribute("android:supportsAssist")

    private val Element.androidSupportsLaunchFromKeyguard: String
        get() = getAttribute("android:supportsLaunchVoiceAssistFromKeyguard")

    private fun org.w3c.dom.NodeList.asElements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }

    private companion object {
        const val VOICE_SERVICE = ".assistant.RikkaVoiceInteractionService"
        const val MANIFEST_RECOGNITION_SERVICE = ".assistant.RikkaNoOpRecognitionService"
        const val TEST_INVOCATION_RECEIVER =
            ".assistant.SystemAssistantInvocationReceiver"
        const val ACCESSIBILITY_BUTTON_SERVICE =
            ".assistant.SystemAssistantAccessibilityButtonService"
        const val HARDWARE_OVERLAY_ENTRY_ACTIVITY =
            ".assistant.SystemAssistantOverlayEntryActivity"
        const val HARDWARE_OVERLAY_ACTIVITY =
            ".assistant.SystemAssistantHardwareOverlayActivity"
        const val TEST_INVOCATION_ACTION =
            "me.rerere.rikkahub.action.SHOW_SYSTEM_ASSISTANT_TEST"
        const val ACCESSIBILITY_INVOCATION_ACTION =
            "me.rerere.rikkahub.action.SHOW_SYSTEM_ASSISTANT_ACCESSIBILITY"
        const val HARDWARE_OVERLAY_ACTION =
            "me.rerere.rikkahub.action.SHOW_SYSTEM_ASSISTANT_HARDWARE"
        const val SECOND_USER_ASSISTANT_ACTION =
            "me.rerere.rikkahub.action.OPEN_SECOND_USER_ASSISTANT"
        const val SECOND_USER_ASSISTANT_ACTIVITY =
            "me.rerere.rikkahub.assistant.SystemAssistantFallbackActivity"
        const val RECOGNITION_SERVICE =
            "me.rerere.rikkahub.assistant.RikkaNoOpRecognitionService"
    }
}
