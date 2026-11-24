package ly.anoma.hex.typing

import android.content.Context
import java.security.MessageDigest
import ly.anoma.hex.speech.Spoken

internal class TypingPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("typing", Context.MODE_PRIVATE)

    /** Enabled entries formatted as `"localeTag:layoutId"` (legacy bare `"localeTag"` is accepted). */
    var enabledLanguages: List<String>
        get() = prefs.getString(ENABLED_LANGUAGES, null)
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        set(value) {
            prefs.edit().putString(ENABLED_LANGUAGES, value.distinct().joinToString("\n")).apply()
        }

    /** Active `"localeTag:layoutId"` entry, or null for Automatic. */
    var activeLanguage: String?
        get() = prefs.getString(ACTIVE_LANGUAGE, null)?.takeIf { it.isNotBlank() }
        set(value) {
            val raw = value?.takeIf { it.isNotBlank() }
            val editor = prefs.edit().putString(ACTIVE_LANGUAGE, raw)
            if (raw != null) {
                val (localeTag, _) = parseEntry(raw)
                val current = enabledLanguages
                if (raw !in current) {
                    editor.putString(ENABLED_LANGUAGES, (current + raw).joinToString("\n"))
                }
                editor.putString("layout", Spoken.matching(localeTag)?.layoutCode)
            } else {
                editor.putString("layout", null)
            }
            editor.apply()
        }

    val activeLocaleTag: String?
        get() = activeLanguage?.let { parseEntry(it).first }

    val activeLayoutId: String?
        get() = activeLanguage?.let { parseEntry(it).second }

    fun addLanguageLayout(localeTag: String, layoutId: String) {
        val tag = localeTag.trim()
        val layout = layoutId.trim()
        if (tag.isEmpty()) return
        val entry = formatEntry(tag, layout)
        val current = enabledLanguages.filterNot { it == tag }
        if (entry !in current) {
            enabledLanguages = current + entry
        } else if (current != enabledLanguages) {
            enabledLanguages = current
        }
    }

    fun removeLanguage(entry: String) {
        val updated = enabledLanguages.filter { it != entry }
        enabledLanguages = updated
        if (activeLanguage == entry) {
            activeLanguage = updated.firstOrNull()
        }
    }

    var layout: Spoken?
        get() = Spoken.matching(activeLocaleTag ?: prefs.getString("layout", null))
        set(value) {
            activeLanguage = value?.layoutCode
        }

    /** Settings › Keyboard › Auto-capitalisation: shift comes on where the field asks for a capital. On by default. */
    var autoCapitalize: Boolean
        get() = prefs.getBoolean("autoCapitalize", true)
        set(value) { prefs.edit().putBoolean("autoCapitalize", value).apply() }
    var autocorrect: Boolean
        get() = prefs.getBoolean("autocorrect", true)
        set(value) { prefs.edit().putBoolean("autocorrect", value).apply() }
    var suggestSwearWords: Boolean
        get() = prefs.getBoolean(SUGGEST_SWEAR_WORDS, false)
        set(value) { prefs.edit().putBoolean(SUGGEST_SWEAR_WORDS, value).apply() }
    var spatial: Boolean
        get() = prefs.getBoolean("spatial", true)
        set(value) { prefs.edit().putBoolean("spatial", value).apply() }
    /** Keyboard › Swipe typing: slide through a word's letters to type it. On by default, as on iPhone and Gboard. */
    var swipe: Boolean
        get() = prefs.getBoolean("swipe", true)
        set(value) { prefs.edit().putBoolean("swipe", value).apply() }
    var popups: Boolean
        get() = prefs.getBoolean("popups", true)
        set(value) { prefs.edit().putBoolean("popups", value).apply() }
    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(value) { prefs.edit().putBoolean("haptics", value).apply() }
    /** Labs › Keyboard › Symbols on letter keys: a held second or third row key offers its symbol first. */
    var letterSymbols: Boolean
        get() = prefs.getBoolean("letterSymbols", false)
        set(value) { prefs.edit().putBoolean("letterSymbols", value).apply() }
    /** Labs › Keyboard › Number row: the digits above the letters. Off by default. */
    var numberRow: Boolean
        get() = prefs.getBoolean(NUMBER_ROW, false)
        set(value) { prefs.edit().putBoolean(NUMBER_ROW, value).apply() }
    /** Labs › Keyboard › Emoji suggestions: the word being typed's emoji in the strip, off by default. */
    var emojiSuggestions: Boolean
        get() = prefs.getBoolean(EMOJI_SUGGESTIONS, false)
        set(value) { prefs.edit().putBoolean(EMOJI_SUGGESTIONS, value).apply() }
    /** Labs › Keyboard › Keyboard tools: emoji, paste, settings and undo in the strip, off by default. */
    var keyboardTools: Boolean
        get() = prefs.getBoolean(KEYBOARD_TOOLS, false)
        set(value) { prefs.edit().putBoolean(KEYBOARD_TOOLS, value).apply() }

    // Remember only explicit rejection of this exact pair in this language; never save typed text.
    private fun pair(language: String, original: String, replacement: String): String {
        val bytes = (language + "\u0000" + original.lowercase() + "\u0000" + replacement.lowercase()).toByteArray()
        return android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes), android.util.Base64.NO_WRAP)
    }
    fun blocked(language: String, original: String, replacement: String) =
        prefs.getInt(pair(language, original, replacement), 0) >= 2
    fun undo(language: String, original: String, replacement: String) {
        val key = pair(language, original, replacement)
        prefs.edit().putInt(key, (prefs.getInt(key, 0) + 1).coerceAtMost(2)).apply()
    }

    companion object {
        const val ENABLED_LANGUAGES = "enabledLanguages"
        const val ACTIVE_LANGUAGE = "activeLanguage"
        const val SUGGEST_SWEAR_WORDS = "suggestSwearWords"
        const val NUMBER_ROW = "numberRow"
        const val EMOJI_SUGGESTIONS = "emojiSuggestions"
        const val KEYBOARD_TOOLS = "keyboardTools"

        fun formatEntry(localeTag: String, layoutId: String?): String =
            if (layoutId.isNullOrBlank()) localeTag else "$localeTag:$layoutId"

        fun parseEntry(raw: String): Pair<String, String?> {
            val idx = raw.indexOf(':')
            return if (idx < 0) raw to null else raw.substring(0, idx) to raw.substring(idx + 1).takeIf { it.isNotBlank() }
        }
    }
}
