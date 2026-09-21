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
    suspend fun capture(context: Context): Location {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw Unavailable("정확한 위치 권한을 허용해 주세요.")
        }
        return try { withTimeout(18_000L) {
            suspendCancellableCoroutine { continuation ->
                val cancellation = CancellationTokenSource()
                continuation.invokeOnCancellation { cancellation.cancel() }
                val request = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(0)
                    .setDurationMillis(15_000)
                    .build()
                LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(request, cancellation.token)
                    .addOnSuccessListener { location ->
                        if (!continuation.isActive) return@addOnSuccessListener
                        val ageMillis = location?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000L }
                        when {
                            location == null -> continuation.resumeWithException(Unavailable("현재 위치를 찾지 못했어요. 창가나 실외에서 다시 시도해 주세요."))
                            ageMillis == null || ageMillis !in 0..20_000 -> continuation.resumeWithException(Unavailable("새 위치를 확인하지 못했어요. 이전 위치는 보내지 않았어요."))
                            !location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy > 150f ->
                                continuation.resumeWithException(Unavailable("위치 오차가 커서 보내지 못했어요. 잠시 후 다시 시도해 주세요."))
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
