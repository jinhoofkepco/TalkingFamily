package kr.family.homeway.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object CurrentLocationProvider {
    class Unavailable(message: String) : Exception(message)

    /** No last-known fallback: old coordinates must never masquerade as a current fix. */
    @SuppressLint("MissingPermission")
    suspend fun capture(context: Context, durationMillis: Long = 15_000L): Location {
        require(durationMillis in 1_000L..60_000L)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw Unavailable("정확한 위치 권한을 허용해 주세요.")
        }
        return try { withTimeout(durationMillis + 3_000L) {
            suspendCancellableCoroutine { continuation ->
                val cancellation = CancellationTokenSource()
                continuation.invokeOnCancellation { cancellation.cancel() }
                val request = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(0)
                    .setDurationMillis(durationMillis)
                    .build()
                LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(request, cancellation.token)
                    .addOnSuccessListener { location ->
                        if (!continuation.isActive) return@addOnSuccessListener
                        val validationError = location?.let { LocationFixValidation.error(it.toFixSample(), SystemClock.elapsedRealtime()) }
                        when {
                            location == null -> continuation.resumeWithException(Unavailable("현재 위치를 찾지 못했어요. 창가나 실외에서 다시 시도해 주세요."))
                            validationError != null -> continuation.resumeWithException(Unavailable(validationError))
                            else -> continuation.resume(location)
                        }
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
            }
        } } catch (_: TimeoutCancellationException) {
            throw Unavailable("현재 위치 확인 시간이 지났어요. 창가나 실외에서 다시 시도해 주세요.")
        }
    }
}

internal fun Location.toFixSample() = LocationFixSample(latitude, longitude,
    if (hasAccuracy()) accuracy.toDouble() else null, time, elapsedRealtimeNanos / 1_000_000L)
