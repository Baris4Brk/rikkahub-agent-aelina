package me.rerere.rikkahub.data.ai.tools.local

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

internal data class CoordinatePair(val latitude: Double, val longitude: Double)

internal object Wgs84Gcj02Converter {
    fun convert(latitude: Double, longitude: Double): CoordinatePair {
        if (outsideChina(latitude, longitude)) return CoordinatePair(latitude, longitude)
        var latitudeDelta = transformLatitude(longitude - 105.0, latitude - 35.0)
        var longitudeDelta = transformLongitude(longitude - 105.0, latitude - 35.0)
        val radLatitude = latitude / 180.0 * PI
        var magic = sin(radLatitude)
        magic = 1 - EARTH_ECCENTRICITY * magic * magic
        val rootMagic = sqrt(magic)
        latitudeDelta = latitudeDelta * 180.0 /
            ((EARTH_RADIUS * (1 - EARTH_ECCENTRICITY)) / (magic * rootMagic) * PI)
        longitudeDelta = longitudeDelta * 180.0 /
            (EARTH_RADIUS / rootMagic * kotlin.math.cos(radLatitude) * PI)
        return CoordinatePair(latitude + latitudeDelta, longitude + longitudeDelta)
    }

    private fun outsideChina(latitude: Double, longitude: Double): Boolean =
        longitude !in 72.004..137.8347 || latitude !in 0.8293..55.8271

    private fun transformLatitude(x: Double, y: Double): Double {
        var result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
            0.2 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        result += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        result += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return result
    }

    private fun transformLongitude(x: Double, y: Double): Double {
        var result = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y +
            0.1 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        result += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        result += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return result
    }

    private const val EARTH_RADIUS = 6_378_245.0
    private const val EARTH_ECCENTRICITY = 0.00669342162296594323
}
