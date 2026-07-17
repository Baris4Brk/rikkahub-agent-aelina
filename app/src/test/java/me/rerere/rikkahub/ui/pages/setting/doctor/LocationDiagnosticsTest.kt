package me.rerere.rikkahub.ui.pages.setting.doctor

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationDiagnosticsTest {
    @Test
    fun `coarse only is usable but precise location remains limited`() {
        val states = resolveLocationDiagnosticStates(
            LocationDiagnosticSnapshot(
                fineGranted = false,
                coarseGranted = true,
                locationServicesEnabled = true,
                gpsProviderExists = true,
                gpsProviderEnabled = true,
            )
        )

        assertEquals(LocationDiagnosticState.APPROXIMATE_ONLY, states.permission)
        assertEquals(LocationDiagnosticState.APPROXIMATE_ONLY, states.preciseLocation)
        assertEquals(LocationDiagnosticState.READY, states.locationServices)
        assertEquals(LocationDiagnosticState.READY, states.gnssProvider)
    }

    @Test
    fun `diagnostics distinguish missing disabled unavailable and oem restricted`() {
        val missing = resolveLocationDiagnosticStates(
            LocationDiagnosticSnapshot(false, false, false, false, false)
        )
        assertEquals(LocationDiagnosticState.PERMISSION_MISSING, missing.permission)
        assertEquals(LocationDiagnosticState.LOCATION_DISABLED, missing.locationServices)
        assertEquals(LocationDiagnosticState.PROVIDER_UNAVAILABLE, missing.gnssProvider)

        val disabledGps = resolveLocationDiagnosticStates(
            LocationDiagnosticSnapshot(true, true, true, true, false)
        )
        assertEquals(LocationDiagnosticState.GPS_PROVIDER_DISABLED, disabledGps.gnssProvider)

        val restricted = resolveLocationDiagnosticStates(
            LocationDiagnosticSnapshot(true, true, true, true, true, providerProbeRestricted = true)
        )
        assertEquals(LocationDiagnosticState.OEM_RESTRICTED, restricted.gnssProvider)
    }

    @Test
    fun `location service probe restriction is not reported as disabled`() {
        val states = resolveLocationDiagnosticStates(
            LocationDiagnosticSnapshot(
                fineGranted = true,
                coarseGranted = true,
                locationServicesEnabled = false,
                locationServicesProbeRestricted = true,
                gpsProviderExists = true,
                gpsProviderEnabled = true,
            )
        )

        assertEquals(LocationDiagnosticState.OEM_RESTRICTED, states.locationServices)
        assertEquals(LocationDiagnosticState.READY, states.gnssProvider)
    }
}
