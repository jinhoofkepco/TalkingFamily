package kr.family.homeway.overlay

import android.content.Context

/** Separate from account/location settings: stopping this shortcut never stops location sharing. */
class OverlayPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("floating_star", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean("enabled", false)
        // Persist the choice before starting/stopping the service, including an explicit off.
        set(value) { preferences.edit().putBoolean("enabled", value).commit() }

    val hasSavedChoice: Boolean get() = preferences.contains("enabled")

    var edgeRight: Boolean
        get() = preferences.getBoolean("edge_right", true)
        set(value) { preferences.edit().putBoolean("edge_right", value).apply() }

    var verticalFraction: Float
        get() = preferences.getFloat("vertical_fraction", 0.34f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.34f
        set(value) { preferences.edit().putFloat("vertical_fraction", value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.34f).apply() }
}
