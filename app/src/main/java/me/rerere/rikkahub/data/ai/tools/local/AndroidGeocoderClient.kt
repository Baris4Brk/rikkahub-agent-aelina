package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal data class AndroidGeocodeCandidate(
    val formattedAddress: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val province: String? = null,
    val city: String? = null,
    val district: String? = null,
    val township: String? = null,
    val road: String? = null,
    val houseNumber: String? = null,
    val postalCode: String? = null,
)

internal interface AndroidGeocoderClient {
    fun isPresent(): Boolean

    suspend fun query(
        latitude: Double,
        longitude: Double,
        maxResults: Int,
        locale: Locale,
    ): List<AndroidGeocodeCandidate>
}

internal class PlatformAndroidGeocoderClient(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : AndroidGeocoderClient {
    override fun isPresent(): Boolean = Geocoder.isPresent()

    override suspend fun query(
        latitude: Double,
        longitude: Double,
        maxResults: Int,
        locale: Locale,
    ): List<AndroidGeocodeCandidate> {
        val geocoder = Geocoder(context, locale)
        return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            queryAsync(geocoder, latitude, longitude, maxResults)
        } else {
            withContext(ioDispatcher) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(latitude, longitude, maxResults)
                    .orEmpty()
                    .map(Address::toCandidate)
            }
        }
    }

    private suspend fun queryAsync(
        geocoder: Geocoder,
        latitude: Double,
        longitude: Double,
        maxResults: Int,
    ): List<AndroidGeocodeCandidate> = suspendCancellableCoroutine { continuation ->
        val completion = SingleCompletionGate()
        continuation.invokeOnCancellation { completion.tryComplete() }

        val listener = object : Geocoder.GeocodeListener {
            override fun onGeocode(addresses: MutableList<Address>) {
                if (completion.tryComplete() && continuation.isActive) {
                    continuation.resume(addresses.map(Address::toCandidate))
                }
            }

            override fun onError(errorMessage: String?) {
                if (completion.tryComplete() && continuation.isActive) {
                    continuation.resumeWithException(AndroidGeocoderPlatformException())
                }
            }
        }

        try {
            geocoder.getFromLocation(latitude, longitude, maxResults, listener)
        } catch (failure: Exception) {
            if (completion.tryComplete() && continuation.isActive) {
                continuation.resumeWithException(AndroidGeocoderPlatformException(failure))
            }
        }
    }
}

internal class SingleCompletionGate {
    private val completed = AtomicBoolean(false)

    fun tryComplete(): Boolean = completed.compareAndSet(false, true)
}

internal class AndroidGeocoderPlatformException(cause: Throwable? = null) :
    Exception("Android platform geocoder failed.", cause)

private fun Address.toCandidate(): AndroidGeocodeCandidate = AndroidGeocodeCandidate(
    formattedAddress = addressLines(),
    country = countryName,
    countryCode = countryCode,
    province = adminArea,
    city = locality,
    district = subAdminArea ?: subLocality,
    township = subLocality?.takeUnless { it == subAdminArea },
    road = thoroughfare,
    houseNumber = subThoroughfare,
    postalCode = postalCode,
)

private fun Address.addressLines(): String? {
    if (maxAddressLineIndex < 0) return null
    return (0..maxAddressLineIndex.coerceAtMost(15))
        .mapNotNull { index -> getAddressLine(index)?.trim()?.takeIf(String::isNotEmpty) }
        .joinToString(", ")
        .takeIf(String::isNotEmpty)
}
