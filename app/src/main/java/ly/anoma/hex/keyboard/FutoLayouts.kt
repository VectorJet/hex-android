package ly.anoma.hex.keyboard

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal data class FutoKeySpec(
    val kind: String,
    val label: String = "",
    val output: String = "",
    val shiftable: Boolean = true,
    val moreKeys: List<String> = emptyList(),
    val shiftedLabel: String? = null,
    val shiftedOutput: String? = null,
    val shiftedMoreKeys: List<String> = emptyList(),
    val hint: String? = null,
    val noBg: Boolean = false,
) {
    fun displayLabel(shifted: Boolean, locale: Locale): String {
        if (!shifted) return label
        shiftedLabel?.let { return it }
        return if (shiftable) label.uppercase(locale) else label
    }

    fun typedOutput(shifted: Boolean, locale: Locale): String {
        if (!shifted) return output
        shiftedOutput?.let { return it }
        return if (shiftable) output.uppercase(locale) else output
    }
}

internal data class FutoRowSpec(
    val type: String,
    val keys: List<FutoKeySpec>,
)

internal data class FutoLayoutDef(
    val id: String,
    val name: String,
    val autoShift: Boolean,
    val shiftable: Boolean,
    val numberRowMode: String,
    val rows: List<FutoRowSpec>,
) {
    val hasExplicitNumberRow: Boolean get() = rows.any { it.type == "numbers" }
    val hasExplicitBottomRow: Boolean get() = rows.any { it.type == "bottom" }
}

internal object FutoLayouts {
    @Volatile private var layoutsCache: Map<String, FutoLayoutDef>? = null
    @Volatile private var textsCache: Map<String, JSONObject>? = null

    fun get(context: Context, layoutId: String?): FutoLayoutDef? {
        if (layoutId.isNullOrBlank()) return null
        return all(context.assets)[layoutId]
    }

    fun get(assets: AssetManager, layoutId: String?): FutoLayoutDef? {
        if (layoutId.isNullOrBlank()) return null
        return all(assets)[layoutId]
    }

    fun nameOf(context: Context, layoutId: String): String =
        get(context, layoutId)?.name ?: ly.anoma.hex.speech.FutoLanguages.formatLayoutName(layoutId)

    fun resolve(context: Context, layoutId: String, localeTag: String): FutoLayoutDef? {
        val raw = get(context.assets, layoutId) ?: return null
        val texts = texts(context.assets)
        val tables = candidateTables(texts, localeTag)
        val resolvedRows = raw.rows.map { row ->
            row.copy(keys = row.keys.flatMap { key -> resolveKey(key, tables) })
        }
        return raw.copy(rows = resolvedRows)
    }

    private fun candidateTables(texts: Map<String, JSONObject>, localeTag: String): List<JSONObject> {
        val norm = localeTag.replace('-', '_')
        val base = norm.substringBefore('_')
        val out = ArrayList<JSONObject>(4)
        texts[norm]?.let(out::add)
        if (base != norm) texts[base]?.let(out::add)
        when (base.lowercase(Locale.ROOT)) {
            "no", "nn" -> texts["nb"]?.let(out::add)
            "he" -> texts["iw"]?.let(out::add)
            "id" -> texts["in"]?.let(out::add)
        }
        texts["DEFAULT"]?.let(out::add)
        return out
    }

    private fun lookupString(tables: List<JSONObject>, key: String): String? {
        for (t in tables) {
            val v = t.optString(key, "")
            if (v.isNotEmpty() && !v.startsWith("!")) return v
        }
        return null
    }

    private fun lookupList(tables: List<JSONObject>, key: String): List<String> {
        for (t in tables) {
            val arr = t.optJSONArray(key) ?: continue
            if (arr.length() > 0) {
                val out = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i, "")
                    if (item.isEmpty()) continue
                    if (item.startsWith("!text/")) {
                        val ref = item.removePrefix("!text/")
                        val subStr = lookupString(tables, ref)
                        if (subStr != null) {
                            out.addAll(subStr.split(',').filter { it.isNotEmpty() })
                        } else {
                            out.addAll(lookupList(tables, ref))
                        }
                    } else if (!item.startsWith("!")) {
                        out.add(item)
                    }
                }
                if (out.isNotEmpty()) return out
            }
        }
        return emptyList()
    }

    private fun resolveTextToken(token: String, tables: List<JSONObject>): String {
        if (!token.startsWith("!text/")) return token
        val ref = token.removePrefix("!text/")
        return lookupString(tables, ref) ?: ""
    }

    private fun resolveMoreKeys(items: List<String>, tables: List<JSONObject>): List<String> {
        val out = ArrayList<String>()
        for (item in items) {
            if (item.startsWith("!text/")) {
                val ref = item.removePrefix("!text/")
                val str = lookupString(tables, ref)
                if (str != null) {
                    out.addAll(str.split(',').filter { it.isNotEmpty() }.map { parseDisplayOutput(it).second })
                } else {
                    out.addAll(lookupList(tables, ref).map { parseDisplayOutput(it).second })
                }
            } else if (!item.startsWith("!")) {
                val (_, output) = parseDisplayOutput(item)
                if (output.isNotEmpty()) out.add(output)
            }
        }
        return out
    }

    private fun parseDisplayOutput(raw: String): Pair<String, String> {
        val idx = raw.indexOf('|')
        return if (idx >= 0) raw.substring(0, idx) to raw.substring(idx + 1) else raw to raw
    }

    private fun resolveKey(key: FutoKeySpec, tables: List<JSONObject>): List<FutoKeySpec> {
        if (key.kind != "key") return listOf(key)
        val resolvedLabel = resolveTextToken(key.label, tables)
        val resolvedOutput = resolveTextToken(key.output, tables)
        if (resolvedLabel.isEmpty() && resolvedOutput.isEmpty()) return emptyList()
        val (cleanLabel, _) = parseDisplayOutput(resolvedLabel)
        val (_, cleanOutput) = parseDisplayOutput(resolvedOutput)
        val finalLabel = cleanLabel.ifEmpty { cleanOutput }
        val finalOutput = cleanOutput.ifEmpty { finalLabel }
        val shiftedLbl = key.shiftedLabel?.let { resolveTextToken(it, tables) }?.takeIf { it.isNotEmpty() }
            ?.let { parseDisplayOutput(it).first }
        val shiftedOut = key.shiftedOutput?.let { resolveTextToken(it, tables) }?.takeIf { it.isNotEmpty() }
            ?.let { parseDisplayOutput(it).second }
        val hintText = key.hint?.let { resolveTextToken(it, tables) }?.takeIf { it.isNotEmpty() }
        return listOf(
            key.copy(
                label = finalLabel,
                output = finalOutput,
                moreKeys = resolveMoreKeys(key.moreKeys, tables),
                shiftedLabel = shiftedLbl,
                shiftedOutput = shiftedOut ?: shiftedLbl,
                shiftedMoreKeys = resolveMoreKeys(key.shiftedMoreKeys, tables),
                hint = hintText,
            )
        )
    }

    fun all(assets: AssetManager): Map<String, FutoLayoutDef> {
        layoutsCache?.let { return it }
        synchronized(this) {
            layoutsCache?.let { return it }
            val loaded = runCatching {
                val raw = assets.open("layouts/futo_layouts.json").bufferedReader().use { it.readText() }
                parseLayoutsJson(raw)
            }.getOrDefault(emptyMap())
            layoutsCache = loaded
            return loaded
        }
    }

    private fun texts(assets: AssetManager): Map<String, JSONObject> {
        textsCache?.let { return it }
        synchronized(this) {
            textsCache?.let { return it }
            val loaded = runCatching {
                val raw = assets.open("layouts/futo_texts.json").bufferedReader().use { it.readText() }
                val root = JSONObject(raw)
                val map = HashMap<String, JSONObject>(root.length())
                val keys = root.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    root.optJSONObject(k)?.let { map[k] = it }
                }
                map
            }.getOrDefault(emptyMap())
            textsCache = loaded
            return loaded
        }
    }

    internal fun parseLayoutsJson(raw: String): Map<String, FutoLayoutDef> {
        val root = JSONObject(raw)
        val out = LinkedHashMap<String, FutoLayoutDef>(root.length())
        val keys = root.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val obj = root.optJSONObject(id) ?: continue
            val rowsArr = obj.optJSONArray("rows") ?: JSONArray()
            val rows = ArrayList<FutoRowSpec>(rowsArr.length())
            for (i in 0 until rowsArr.length()) {
                val rObj = rowsArr.optJSONObject(i) ?: continue
                val type = rObj.optString("type", "letters")
                val keysArr = rObj.optJSONArray("keys") ?: JSONArray()
                val kList = ArrayList<FutoKeySpec>(keysArr.length())
                for (j in 0 until keysArr.length()) {
                    val kObj = keysArr.optJSONObject(j) ?: continue
                    kList.add(
                        FutoKeySpec(
                            kind = kObj.optString("kind", "key"),
                            label = kObj.optString("label", ""),
                            output = kObj.optString("output", ""),
                            shiftable = kObj.optBoolean("shiftable", true),
                            moreKeys = kObj.optJSONArray("moreKeys").toStringList(),
                            shiftedLabel = kObj.optString("shiftedLabel", "").takeIf { it.isNotEmpty() },
                            shiftedOutput = kObj.optString("shiftedOutput", "").takeIf { it.isNotEmpty() },
                            shiftedMoreKeys = kObj.optJSONArray("shiftedMoreKeys").toStringList(),
                            hint = kObj.optString("hint", "").takeIf { it.isNotEmpty() },
                            noBg = kObj.optBoolean("noBg", false),
                        )
                    )
                }
                rows.add(FutoRowSpec(type, kList))
            }
            out[id] = FutoLayoutDef(
                id = id,
                name = obj.optString("name", id),
                autoShift = obj.optBoolean("autoShift", true),
                shiftable = obj.optBoolean("shiftable", true),
                numberRowMode = obj.optString("numberRowMode", "UserConfigurable"),
                rows = rows,
            )
        }
        return out
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null || length() == 0) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }
}
