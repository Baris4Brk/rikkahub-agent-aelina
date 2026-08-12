package me.rerere.rikkahub.memory.dreaming.runtime

import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamingPreferencesTest {
    @Test
    fun `defaults are conservative and every behavior is off`() {
        val root = DreamingPreferencesV1()

        assertTrue(root.scopes.isEmpty())
        assertEquals(DreamNetworkPolicy.UNMETERED, root.costPolicy.networkPolicy)
        assertTrue(root.costPolicy.requireBatteryNotLow)
        assertTrue(root.costPolicy.requireCharging)
        assertEquals(4, root.costPolicy.dailyRunLimit)
        assertEquals(100_000L, root.costPolicy.dailyInputTokenLimit)
        assertEquals(20_000L, root.costPolicy.dailyOutputTokenLimit)
        assertEquals(2, root.costPolicy.retryLimit)
        assertEquals(15, root.costPolicy.idleThresholdMinutes)
        assertEquals(DreamingScopePreferences(), root.forScope(PRIVATE_SCOPE))
        assertEquals(DreamingScopePreferences(), root.forScope(DreamScopeId.Global))
    }

    @Test
    fun `single root round trips canonical private and global scope settings`() {
        val configured = DreamingPreferencesV1()
            .withScopeMutation(PRIVATE_SCOPE, DreamingScopePreferenceMutation.SetGenerate(true))
            .withScopeMutation(PRIVATE_SCOPE, DreamingScopePreferenceMutation.SetUse(true))
            .withScopeMutation(DreamScopeId.Global, DreamingScopePreferenceMutation.SetShadow(true))

        val decoded = decodeDreamingPreferencesOrDefault(
            encodeDreamingPreferencesFailClosed(configured),
        )

        assertEquals(configured, decoded)
        assertEquals(
            DreamingScopePreferences(generate = true, use = true),
            decoded.forScope(PRIVATE_SCOPE),
        )
        assertEquals(
            DreamingScopePreferences(generate = true, shadow = true),
            decoded.forScope(DreamScopeId.Global),
        )
    }

    @Test
    fun `typed mutations preserve the five legal switch states`() {
        val shadow = DreamingScopePreferences()
            .apply(DreamingScopePreferenceMutation.SetShadow(true))
        assertEquals(DreamingScopePreferences(generate = true, shadow = true), shadow)

        val use = shadow.apply(DreamingScopePreferenceMutation.SetUse(true))
        assertEquals(DreamingScopePreferences(generate = true, use = true), use)

        val useExisting = use
            .apply(DreamingScopePreferenceMutation.SetGenerate(false))
        assertEquals(DreamingScopePreferences(use = true), useExisting)

        val allOff = useExisting.apply(DreamingScopePreferenceMutation.SetUse(false))
        assertEquals(DreamingScopePreferences(), allOff)
    }

    @Test
    fun `all-off scope entries are removed without changing another scope`() {
        val configured = DreamingPreferencesV1()
            .withScopeMutation(PRIVATE_SCOPE, DreamingScopePreferenceMutation.SetGenerate(true))
            .withScopeMutation(DreamScopeId.Global, DreamingScopePreferenceMutation.SetUse(true))
            .withScopeMutation(PRIVATE_SCOPE, DreamingScopePreferenceMutation.SetGenerate(false))

        assertFalse(PRIVATE_SCOPE.value in configured.scopes)
        assertEquals(DreamingScopePreferences(use = true), configured.forScope(DreamScopeId.Global))
    }

    @Test
    fun `malformed partial future unknown and illegal persisted roots fail closed`() {
        val valid = encodeDreamingPreferencesFailClosed(
            DreamingPreferencesV1().withScopeMutation(
                PRIVATE_SCOPE,
                DreamingScopePreferenceMutation.SetGenerate(true),
            ),
        )
        val invalidPayloads = listOf(
            "not-json",
            "{}",
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            valid.replaceFirst("{", "{\"unknown\":true,"),
            valid.replace("UNMETERED", "WIFI_ONLY"),
            valid.replace(PRIVATE_SCOPE.value, "PRIVATE"),
            valid.replace("\"generate\":true,\"shadow\":false,\"use\":false", "\"generate\":false,\"shadow\":true,\"use\":false"),
            valid.replace("\"dailyRunLimit\":4", "\"dailyRunLimit\":65"),
        )

        invalidPayloads.forEach { raw ->
            assertEquals("payload=$raw", DreamingPreferencesV1(), decodeDreamingPreferencesOrDefault(raw))
        }
    }

    @Test
    fun `null token caps are explicit while negative values remain invalid`() {
        val uncapped = DreamingCostPolicy(
            dailyInputTokenLimit = null,
            dailyOutputTokenLimit = null,
        )
        assertEquals(uncapped, uncapped.validatedOrNull())
        assertNull(DreamingCostPolicy(dailyInputTokenLimit = -1).validatedOrNull())
        assertNull(DreamingCostPolicy(dailyOutputTokenLimit = -1).validatedOrNull())
        assertNull(DreamingCostPolicy(idleThresholdMinutes = 4).validatedOrNull())
    }

    private companion object {
        val PRIVATE_SCOPE = DreamScopeId.requireCanonical(
            "10000000-0000-4000-8000-000000000005",
        )
    }
}
