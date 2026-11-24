package ly.anoma.hex.speech

import android.content.Context
import android.content.res.AssetManager
import java.text.Normalizer
import java.util.Locale

/** A language entry parsed from FUTO Keyboard's `layouts/mapping.yaml` and `layouts/names.yaml`. */
internal data class FutoLanguage(
    val localeTag: String,
    val whisperCode: String,
    val nativeName: String,
    val englishName: String,
    val layouts: List<String>,
) {
    val defaultLayout: String get() = layouts.firstOrNull() ?: "qwerty"
    val hasHexTypingModel: Boolean get() = Spoken.matching(localeTag) != null
    val defaultLayoutLabel: String get() = FutoLanguages.formatLayoutName(defaultLayout)
}

internal object FutoLanguages {
    @Volatile private var cached: List<FutoLanguage>? = null
    @Volatile private var byTag: Map<String, FutoLanguage>? = null

    fun all(context: Context): List<FutoLanguage> = all(context.assets)

    fun all(assets: AssetManager): List<FutoLanguage> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val loaded = load(assets)
            cached = loaded
            byTag = loaded.associateBy { it.localeTag }
            return loaded
        }
    }

    fun find(context: Context, localeTag: String?): FutoLanguage? {
        if (localeTag.isNullOrBlank()) return null
        val list = all(context)
        val map = byTag ?: return null
        map[localeTag]?.let { return it }
        val normalized = localeTag.replace('-', '_')
        map[normalized]?.let { return it }
        val langOnly = normalized.substringBefore('_').lowercase()
        return list.firstOrNull { it.localeTag.substringBefore('_').equals(langOnly, ignoreCase = true) }
    }

    fun formatLayoutName(id: String): String {
        val special = mapOf(
            "qwerty" to "QWERTY",
            "qwertz" to "QWERTZ",
            "azerty" to "AZERTY",
            "pcqwerty" to "PC QWERTY",
            "colemak" to "Colemak",
            "colemak_dh" to "Colemak-DH",
            "colemak_dh_ansi" to "Colemak-DH (ANSI)",
            "dvorak" to "Dvorak",
            "bepo" to "BÉPO",
            "bepo_alt" to "BÉPO (Alt)",
            "workman" to "Workman",
            "nordic" to "Nordic",
            "swiss" to "Swiss",
            "kasroz" to "Kasroz",
            "clearflow" to "Clearflow",
            "flick" to "Flick",
            "flick_latin" to "Flick (Latin)",
            "japanese_qwerty" to "Japanese QWERTY",
            "zh_qwerty" to "Pinyin QWERTY",
            "zh_stroke" to "Stroke",
            "hindi_inscript" to "Hindi InScript",
            "hindi_compact" to "Hindi Compact",
            "hindi_devanagari" to "Hindi Devanagari",
            "east_slavic" to "JCUKEN (East Slavic)",
            "russian_student" to "Russian Student",
            "russian_yavert" to "Russian YaWERT",
            "russian_yazhert" to "Russian YaZHERT",
            "ipa_qwerty" to "IPA QWERTY",
        )
        special[id]?.let { return it }
        return id.split('_', '-').filter { it.isNotEmpty() }.joinToString(" ") { part ->
            when (part.lowercase()) {
                "qwerty", "qwertz", "azerty", "ansi", "bds", "ipa", "vni", "g" -> part.uppercase()
                else -> part.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
        }
    }

    private fun toLocale(tag: String): Locale =
        Locale.forLanguageTag(tag.replace("__#", "-").replace('_', '-'))

    /** Maps FUTO / Android locale tags to Whisper's ISO-639 language codes. */
    fun whisperCodeFor(localeTag: String): String {
        val norm = localeTag.replace('-', '_')
        val base = norm.substringBefore('_').lowercase()
        return when (base) {
            "nb", "nn" -> "no"
            "iw" -> "he"
            "in" -> "id"
            "bod" -> "bo"
            "fil" -> "tl"
            "zgh" -> "ar"
            else -> base
        }
    }

    private fun capitalize(s: String, locale: Locale): String =
        s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

    private fun load(assets: AssetManager): List<FutoLanguage> {
        val mappings = runCatching {
            assets.open("layouts/mapping.yaml").bufferedReader().use { parseMappingYaml(it.readText()) }
        }.getOrDefault(emptyMap())
        val names = runCatching {
            assets.open("layouts/names.yaml").bufferedReader().use { parseNamesYaml(it.readText()) }
        }.getOrDefault(emptyMap())

        if (mappings.isEmpty()) {
            return DictationLanguage.entries.map {
                FutoLanguage(it.code, it.code, it.label, it.name, listOf("qwerty"))
            }
        }

        return mappings.entries
            .filter { (tag, _) -> tag != "zz" && tag != "ipa" }
            .map { (tag, layouts) ->
                val locale = toLocale(tag)
                val overrideMap = names[tag] ?: names[tag.replace('-', '_')]
                    ?: names[tag.substringBefore('_')]
                val engRaw = overrideMap?.get("en")
                    ?: locale.getDisplayName(Locale.ENGLISH).takeIf { it.isNotBlank() && it != tag }
                    ?: tag
                val natRaw = overrideMap?.get(tag)
                    ?: overrideMap?.get(tag.replace('-', '_'))
                    ?: overrideMap?.get(locale.language)
                    ?: locale.getDisplayName(locale).takeIf { it.isNotBlank() && it != tag }
                    ?: engRaw
                val english = capitalize(engRaw, Locale.ENGLISH)
                val native = capitalize(natRaw, locale)
                FutoLanguage(
                    localeTag = tag,
                    whisperCode = whisperCodeFor(tag),
                    nativeName = native,
                    englishName = english,
                    layouts = layouts,
                )
            }
            .sortedWith(compareBy({ it.englishName.lowercase(Locale.ENGLISH) }, { it.localeTag }))
    }

    internal fun parseMappingYaml(text: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        var currentLang: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trimEnd()
            if (line.isBlank() || line == "languages:") continue
            val trimmed = line.trim()
            if (trimmed.startsWith("- ")) {
                val layout = trimmed.removePrefix("- ").trim().trim('"', '\'')
                if (currentLang != null && layout.isNotEmpty()) {
                    out.getOrPut(currentLang) { mutableListOf() }.add(layout)
                }
            } else if (line.startsWith("  ") && trimmed.endsWith(":")) {
                currentLang = trimmed.removeSuffix(":").trim().trim('"', '\'')
                out.getOrPut(currentLang) { mutableListOf() }
            }
        }
        return out
    }

    internal fun parseNamesYaml(text: String): Map<String, Map<String, String>> {
        val out = LinkedHashMap<String, MutableMap<String, String>>()
        var currentLang: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trimEnd()
            if (line.isBlank()) continue
            if (!line.startsWith(" ") && line.endsWith(":")) {
                currentLang = line.removeSuffix(":").trim().trim('"', '\'')
                out.getOrPut(currentLang) { mutableMapOf() }
            } else if (line.startsWith("  ") && currentLang != null && ':' in line) {
                val idx = line.indexOf(':')
                val k = line.substring(0, idx).trim().trim('"', '\'')
                val v = line.substring(idx + 1).trim().trim('"', '\'')
                if (k.isNotEmpty() && v.isNotEmpty()) {
                    out[currentLang]!![k] = v
                }
            }
        }
        return out
    }

    fun normalizeForSearch(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
}
