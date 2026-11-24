package ly.anoma.hex.keyboard

import android.content.Context
import android.content.SharedPreferences

internal class CustomizePrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("customize", Context.MODE_PRIVATE)

    /** Overall keyboard height scale (0.75x .. 1.35x, default 1.0f). */
    var keyboardHeightScale: Float
        get() = prefs.getFloat(KEY_HEIGHT_SCALE, 1.0f).coerceIn(0.75f, 1.35f)
        set(value) { prefs.edit().putFloat(KEY_HEIGHT_SCALE, value.coerceIn(0.75f, 1.35f)).apply() }

    /** Overall keyboard width scale / side margin offset (0.80x .. 1.05x, default 1.0f). */
    var keyboardWidthScale: Float
        get() = prefs.getFloat(KEY_WIDTH_SCALE, 1.0f).coerceIn(0.80f, 1.05f)
        set(value) { prefs.edit().putFloat(KEY_WIDTH_SCALE, value.coerceIn(0.80f, 1.05f)).apply() }

    /** Key label / glyph font size multiplier (0.75f .. 1.45f, default 1.0f). */
    var fontScale: Float
        get() = prefs.getFloat(KEY_FONT_SCALE, 1.0f).coerceIn(0.75f, 1.45f)
        set(value) { prefs.edit().putFloat(KEY_FONT_SCALE, value.coerceIn(0.75f, 1.45f)).apply() }

    /** Individual keycap width multiplier (0.70f .. 1.30f, default 1.0f). */
    var buttonWidthScale: Float
        get() = prefs.getFloat(KEY_BUTTON_WIDTH_SCALE, 1.0f).coerceIn(0.70f, 1.30f)
        set(value) { prefs.edit().putFloat(KEY_BUTTON_WIDTH_SCALE, value.coerceIn(0.70f, 1.30f)).apply() }

    /** Individual keycap height multiplier (0.70f .. 1.25f, default 1.0f). */
    var buttonHeightScale: Float
        get() = prefs.getFloat(KEY_BUTTON_HEIGHT_SCALE, 1.0f).coerceIn(0.70f, 1.25f)
        set(value) { prefs.edit().putFloat(KEY_BUTTON_HEIGHT_SCALE, value.coerceIn(0.70f, 1.25f)).apply() }

    fun resetAll() {
        prefs.edit()
            .putFloat(KEY_HEIGHT_SCALE, 1.0f)
            .putFloat(KEY_WIDTH_SCALE, 1.0f)
            .putFloat(KEY_FONT_SCALE, 1.0f)
            .putFloat(KEY_BUTTON_WIDTH_SCALE, 1.0f)
            .putFloat(KEY_BUTTON_HEIGHT_SCALE, 1.0f)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "customize"
        const val KEY_HEIGHT_SCALE = "keyboardHeightScale"
        const val KEY_WIDTH_SCALE = "keyboardWidthScale"
        const val KEY_FONT_SCALE = "fontScale"
        const val KEY_BUTTON_WIDTH_SCALE = "buttonWidthScale"
        const val KEY_BUTTON_HEIGHT_SCALE = "buttonHeightScale"
    }
}
