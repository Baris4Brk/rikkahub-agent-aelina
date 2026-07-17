package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePermissionRequirementTest {
    private val locationPermissions = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @Test
    fun `location any policy accepts coarse only`() {
        assertTrue(
            runtimePermissionRequirementSatisfied(
                required = locationPermissions,
                policy = RuntimePermissionPolicy.ANY,
                isGranted = { it == Manifest.permission.ACCESS_COARSE_LOCATION },
            )
        )
    }

    @Test
    fun `default all policy does not relax wifi style requirements`() {
        assertFalse(
            runtimePermissionRequirementSatisfied(
                required = locationPermissions,
                policy = RuntimePermissionPolicy.ALL,
                isGranted = { it == Manifest.permission.ACCESS_COARSE_LOCATION },
            )
        )
    }
}
