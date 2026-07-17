package me.rerere.rikkahub.ui.pages.setting.doctor

internal enum class LocationDiagnosticState {
    READY,
    APPROXIMATE_ONLY,
    PERMISSION_MISSING,
    LOCATION_DISABLED,
    GPS_PROVIDER_DISABLED,
    PROVIDER_UNAVAILABLE,
    OEM_RESTRICTED,
}

internal data class LocationDiagnosticSnapshot(
    val fineGranted: Boolean,
    val coarseGranted: Boolean,
    val locationServicesEnabled: Boolean,
    val gpsProviderExists: Boolean,
    val gpsProviderEnabled: Boolean,
    val providerProbeRestricted: Boolean = false,
    val locationServicesProbeRestricted: Boolean = false,
)

internal data class LocationDiagnosticStates(
    val permission: LocationDiagnosticState,
    val preciseLocation: LocationDiagnosticState,
    val locationServices: LocationDiagnosticState,
    val gnssProvider: LocationDiagnosticState,
)

internal fun resolveLocationDiagnosticStates(
    snapshot: LocationDiagnosticSnapshot,
): LocationDiagnosticStates = LocationDiagnosticStates(
    permission = when {
        snapshot.fineGranted -> LocationDiagnosticState.READY
        snapshot.coarseGranted -> LocationDiagnosticState.APPROXIMATE_ONLY
        else -> LocationDiagnosticState.PERMISSION_MISSING
    },
    preciseLocation = when {
        snapshot.fineGranted -> LocationDiagnosticState.READY
        snapshot.coarseGranted -> LocationDiagnosticState.APPROXIMATE_ONLY
        else -> LocationDiagnosticState.PERMISSION_MISSING
    },
    locationServices = when {
        snapshot.locationServicesProbeRestricted -> LocationDiagnosticState.OEM_RESTRICTED
        snapshot.locationServicesEnabled -> LocationDiagnosticState.READY
        else -> LocationDiagnosticState.LOCATION_DISABLED
    },
    gnssProvider = when {
        snapshot.providerProbeRestricted -> LocationDiagnosticState.OEM_RESTRICTED
        !snapshot.gpsProviderExists -> LocationDiagnosticState.PROVIDER_UNAVAILABLE
        !snapshot.gpsProviderEnabled -> LocationDiagnosticState.GPS_PROVIDER_DISABLED
        else -> LocationDiagnosticState.READY
    },
)
