package me.rerere.rikkahub.display

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuDisplayProvisionerTest {
    @Test
    fun `capabilities are the union of verified bridge and public adapters`() = runBlocking {
        val port = FakeDisplayBridgePort(
            PrivilegedDisplayInfo(
                displayId = 12,
                capabilities = setOf(DisplayCapability.CREATE, DisplayCapability.KEY),
            )
        )
        val provisioner = ShizukuDisplayProvisioner(
            bridge = port,
            publicCapabilityProbe = DisplayPublicCapabilityProbe {
                setOf(
                    DisplayCapability.LAUNCH,
                    DisplayCapability.TREE,
                    DisplayCapability.SCREENSHOT,
                    DisplayCapability.GESTURE,
                )
            },
        )

        val display = provisioner.create().getOrThrow()

        assertEquals(12, display.displayId)
        assertEquals(DisplayCapability.entries.toSet(), display.capabilities)
    }

    @Test
    fun `binder death is forwarded without fabricating a primary display`() {
        val port = FakeDisplayBridgePort(
            PrivilegedDisplayInfo(14, setOf(DisplayCapability.CREATE))
        )
        val provisioner = ShizukuDisplayProvisioner(
            bridge = port,
            publicCapabilityProbe = DisplayPublicCapabilityProbe { emptySet() },
        )
        val deaths = mutableListOf<Int?>()
        provisioner.setLifecycleListener(DisplayProvisionerLifecycleListener { displayId ->
            deaths.add(displayId)
        })

        port.listener?.onProvisionerDied(null)

        assertEquals(listOf<Int?>(null), deaths)
        assertTrue(deaths.none { it == 0 })
    }

    @Test
    fun `close routes only the exact managed display id`() = runBlocking {
        val port = FakeDisplayBridgePort(
            PrivilegedDisplayInfo(15, setOf(DisplayCapability.CREATE))
        )
        val provisioner = ShizukuDisplayProvisioner(
            bridge = port,
            publicCapabilityProbe = DisplayPublicCapabilityProbe { emptySet() },
        )

        provisioner.close(15)

        assertEquals(listOf(15), port.closed)
    }

    private class FakeDisplayBridgePort(
        private val info: PrivilegedDisplayInfo,
    ) : PrivilegedDisplayBridgePort {
        var listener: DisplayProvisionerLifecycleListener? = null
        val closed = mutableListOf<Int>()

        override suspend fun createManagedDisplay(): Result<PrivilegedDisplayInfo> =
            Result.success(info)

        override suspend fun closeManagedDisplay(displayId: Int): Result<Unit> {
            closed += displayId
            return Result.success(Unit)
        }

        override fun setDisplayLifecycleListener(listener: DisplayProvisionerLifecycleListener?) {
            this.listener = listener
        }
    }
}
