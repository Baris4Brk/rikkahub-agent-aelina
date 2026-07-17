package me.rerere.rikkahub.ui.pages.setting

import android.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class LocationPermissionRequestTest {
    @Test
    fun `fine and coarse rows request the foreground location pair`() {
        val expected = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        assertArrayEquals(expected, runtimePermissionsForGrant(Manifest.permission.ACCESS_FINE_LOCATION))
        assertArrayEquals(expected, runtimePermissionsForGrant(Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @Test
    fun `ordinary runtime row requests only its own permission`() {
        assertArrayEquals(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            runtimePermissionsForGrant(Manifest.permission.RECORD_AUDIO),
        )
    }
}
