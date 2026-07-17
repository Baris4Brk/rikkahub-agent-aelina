package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyDevicesToolTest {
    private fun execute(tool: Tool, args: String) = runBlocking {
        val part = tool.execute(Json.parseToJsonElement(args)).single() as UIMessagePart.Text
        Json.parseToJsonElement(part.text).jsonObject
    }

    @Test
    fun `bluetooth scan returns discovered devices and clamps duration`() {
        val backend = object : NearbyDevicesBackend {
            var requestedDurationMs: Int? = null

            override suspend fun pairedDevices(): NearbyDevicesResult =
                NearbyDevicesResult.Success(emptyList())

            override suspend fun scan(durationMs: Int): NearbyDevicesResult {
                requestedDurationMs = durationMs
                return NearbyDevicesResult.Success(
                    listOf(
                        NearbyDeviceRecord(
                            name = "Headphones",
                            address = "AA:BB:CC:DD:EE:FF",
                            type = "dual",
                            bondState = "not_bonded",
                            rssi = -42,
                        )
                    )
                )
            }
        }

        val result = execute(scanNearbyBluetoothDevicesTool(backend), """{"duration_ms":99999}""")

        assertEquals(15_000, backend.requestedDurationMs)
        assertEquals(1, result["count"]?.jsonPrimitive?.content?.toInt())
        assertEquals(
            "Headphones",
            result["devices"]?.jsonArray?.single()?.jsonObject?.get("name")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `paired device tool preserves bond metadata and removes duplicate addresses`() {
        val backend = object : NearbyDevicesBackend {
            override suspend fun pairedDevices(): NearbyDevicesResult = NearbyDevicesResult.Success(
                listOf(
                    NearbyDeviceRecord("Watch", "11:22:33:44:55:66", "le", "bonded"),
                    NearbyDeviceRecord("Watch duplicate", "11:22:33:44:55:66", "le", "bonded"),
                )
            )

            override suspend fun scan(durationMs: Int): NearbyDevicesResult =
                NearbyDevicesResult.Success(emptyList())
        }

        val result = execute(listPairedBluetoothDevicesTool(backend), "{}")

        assertEquals(1, result["count"]?.jsonPrimitive?.content?.toInt())
        assertEquals(
            "bonded",
            result["devices"]?.jsonArray?.single()?.jsonObject?.get("bond_state")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `nearby device reads require approval`() {
        assertTrue(ToolApprovalDefaults.requiresApproval("bluetooth_scan"))
        assertTrue(ToolApprovalDefaults.requiresApproval("list_paired_bluetooth_devices"))
    }
}
