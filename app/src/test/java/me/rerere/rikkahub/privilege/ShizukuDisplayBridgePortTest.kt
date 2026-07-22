package me.rerere.rikkahub.privilege

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.display.DisplayProvisionerLifecycleListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuDisplayBridgePortTest {
    @Test
    fun `invalid display response fails closed instead of yielding a primary display`() = runBlocking {
        val connector = FakeConnector(
            createResponse = """
                {"ok":true,"code":"OK","display_id":0,"capabilities":["create"]}
            """.trimIndent(),
        )
        val port = ShizukuDisplayBridgePort(connector)

        val result = port.createManagedDisplay()

        assertTrue(result.isFailure)
        assertEquals(
            "display_capability_unavailable",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `service death is forwarded as an all-display lifecycle failure`() {
        val connector = FakeConnector()
        val port = ShizukuDisplayBridgePort(connector)
        val deaths = mutableListOf<Int?>()
        port.setDisplayLifecycleListener(DisplayProvisionerLifecycleListener { deaths += it })

        connector.emitDeath()

        assertEquals(listOf<Int?>(null), deaths)
    }

    private class FakeConnector(
        private val createResponse: String =
            """{"ok":true,"code":"OK","display_id":7,"capabilities":["create"]}""",
    ) : ManagedDisplayBridgeConnector {
        private var deathListener: (() -> Unit)? = null

        override suspend fun createManagedDisplayResponse(): Result<String> =
            Result.success(createResponse)

        override suspend fun closeManagedDisplayResponse(displayId: Int): Result<String> =
            Result.success(
                """{"ok":true,"code":"OK","display_id":$displayId,"capabilities":["create"]}""",
            )

        override fun addDisplayBridgeDeathListener(listener: () -> Unit): AutoCloseable {
            deathListener = listener
            return AutoCloseable { deathListener = null }
        }

        fun emitDeath() {
            deathListener?.invoke()
        }
    }
}
