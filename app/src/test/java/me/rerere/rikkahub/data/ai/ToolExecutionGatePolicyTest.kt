package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.capability.CapabilityId
import me.rerere.rikkahub.data.capability.CapabilityCatalog
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import me.rerere.rikkahub.data.ai.tools.SelfPreservationPolicy
import me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_TOOL_NAMES
import me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_WRITE_TOOL_NAMES
import me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_V2_TOOL_NAMES
import me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_V2_WRITE_TOOL_NAMES
import me.rerere.rikkahub.data.ai.tools.local.VERIFIED_ACCESSIBILITY_TOOL_NAMES
import me.rerere.rikkahub.data.ai.tools.local.VERIFIED_ACCESSIBILITY_WRITE_TOOL_NAMES
import me.rerere.rikkahub.assistant.SecondUserAdmissionSnapshot
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.capability.CapabilitySubject
import me.rerere.rikkahub.data.capability.SubjectType
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionGatePolicyTest {
    @Test
    fun `self preservation blocks structured and file mutations before unrestricted`() {
        val policy = SelfPreservationPolicy.forApplication("me.rerere.rikkahub.test")

        assertTrue(
            selfPreservationBlockReason(
                "privileged_package_uninstall",
                buildJsonObject { put("package_name", "me.rerere.rikkahub.test") },
                policy,
            ) != null,
        )
        assertTrue(
            selfPreservationBlockReason(
                "batch_delete",
                buildJsonObject {
                    put("paths", buildJsonArray {
                        add("/workspace/safe.txt")
                        add("/data/user/0/me.rerere.rikkahub.test/shared_prefs/settings.xml")
                    })
                },
                policy,
            ) != null,
        )
        assertTrue(
            selfPreservationBlockReason(
                "delete_file",
                buildJsonObject {
                    put("path", "/data/user/0/me.rerere.rikkahub.test/databases/rikkahub.db")
                },
                policy,
            ) != null,
        )
        assertEquals(
            null,
            selfPreservationBlockReason(
                "privileged_package_uninstall",
                buildJsonObject { put("package_name", "com.example.other") },
                policy,
            ),
        )
    }

    @Test
    fun `unrestricted does not bypass either external privilege capability`() {
        assertFalse(unrestrictedMayBypassCapability(CapabilityId.ExternalPrivilegeBridge))
        assertFalse(unrestrictedMayBypassCapability(CapabilityId.PrivilegedShell))
        assertFalse(unrestrictedMayBypassCapability(CapabilityId.StructuredPrivilegedSystemTools))
        assertFalse(unrestrictedMayBypassCapability(CapabilityId.StructuredPrivilegedSystemToolsV2))
        assertFalse(unrestrictedMayBypassCapability(CapabilityId.VerifiedAccessibility))
    }

    @Test
    fun `unrestricted keeps legacy bypass for ordinary capabilities`() {
        assertTrue(unrestrictedMayBypassCapability(CapabilityId.PhoneActions))
        assertTrue(unrestrictedMayBypassCapability(null))
    }

    @Test
    fun `structured capability requires a hard unlock`() {
        assertTrue(capabilityRequiresHardUnlock(CapabilityId.StructuredPrivilegedSystemTools))
        assertTrue(capabilityRequiresHardUnlock(CapabilityId.StructuredPrivilegedSystemToolsV2))
        assertTrue(capabilityRequiresHardUnlock(CapabilityId.VerifiedAccessibility))
        assertFalse(capabilityRequiresHardUnlock(CapabilityId.PrivilegedShell))
        assertFalse(capabilityRequiresHardUnlock(null))
    }

    @Test
    fun `foreground-only capability is denied when app is backgrounded`() {
        assertEquals(
            "get_gnss_status requires RikkaHub to be in the foreground.",
            foregroundRequirementBlockReason(
                toolName = "get_gnss_status",
                requiresForegroundApp = true,
                appInForeground = false,
            ),
        )
        assertEquals(
            null,
            foregroundRequirementBlockReason(
                toolName = "get_gnss_status",
                requiresForegroundApp = true,
                appInForeground = true,
            ),
        )
    }

    @Test
    fun `structured privileged tools retain defensive approval origin and risk sets`() {
        assertTrue(ToolApprovalDefaults.ALWAYS_ASK.containsAll(STRUCTURED_PRIVILEGED_TOOL_NAMES))
        assertTrue(ToolApprovalDefaults.NO_ALWAYS_ALLOW.containsAll(STRUCTURED_PRIVILEGED_TOOL_NAMES))
        assertTrue(ToolExecutionGate.NEVER_REMOTE.containsAll(STRUCTURED_PRIVILEGED_TOOL_NAMES))
        assertTrue(ToolExecutionGate.BLOCKED_WHILE_LOCKED.containsAll(STRUCTURED_PRIVILEGED_TOOL_NAMES))
        assertEquals(
            STRUCTURED_PRIVILEGED_WRITE_TOOL_NAMES,
            ToolExecutionGate.HIGH_RISK_TOOLS.intersect(STRUCTURED_PRIVILEGED_TOOL_NAMES),
        )
    }

    @Test
    fun `v2 and verified accessibility tools retain defensive policy sets`() {
        val all = STRUCTURED_PRIVILEGED_V2_TOOL_NAMES + VERIFIED_ACCESSIBILITY_TOOL_NAMES
        assertTrue(ToolApprovalDefaults.ALWAYS_ASK.containsAll(all))
        assertTrue(ToolApprovalDefaults.NO_ALWAYS_ALLOW.containsAll(all))
        assertTrue(ToolExecutionGate.NEVER_REMOTE.containsAll(all))
        assertTrue(ToolExecutionGate.BLOCKED_WHILE_LOCKED.containsAll(all))
        assertEquals(
            STRUCTURED_PRIVILEGED_V2_WRITE_TOOL_NAMES,
            ToolExecutionGate.HIGH_RISK_TOOLS.intersect(STRUCTURED_PRIVILEGED_V2_TOOL_NAMES),
        )
        assertEquals(
            VERIFIED_ACCESSIBILITY_WRITE_TOOL_NAMES,
            ToolExecutionGate.HIGH_RISK_TOOLS.intersect(VERIFIED_ACCESSIBILITY_TOOL_NAMES),
        )
    }

    @Test
    fun `catalog origin is a hard gate even for unrestricted assistants`() {
        val localOnly = CapabilityCatalog.capabilityOf(CapabilityId.GnssDiagnostics)!!

        assertTrue(
            catalogOriginHardBlockReason(
                toolName = "get_gnss_status",
                origin = ToolCallOrigin.SystemAssistant,
                capability = localOnly,
            ) != null,
        )
        assertFalse(
            canApplyUnrestrictedOverride(
                "get_gnss_status",
                origin = ToolCallOrigin.SystemAssistant,
                capability = localOnly,
            ),
        )
        assertEquals(
            null,
            catalogOriginHardBlockReason(
                toolName = "get_gnss_status",
                origin = ToolCallOrigin.LocalChat,
                capability = localOnly,
            ),
        )
        assertTrue(
            canApplyUnrestrictedOverride(
                "get_gnss_status",
                origin = ToolCallOrigin.LocalChat,
                capability = localOnly,
            ),
        )
    }

    @Test
    fun `active unlocked second user can use high risk autonomy only in its bound conversation`() {
        val assistantId = Uuid.random()
        val conversationId = Uuid.random()
        val authority = SecondUserAdmissionSnapshot.create(
            assistantId = assistantId,
            conversationId = conversationId,
            authorityEpoch = 7,
            origin = ToolCallOrigin.LocalChat,
        )
        SecondUserAuthorityRegistry.install(authority)
        try {
            val subject = CapabilitySubject(
                id = authority.subjectId,
                type = SubjectType.LOCAL_SECOND_USER,
                privilegedConversationId = conversationId.toString(),
            )
            assertTrue(
                canUseSecondUserHighRiskAutonomy(
                    subject = subject,
                    conversationId = conversationId,
                    origin = ToolCallOrigin.LocalChat,
                    deviceLocked = false,
                ),
            )
            assertFalse(
                canUseSecondUserHighRiskAutonomy(
                    subject = subject,
                    conversationId = Uuid.random(),
                    origin = ToolCallOrigin.LocalChat,
                    deviceLocked = false,
                ),
            )
            assertFalse(
                canUseSecondUserHighRiskAutonomy(
                    subject = subject,
                    conversationId = conversationId,
                    origin = ToolCallOrigin.Telegram,
                    deviceLocked = false,
                ),
            )
            assertFalse(
                canUseSecondUserHighRiskAutonomy(
                    subject = subject,
                    conversationId = conversationId,
                    origin = ToolCallOrigin.LocalChat,
                    deviceLocked = true,
                ),
            )
        } finally {
            SecondUserAuthorityRegistry.install(null)
        }
    }

    @Test
    fun `uncatalogued tools fail closed on the system assistant surface`() {
        assertTrue(
            catalogOriginHardBlockReason(
                toolName = "uncatalogued_tool",
                origin = ToolCallOrigin.SystemAssistant,
                capability = null,
            ) != null,
        )
        assertFalse(
            canApplyUnrestrictedOverride(
                "uncatalogued_tool",
                origin = ToolCallOrigin.SystemAssistant,
                capability = null,
            ),
        )
    }

    @Test
    fun `unrestricted cannot promote Activity tools on the system assistant surface`() {
        val share = CapabilityCatalog.capabilityOf(CapabilityId.Share)!!

        assertTrue(
            catalogOriginHardBlockReason(
                toolName = "share",
                origin = ToolCallOrigin.SystemAssistant,
                capability = share,
            ) != null,
        )
        assertFalse(
            canApplyUnrestrictedOverride(
                "share",
                origin = ToolCallOrigin.SystemAssistant,
                capability = share,
            ),
        )
    }

    @Test
    fun `system assistant unrestricted cannot bypass bounded surface classification`() {
        setOf(
            "external_bridge_run_command",
            "privileged_start_activity",
            "privileged_settings_put",
            "termux_run_command",
            "workspace_process_start",
            "write_binary_file",
        ).forEach { toolName ->
            val capability = CapabilityCatalog.byToolName(toolName)
            assertTrue("$toolName must be catalogued", capability != null)
            assertTrue(
                "$toolName must be hard-blocked before unrestricted approval",
                catalogOriginHardBlockReason(
                    toolName = toolName,
                    origin = ToolCallOrigin.SystemAssistant,
                    capability = capability,
                ) != null,
            )
            assertFalse(
                canApplyUnrestrictedOverride(
                    toolName = toolName,
                    origin = ToolCallOrigin.SystemAssistant,
                    capability = capability,
                ),
            )
        }
    }

    @Test
    fun `system assistant cannot read core second user files into model context`() {
        val corePath =
            "/data/user/0/${BuildConfig.APPLICATION_ID}/databases/rikka_hub"
        val arguments = buildJsonObject { put("path", corePath) }

        assertTrue(
            systemAssistantSensitivePathBlockReason(
                toolName = "read_file",
                origin = ToolCallOrigin.SystemAssistant,
                arguments = arguments,
            ) != null,
        )
        assertEquals(
            null,
            systemAssistantSensitivePathBlockReason(
                toolName = "read_file",
                origin = ToolCallOrigin.SystemAssistant,
                arguments = buildJsonObject { put("path", "/sdcard/Download/note.txt") },
            ),
        )
        assertEquals(
            null,
            systemAssistantSensitivePathBlockReason(
                toolName = "read_file",
                origin = ToolCallOrigin.LocalChat,
                arguments = arguments,
            ),
        )
    }

    @Test
    fun `system assistant checks the real root argument used by find files`() {
        val coreRoot =
            "/data/user/0/${BuildConfig.APPLICATION_ID}/datastore"

        assertTrue(
            systemAssistantSensitivePathBlockReason(
                toolName = "find_files",
                origin = ToolCallOrigin.SystemAssistant,
                arguments = buildJsonObject { put("root", coreRoot) },
            ) != null,
        )
    }

    @Test
    fun `system assistant resolves own file provider uri before sensitive read check`() {
        listOf(
            "content://${BuildConfig.APPLICATION_ID}.fileprovider/upload/datastore/settings.preferences_pb",
            "content://0@${BuildConfig.APPLICATION_ID}.fileprovider/upload/upload/managed.txt",
        ).forEach { protectedUri ->
            assertTrue(
                systemAssistantSensitivePathBlockReason(
                    toolName = "read_file",
                    origin = ToolCallOrigin.SystemAssistant,
                    arguments = buildJsonObject { put("path", protectedUri) },
                ) != null,
            )
        }
        assertEquals(
            null,
            systemAssistantSensitivePathBlockReason(
                toolName = "read_file",
                origin = ToolCallOrigin.SystemAssistant,
                arguments = buildJsonObject {
                    put("path", "content://com.android.providers.downloads.documents/document/42")
                },
            ),
        )
    }
}
