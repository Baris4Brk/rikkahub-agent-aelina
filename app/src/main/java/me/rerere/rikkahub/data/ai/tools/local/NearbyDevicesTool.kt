package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

internal data class NearbyDeviceRecord(
    val name: String,
    val address: String,
    val type: String,
    val bondState: String,
    val rssi: Int? = null,
)

internal sealed interface NearbyDevicesResult {
    data class Success(val devices: List<NearbyDeviceRecord>) : NearbyDevicesResult
    data class Error(val code: String, val message: String) : NearbyDevicesResult
}

/** Seam around Android's Bluetooth stack; tests exercise the Tool interface with a fake adapter. */
internal interface NearbyDevicesBackend {
    suspend fun pairedDevices(): NearbyDevicesResult
    suspend fun scan(durationMs: Int): NearbyDevicesResult
}

private class AndroidNearbyDevicesBackend(private val context: Context) : NearbyDevicesBackend {
    private val adapter
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    override suspend fun pairedDevices(): NearbyDevicesResult {
        if (!hasConnectPermission()) {
            return NearbyDevicesResult.Error(
                "NO_PERMISSION",
                "BLUETOOTH_CONNECT permission is required to read paired devices.",
            )
        }
        val bluetoothAdapter = adapter
            ?: return NearbyDevicesResult.Error("NO_BLUETOOTH", "This device does not support Bluetooth.")
        if (!bluetoothAdapter.isEnabled) {
            return NearbyDevicesResult.Error("BLUETOOTH_DISABLED", "Bluetooth is turned off.")
        }
        return NearbyDevicesResult.Success(
            bluetoothAdapter.bondedDevices.orEmpty()
                .map(::toRecord)
                .sortedBy { it.name.lowercase() }
        )
    }

    override suspend fun scan(durationMs: Int): NearbyDevicesResult {
        if (!hasScanPermission() || !hasConnectPermission()) {
            return NearbyDevicesResult.Error(
                "NO_PERMISSION",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "BLUETOOTH_SCAN and BLUETOOTH_CONNECT permissions are required."
                } else {
                    "Location permission is required for Bluetooth discovery on this Android version."
                },
            )
        }
        val bluetoothAdapter = adapter
            ?: return NearbyDevicesResult.Error("NO_BLUETOOTH", "This device does not support Bluetooth.")
        if (!bluetoothAdapter.isEnabled) {
            return NearbyDevicesResult.Error("BLUETOOTH_DISABLED", "Bluetooth is turned off.")
        }

        val found = linkedMapOf<String, NearbyDeviceRecord>()
        val finished = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        } ?: return
                        val rssi = if (intent.hasExtra(BluetoothDevice.EXTRA_RSSI)) {
                            intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                                .takeUnless { it == Short.MIN_VALUE.toInt() }
                        } else {
                            null
                        }
                        val record = toRecord(device, rssi)
                        synchronized(found) { found[record.address] = record }
                    }
                    android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> finished.complete(Unit)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }

        return try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            bluetoothAdapter.cancelDiscovery()
            if (!bluetoothAdapter.startDiscovery()) {
                return NearbyDevicesResult.Error("SCAN_START_FAILED", "Android refused to start Bluetooth discovery.")
            }
            withTimeoutOrNull(durationMs.toLong()) { finished.await() }
            NearbyDevicesResult.Success(
                synchronized(found) { found.values.sortedByDescending { it.rssi ?: Int.MIN_VALUE } }
            )
        } catch (security: SecurityException) {
            NearbyDevicesResult.Error("NO_PERMISSION", security.message ?: "Bluetooth permission denied.")
        } finally {
            runCatching { bluetoothAdapter.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun toRecord(device: BluetoothDevice, rssi: Int? = null) = NearbyDeviceRecord(
        name = device.name ?: "Unknown",
        address = device.address,
        type = when (device.type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
            BluetoothDevice.DEVICE_TYPE_LE -> "le"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
            else -> "unknown"
        },
        bondState = when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> "bonded"
            BluetoothDevice.BOND_BONDING -> "bonding"
            else -> "not_bonded"
        },
        rssi = rssi,
    )
}

private fun nearbyResultPart(result: NearbyDevicesResult): List<UIMessagePart> =
    listOf(UIMessagePart.Text(when (result) {
        is NearbyDevicesResult.Error -> buildJsonObject {
            put("error", result.code)
            put("message", result.message)
        }
        is NearbyDevicesResult.Success -> buildJsonObject {
            val unique = result.devices.distinctBy { it.address }
            put("count", unique.size)
            put("devices", buildJsonArray {
                unique.forEach { device ->
                    add(buildJsonObject {
                        put("name", device.name)
                        put("address", device.address)
                        put("type", device.type)
                        put("bond_state", device.bondState)
                        device.rssi?.let { put("rssi_dbm", it) }
                    })
                }
            })
        }
    }.toString()))

internal fun listPairedBluetoothDevicesTool(backend: NearbyDevicesBackend): Tool = Tool(
    name = "list_paired_bluetooth_devices",
    description = "List paired Bluetooth devices. Returns device name and MAC address.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        nearbyResultPart(backend.pairedDevices())
    }
)

fun listPairedBluetoothDevicesTool(context: Context): Tool =
    listPairedBluetoothDevicesTool(AndroidNearbyDevicesBackend(context))

internal fun scanNearbyBluetoothDevicesTool(backend: NearbyDevicesBackend): Tool = Tool(
    name = "bluetooth_scan",
    description = "Scan for nearby Bluetooth devices for a bounded time. Returns name, address, type, bond state, and RSSI when available.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("duration_ms", buildJsonObject {
                put("type", "integer")
                put("description", "Scan duration in milliseconds, default 8000, range 1000-15000.")
            })
        })
    },
    execute = { input ->
        val durationMs = (input.jsonObject["duration_ms"]?.jsonPrimitive?.intOrNull ?: 8_000)
            .coerceIn(1_000, 15_000)
        nearbyResultPart(backend.scan(durationMs))
    },
)

fun scanNearbyBluetoothDevicesTool(context: Context): Tool =
    scanNearbyBluetoothDevicesTool(AndroidNearbyDevicesBackend(context))

fun getStepCountTool(context: Context): Tool = Tool(
    name = "get_step_count",
    description = "Read the latest step counter value from the device's step sensor. Requires ACTIVITY_RECOGNITION permission.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val ctx = context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", "NO_PERMISSION"); put("message", "ACTIVITY_RECOGNITION permission not granted.")
                }.toString()))
            }
        }
        val sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (stepSensor == null) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "NO_SENSOR"); put("message", "Step counter sensor not available on this device.")
            }.toString()))
        }
        val result = CompletableDeferred<Float>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                result.complete(event.values[0])
                sensorManager.unregisterListener(this)
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_UI)
        val steps = withTimeoutOrNull(2000L) { result.await() } ?: run {
            sensorManager.unregisterListener(listener); return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "TIMEOUT"); put("message", "Step sensor did not respond within 2 seconds.")
            }.toString()))
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("steps", steps.toLong())
            put("unit", "total steps since last device boot (or sensor reset)")
        }.toString()))
    }
)
