package me.rerere.rikkahub.display

import android.os.Build
import me.rerere.rikkahub.service.RikkaAccessibilityService

data class PrivilegedDisplayInfo(
    val displayId: Int,
    val capabilities: Set<DisplayCapability>,
)

interface PrivilegedDisplayBridgePort {
    suspend fun createManagedDisplay(): Result<PrivilegedDisplayInfo>
    suspend fun closeManagedDisplay(displayId: Int): Result<Unit>
    fun setDisplayLifecycleListener(listener: DisplayProvisionerLifecycleListener?)
}

fun interface DisplayPublicCapabilityProbe {
    fun availableCapabilities(): Set<DisplayCapability>
}

/** Combines independently verified UserService and public Android display capabilities. */
class ShizukuDisplayProvisioner(
    private val bridge: PrivilegedDisplayBridgePort,
    private val publicCapabilityProbe: DisplayPublicCapabilityProbe,
) : DisplayProvisioner {
    @Volatile
    private var lifecycleListener: DisplayProvisionerLifecycleListener? = null

    init {
        bridge.setDisplayLifecycleListener(DisplayProvisionerLifecycleListener { displayId ->
            lifecycleListener?.onProvisionerDied(displayId)
        })
    }

    override suspend fun create(): Result<ProvisionedDisplay> =
        bridge.createManagedDisplay().map { privileged ->
            require(DisplayCapability.CREATE in privileged.capabilities) {
                "display_capability_unavailable"
            }
            ProvisionedDisplay(
                displayId = privileged.displayId,
                capabilities = privileged.capabilities +
                    publicCapabilityProbe.availableCapabilities(),
            )
        }

    override suspend fun close(displayId: Int) {
        bridge.closeManagedDisplay(displayId).getOrThrow()
    }

    override fun setLifecycleListener(listener: DisplayProvisionerLifecycleListener?) {
        lifecycleListener = listener
    }
}

/** Reports only public API paths that are usable in the current app process. */
class AndroidDisplayPublicCapabilityProbe : DisplayPublicCapabilityProbe {
    override fun availableCapabilities(): Set<DisplayCapability> = buildSet {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) add(DisplayCapability.LAUNCH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            RikkaAccessibilityService.instance != null
        ) {
            add(DisplayCapability.TREE)
            add(DisplayCapability.SCREENSHOT)
            add(DisplayCapability.GESTURE)
        }
    }
}
