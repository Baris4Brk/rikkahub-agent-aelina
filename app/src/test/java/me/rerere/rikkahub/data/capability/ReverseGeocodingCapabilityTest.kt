package me.rerere.rikkahub.data.capability

import android.Manifest
import kotlin.uuid.Uuid
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.execution.DefaultToolExecutionPolicyResolver
import me.rerere.rikkahub.data.ai.execution.ToolConcurrency
import me.rerere.rikkahub.data.ai.execution.ToolEffect
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseGeocodingCapabilityTest {
    private val context = ToolExecutionContext(
        runId = Uuid.random(),
        conversationId = Uuid.random(),
        assistantId = "assistant",
        callOrigin = ToolCallOrigin.LocalChat,
    )
    private val policyResolver = DefaultToolExecutionPolicyResolver()

    @Test
    fun `supplied-coordinate reverse geocoding has no Android location permission`() {
        val descriptor = CapabilityCatalog.capabilityOf(CapabilityId.ReverseGeocoding)
        val runtimePermissions = descriptor?.requirements
            ?.filterIsInstance<CapabilityRequirement.RuntimePermission>()
            ?.map { it.permission }
            .orEmpty()

        assertEquals(setOf("reverse_geocode"), descriptor?.toolNames)
        assertEquals(null, descriptor?.localToolOption)
        assertTrue(runtimePermissions.isEmpty())
        assertFalse(runtimePermissions.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertEquals(InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER, descriptor?.allowedOrigins)
        assertTrue(descriptor?.requiresUnlockedDevice == true)
        assertFalse(descriptor?.requiresForegroundApp == true)
        assertEquals(CapabilityId.ReverseGeocoding, CapabilityCatalog.byToolName("reverse_geocode")?.id)
        assertEquals(ToolInvocationSurface.Background, CapabilityCatalog.toolInvocationSurface("reverse_geocode"))
    }

    @Test
    fun `reverse tool uses existing approval layer without forbidding trusted grants`() {
        assertTrue(ToolApprovalDefaults.requiresApproval("reverse_geocode"))
        assertTrue(ToolApprovalDefaults.allowsAlwaysAllow("reverse_geocode"))
    }

    @Test
    fun `plain location remains a sensitive resource-serial read`() {
        val policy = policyResolver.resolve("get_location", buildJsonObject {}, context)

        assertEquals(setOf(ToolEffect.SENSITIVE_READ), policy.effects)
        assertEquals(ToolConcurrency.RESOURCE_SERIAL, policy.concurrency)
        assertTrue(policy.resourceKeys.any { it.namespace == "location" })
    }

    @Test
    fun `address-enabled location is classified as possible coordinate egress`() {
        val policy = policyResolver.resolve(
            "get_location",
            buildJsonObject { put("include_address", true) },
            context,
        )

        assertEquals(setOf(ToolEffect.SENSITIVE_READ, ToolEffect.NETWORK_WRITE), policy.effects)
        assertTrue(policy.resourceKeys.any { it.namespace == "reverse-geocoder" })
    }

    @Test
    fun `reverse policy follows actual platform and external flags`() {
        val normal = policyResolver.resolve("reverse_geocode", buildJsonObject {}, context)
        val cacheOnly = policyResolver.resolve(
            "reverse_geocode",
            buildJsonObject {
                put("allow_platform_geocoder", false)
                put("allow_external", false)
            },
            context,
        )

        assertEquals(setOf(ToolEffect.SENSITIVE_READ, ToolEffect.NETWORK_WRITE), normal.effects)
        assertEquals(setOf(ToolEffect.SENSITIVE_READ), cacheOnly.effects)
        assertEquals(ToolConcurrency.RESOURCE_SERIAL, normal.concurrency)
        assertTrue(normal.resourceKeys.single().namespace == "reverse-geocoder")
    }
}
