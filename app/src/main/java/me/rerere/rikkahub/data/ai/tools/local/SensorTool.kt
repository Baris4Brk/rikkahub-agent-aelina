package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private val FRIENDLY_TO_TYPE: Map<String, Int> = mapOf(
    "accelerometer" to Sensor.TYPE_ACCELEROMETER,
    "gyroscope" to Sensor.TYPE_GYROSCOPE,
    "light" to Sensor.TYPE_LIGHT,
    "proximity" to Sensor.TYPE_PROXIMITY,
    "magnetic_field" to Sensor.TYPE_MAGNETIC_FIELD,
    "pressure" to Sensor.TYPE_PRESSURE,
    "temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE,
    "humidity" to Sensor.TYPE_RELATIVE_HUMIDITY,
    "step_counter" to Sensor.TYPE_STEP_COUNTER,
    "linear_acceleration" to Sensor.TYPE_LINEAR_ACCELERATION,
    "gravity" to Sensor.TYPE_GRAVITY,
    "rotation_vector" to Sensor.TYPE_ROTATION_VECTOR,
)

private val TYPE_TO_FRIENDLY: Map<Int, String> = FRIENDLY_TO_TYPE.entries.associate { it.value to it.key }

private val UNIT_BY_FRIENDLY: Map<String, String> = mapOf(
    "accelerometer" to "m/s^2",
    "gravity" to "m/s^2",
    "linear_acceleration" to "m/s^2",
    "gyroscope" to "rad/s",
    "magnetic_field" to "uT",
    "light" to "lx",
    "proximity" to "cm",
    "pressure" to "hPa",
    "temperature" to "°C",
    "humidity" to "%",
)

private val HEALTH_FRIENDLY_TO_TYPE: Map<String, Int> = mapOf(
    "heart_rate" to Sensor.TYPE_HEART_RATE,
    "heart_beat" to Sensor.TYPE_HEART_BEAT,
)

internal data class HealthSensorDescriptor(
    val type: String,
    val name: String,
    val vendor: String,
    val maxRange: Float,
    val resolution: Float,
)

internal sealed interface HealthSensorsResult {
    data class Available(val sensors: List<HealthSensorDescriptor>) : HealthSensorsResult
    data class Reading(
        val type: String,
        val values: List<Double>,
        val unit: String?,
        val sampleCount: Int,
        val timestampMs: Long,
    ) : HealthSensorsResult
    data class Error(val code: String, val message: String) : HealthSensorsResult
}

/** Seam around SensorManager so Tool behavior can be tested without Android hardware. */
internal interface HealthSensorsBackend {
    fun availableSensors(): HealthSensorsResult
    suspend fun read(type: String, durationMs: Int): HealthSensorsResult
}

private class AndroidHealthSensorsBackend(private val context: Context) : HealthSensorsBackend {
    private val sensorManager
        get() = context.getSystemService(SensorManager::class.java)

    override fun availableSensors(): HealthSensorsResult {
        val manager = sensorManager
            ?: return HealthSensorsResult.Error("NO_SENSOR_MANAGER", "SensorManager is unavailable.")
        val friendlyByType = HEALTH_FRIENDLY_TO_TYPE.entries.associate { it.value to it.key }
        return HealthSensorsResult.Available(
            manager.getSensorList(Sensor.TYPE_ALL)
                .filter { it.type in friendlyByType }
                .map { sensor ->
                    HealthSensorDescriptor(
                        type = friendlyByType.getValue(sensor.type),
                        name = sensor.name,
                        vendor = sensor.vendor,
                        maxRange = sensor.maximumRange,
                        resolution = sensor.resolution,
                    )
                }
        )
    }

    override suspend fun read(type: String, durationMs: Int): HealthSensorsResult {
        val sensorType = HEALTH_FRIENDLY_TO_TYPE[type]
            ?: return HealthSensorsResult.Error(
                "UNKNOWN_SENSOR_TYPE",
                "Supported health sensor types: ${HEALTH_FRIENDLY_TO_TYPE.keys.joinToString()}.",
            )
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BODY_SENSORS) !=
            PackageManager.PERMISSION_GRANTED) {
            return HealthSensorsResult.Error(
                "NO_PERMISSION",
                "BODY_SENSORS permission is required to read $type.",
            )
        }
        val manager = sensorManager
            ?: return HealthSensorsResult.Error("NO_SENSOR_MANAGER", "SensorManager is unavailable.")
        val sensor = manager.getDefaultSensor(sensorType)
            ?: return HealthSensorsResult.Error("NO_SENSOR", "$type is not available on this device.")

        val lock = Any()
        val sums = mutableListOf<Double>()
        var sampleCount = 0
        var timestampMs = 0L
        val acceptingSamples = java.util.concurrent.atomic.AtomicBoolean(true)

        fun recordValues(values: FloatArray) {
            synchronized(lock) {
                if (sums.isEmpty()) repeat(values.size) { sums.add(0.0) }
                values.forEachIndexed { index, value ->
                    if (index < sums.size) sums[index] += value.toDouble()
                }
                sampleCount++
                timestampMs = System.currentTimeMillis()
            }
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) = recordValues(event.values)
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val triggerListener = object : android.hardware.TriggerEventListener() {
            override fun onTrigger(event: android.hardware.TriggerEvent) {
                if (!acceptingSamples.get()) return
                recordValues(event.values)
                if (acceptingSamples.get()) {
                    runCatching { manager.requestTriggerSensor(this, sensor) }
                }
            }
        }
        val isTriggerSensor = type == "heart_beat"

        return try {
            val registered = if (isTriggerSensor) {
                manager.requestTriggerSensor(triggerListener, sensor)
            } else {
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
            if (!registered) {
                return HealthSensorsResult.Error("READ_FAILED", "Android refused the sensor listener.")
            }
            delay(durationMs.toLong())
            synchronized(lock) {
                if (sampleCount == 0) {
                    HealthSensorsResult.Error("NO_SAMPLES", "$type produced no samples.")
                } else {
                    HealthSensorsResult.Reading(
                        type = type,
                        values = sums.map { it / sampleCount },
                        unit = if (type == "heart_rate") "bpm" else null,
                        sampleCount = sampleCount,
                        timestampMs = timestampMs,
                    )
                }
            }
        } catch (security: SecurityException) {
            HealthSensorsResult.Error(
                "NO_PERMISSION",
                security.message ?: "Android denied access to $type.",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            HealthSensorsResult.Error("READ_FAILED", error.message ?: "Health sensor read failed.")
        } finally {
            acceptingSamples.set(false)
            if (isTriggerSensor) {
                runCatching { manager.cancelTriggerSensor(triggerListener, sensor) }
            } else {
                runCatching { manager.unregisterListener(listener) }
            }
        }
    }
}

private fun healthResultParts(result: HealthSensorsResult): List<UIMessagePart> =
    listOf(UIMessagePart.Text(when (result) {
        is HealthSensorsResult.Error -> buildJsonObject {
            put("error", result.code)
            put("message", result.message)
        }
        is HealthSensorsResult.Available -> buildJsonObject {
            put("count", result.sensors.size)
            put("sensors", buildJsonArray {
                result.sensors.forEach { sensor ->
                    addJsonObject {
                        put("type", sensor.type)
                        put("name", sensor.name)
                        put("vendor", sensor.vendor)
                        put("max_range", sensor.maxRange)
                        put("resolution", sensor.resolution)
                    }
                }
            })
        }
        is HealthSensorsResult.Reading -> buildJsonObject {
            put("type", result.type)
            put("values", buildJsonArray { result.values.forEach { add(it) } })
            result.unit?.let { put("unit", it) }
            put("sample_count", result.sampleCount)
            put("timestamp_ms", result.timestampMs)
        }
    }.toString()))

internal fun listHealthSensorsTool(backend: HealthSensorsBackend): Tool = Tool(
    name = "list_health_sensors",
    description = "List supported body sensors such as heart-rate and heart-beat sensors.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = { healthResultParts(backend.availableSensors()) },
)

fun listHealthSensorsTool(context: Context): Tool =
    listHealthSensorsTool(AndroidHealthSensorsBackend(context))

internal fun readHealthSensorTool(backend: HealthSensorsBackend): Tool = Tool(
    name = "read_health_sensor",
    description = "Read a bounded sample from a body sensor. Supported types: heart_rate, heart_beat.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("type", buildJsonObject {
                    put("type", "string")
                    put("description", "Health sensor type: heart_rate or heart_beat.")
                })
                put("duration_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Sample duration in milliseconds, default 1000, range 100-5000.")
                })
            },
            required = listOf("type"),
        )
    },
    execute = { input ->
        val type = input.jsonObject["type"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool healthResultParts(
                HealthSensorsResult.Error("MISSING_TYPE", "type is required."),
            )
        val durationMs = (input.jsonObject["duration_ms"]?.jsonPrimitive?.intOrNull ?: 1_000)
            .coerceIn(100, 5_000)
        healthResultParts(backend.read(type, durationMs))
    },
)

fun readHealthSensorTool(context: Context): Tool =
    readHealthSensorTool(AndroidHealthSensorsBackend(context))

fun listSensorsTool(context: Context): Tool = Tool(
    name = "list_sensors",
    description = """
        List all available sensors on the device, including their type, vendor, and operating range.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    execute = {
        val sm = context.getSystemService(SensorManager::class.java)
        val payload = if (sm == null) {
            buildJsonObject { put("error", "SensorManager unavailable") }
        } else {
            val sensors = sm.getSensorList(Sensor.TYPE_ALL)
            buildJsonObject {
                put("sensors", buildJsonArray {
                    sensors.forEach { s ->
                        addJsonObject {
                            put("name", s.name)
                            put("type", TYPE_TO_FRIENDLY[s.type] ?: s.stringType ?: "type_${s.type}")
                            put("vendor", s.vendor)
                            put("max_range", s.maximumRange)
                            put("resolution", s.resolution)
                        }
                    }
                })
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun readSensorTool(context: Context): Tool = Tool(
    name = "read_sensor",
    description = """
        Read a single value (or short averaged sample) from a named device sensor,
        e.g., accelerometer, gyroscope, light, proximity.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("type", buildJsonObject {
                    put("type", "string")
                    put("description", "Sensor type, e.g. \"accelerometer\"")
                })
                put("duration_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional sample window in ms, default 200, max 5000")
                })
            },
            required = listOf("type")
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val typeName = params["type"]?.jsonPrimitive?.contentOrNull
            ?: error("type is required")
        val durationMs = (params["duration_ms"]?.jsonPrimitive?.intOrNull ?: 200)
            .coerceIn(1, 5000)
        val typeInt = FRIENDLY_TO_TYPE[typeName]
        val payload = if (typeInt == null) {
            buildJsonObject { put("error", "unknown sensor type: $typeName") }
        } else {
            val sm = context.getSystemService(SensorManager::class.java)
            val sensor = sm?.getDefaultSensor(typeInt)
            if (sm == null || sensor == null) {
                buildJsonObject { put("error", "sensor unavailable on device") }
            } else {
                val lock = Any()
                val sums = mutableListOf<Double>()
                var count = 0
                var lastTimestamp = 0L
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        synchronized(lock) {
                            if (sums.isEmpty()) {
                                repeat(event.values.size) { sums.add(0.0) }
                            }
                            for (i in event.values.indices) {
                                if (i < sums.size) sums[i] = sums[i] + event.values[i]
                            }
                            count++
                            lastTimestamp = System.currentTimeMillis()
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                try {
                    sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
                    delay(durationMs.toLong())
                } finally {
                    sm.unregisterListener(listener)
                }
                val (resultValues, resultCount, resultTimestamp) = synchronized(lock) {
                    Triple(sums.toList(), count, lastTimestamp)
                }
                buildJsonObject {
                    put("type", typeName)
                    put("values", buildJsonArray {
                        if (resultCount > 0) {
                            resultValues.forEach { add(it / resultCount) }
                        }
                    })
                    UNIT_BY_FRIENDLY[typeName]?.let { put("unit", it) }
                    put("timestamp_ms", if (resultTimestamp != 0L) resultTimestamp else System.currentTimeMillis())
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
