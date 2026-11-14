package com.nnexe.overlaybrowser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Everything the floating window remembers between launches. Sizes and positions are in pixels. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)
    private val engines = SearchEngineManager(context)

    var x: Int
        get() = sp.getInt("x", -1)
        set(v) = sp.edit().putInt("x", v).apply()
    var y: Int
        get() = sp.getInt("y", -1)
        set(v) = sp.edit().putInt("y", v).apply()
    var width: Int
        get() = sp.getInt("w", -1)
        set(v) = sp.edit().putInt("w", v).apply()
    var height: Int
        get() = sp.getInt("h", -1)
        set(v) = sp.edit().putInt("h", v).apply()

    var bubbleX: Int
        get() = sp.getInt("bx", -1)
        set(v) = sp.edit().putInt("bx", v).apply()
    var bubbleY: Int
        get() = sp.getInt("by", -1)
        set(v) = sp.edit().putInt("by", v).apply()

    var locked: Boolean
        get() = sp.getBoolean("locked", false)
        set(v) = sp.edit().putBoolean("locked", v).apply()
    var muted: Boolean
        get() = sp.getBoolean("muted", false)
        set(v) = sp.edit().putBoolean("muted", v).apply()
    var blockAds: Boolean
        get() = sp.getBoolean("block_ads", true)
        set(v) = sp.edit().putBoolean("block_ads", v).apply()
    var opacity: Float
        get() = sp.getFloat("opacity", 1f).coerceIn(MIN_OPACITY, 1f)
        set(v) = sp.edit().putFloat("opacity", v).apply()
    var desktopMode: Boolean
        get() = sp.getBoolean("desktop", false)
        set(v) = sp.edit().putBoolean("desktop", v).apply()
    var videoFillsScreen: Boolean
        get() = sp.getBoolean("video_full", false)
        set(v) = sp.edit().putBoolean("video_full", v).apply()

    var autoFitVideo: Boolean
        get() = sp.getBoolean("auto_fit_video", true)
        set(v) = sp.edit().putBoolean("auto_fit_video", v).apply()

    var videoX: Int
        get() = sp.getInt("vx", -1)
        set(v) = sp.edit().putInt("vx", v).apply()
    var videoY: Int
        get() = sp.getInt("vy", -1)
        set(v) = sp.edit().putInt("vy", v).apply()
    var videoArea: Int
        get() = sp.getInt("varea", -1)
        set(v) = sp.edit().putInt("varea", v).apply()

    var homeUrl: String
        get() = sp.getString("home", null) ?: DEFAULT_HOME
        set(v) = sp.edit().putString("home", v).apply()
    var lastUrl: String?
        get() = sp.getString("last", null)
        set(v) = sp.edit().putString("last", v).apply()

    /** Saved pages stored locally on this device. */
    fun bookmarks(): List<Pair<String, String>> {
        val raw = sp.getString("bookmarks", "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val url = item.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                item.optString("title", url) to url
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addBookmark(title: String, url: String) {
        val items = bookmarks().filterNot { it.second == url }.toMutableList()
        items.add(0, title.ifBlank { url } to url)
        val array = JSONArray()
        items.take(200).forEach { (name, link) ->
            array.put(JSONObject().put("title", name).put("url", link))
        }
        sp.edit().putString("bookmarks", array.toString()).apply()
    }

    fun removeBookmark(url: String) {
        val array = JSONArray()
        bookmarks().filterNot { it.second == url }.forEach { (name, link) ->
            array.put(JSONObject().put("title", name).put("url", link))
        }
        sp.edit().putString("bookmarks", array.toString()).apply()
    }

    /**
     * Id of the default search engine ("Brave Search", "Google", ... or a custom engine's id).
     * Backed by [SearchEngineManager]; kept here so existing callers keep working.
     */
    var searchEngine: String
        get() = engines.default().id
        set(v) {
            engines.defaultId = v
        }

    /** Search URL template of the default engine. Writing it updates the legacy "Custom" engine. */
    var customSearchUrl: String
        get() = engines.default().template
        set(v) = engines.setLegacyCustom(v)

    companion object {
        const val DEFAULT_HOME = "https://search.brave.com/"
        const val SEARCH_BRAVE = SearchEngineCatalog.ID_BRAVE
        const val SEARCH_GOOGLE = SearchEngineCatalog.ID_GOOGLE
        const val SEARCH_DDG = SearchEngineCatalog.ID_DDG
        const val SEARCH_BING = SearchEngineCatalog.ID_BING
        const val SEARCH_STARTPAGE = SearchEngineCatalog.ID_STARTPAGE
        const val SEARCH_CUSTOM = "Custom"
        const val DEFAULT_CUSTOM_SEARCH = "https://www.google.com/search?q={query}"

        const val MIN_OPACITY = 0.1f

        /**
         * Converts typed text to a URL using the selected search provider.
         * [engine] is a built-in id; for any other id the [customTemplate] is used.
         */
        fun toUrl(
            input: String,
            engine: String = SEARCH_BRAVE,
            customTemplate: String = DEFAULT_CUSTOM_SEARCH,
        ): String? {
            val t = input.trim()
            if (t.isEmpty()) return null
            val lower = t.lowercase()
            return when {
                lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("about:") -> t
                lower.startsWith("localhost") -> "http://$t"
                !t.contains(' ') && t.contains('.') && !t.endsWith('.') -> "https://$t"
                else -> {
                    val encoded = Uri.encode(t)
                    val template = SearchEngineCatalog.builtinTemplate(engine)
                        ?: customTemplate.takeIf { SearchEngineCatalog.isValidTemplate(it) }
                        ?: SearchEngineCatalog.BUILTIN.first().template
                    template.replace(SearchEngineCatalog.PLACEHOLDER, encoded)
                }
            }
        }
    }
}
