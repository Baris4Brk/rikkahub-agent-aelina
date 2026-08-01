package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun ReverseGeocodeResolution.toJson(): JsonObject = when (this) {
    is ReverseGeocodeResolution.Success -> {
        val normalized = address.normalized()
        require(normalized.hasUsableAddressField()) { "A successful reverse-geocode result must contain an address field." }
        buildJsonObject {
            put("ok", true)
            put("address_status", "resolved")
            put("address", normalized.toJson())
            put("cached", cached)
            cacheAgeMs?.let { put("cache_age_ms", it.coerceAtLeast(0L)) }
            put("attempted_providers", attemptedProviders.toJsonArray())
        }
    }
    is ReverseGeocodeResolution.Failure -> buildJsonObject {
        put("ok", false)
        put("address_status", "failed")
        put("code", error.code)
        put("message", error.message)
        error.recovery?.let { put("recovery", it) }
        put("attempted_providers", attemptedProviders.toJsonArray())
    }
}

internal fun StructuredAddress.toJson(): JsonObject = normalized().let { address ->
    buildJsonObject {
        address.formattedAddress?.let { put("formatted_address", it) }
        address.country?.let { put("country", it) }
        address.countryCode?.let { put("country_code", it) }
        address.province?.let { put("province", it) }
        address.city?.let { put("city", it) }
        address.district?.let { put("district", it) }
        address.township?.let { put("township", it) }
        address.village?.let { put("village", it) }
        address.road?.let { put("road", it) }
        address.houseNumber?.let { put("house_number", it) }
        address.postalCode?.let { put("postal_code", it) }
        address.poiName?.let { put("poi_name", it) }
        put("provider", address.provider)
        put("input_coordinate_system", address.inputCoordinateSystem.name)
        put("query_coordinate_system", address.queryCoordinateSystem.name)
        put("match_type", address.matchType.wireName)
        put("achieved_detail", address.achievedDetail.wireName)
        address.matchedDistanceM?.let { put("matched_distance_m", it) }
        put("is_exact", address.isExact)
        put("coordinate_disclosure", address.coordinateDisclosure.wireName)
        put("explicit_external_provider", address.explicitExternalProvider)
        address.attribution?.let { put("attribution", it) }
        address.requestedLanguage?.let { put("requested_language", it) }
    }
}

private fun List<String>.toJsonArray() = buildJsonArray {
    distinct().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
}
