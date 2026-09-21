package kr.family.homeway.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Explicit non-exported PendingIntent target; never starts sharing from a background event. */
class ActivityMotionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TrackingService.receiveActivityMotion(intent)
    }
}
