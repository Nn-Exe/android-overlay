package com.nnexe.overlaybrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One search provider. [template] must contain the {query} placeholder. */
data class SearchEngine(
    val id: String,
    val name: String,
    val template: String,
    val builtin: Boolean,
)

/** Built-in providers and template validation. No Android state, so it is easy to unit test. */
object SearchEngineCatalog {
    const val PLACEHOLDER = "{query}"

    // Ids of the first three match the names older versions stored, so saved choices keep working.
    const val ID_BRAVE = "Brave Search"
    const val ID_GOOGLE = "Google"
    const val ID_DDG = "DuckDuckGo"
    const val ID_BING = "Bing"
    const val ID_STARTPAGE = "Startpage"

    val BUILTIN: List<SearchEngine> = listOf(
        SearchEngine(ID_BRAVE, "Brave Search", "https://search.brave.com/search?q={query}", true),
        SearchEngine(ID_GOOGLE, "Google", "https://www.google.com/search?q={query}", true),
        SearchEngine(ID_DDG, "DuckDuckGo", "https://duckduckgo.com/?q={query}", true),
        SearchEngine(ID_BING, "Bing", "https://www.bing.com/search?q={query}", true),
        SearchEngine(ID_STARTPAGE, "Startpage", "https://www.startpage.com/do/search?q={query}", true),
    )

    fun isValidTemplate(template: String): Boolean {
        val t = template.trim()
        return (t.startsWith("https://") || t.startsWith("http://")) && t.contains(PLACEHOLDER)
    }

    fun builtinTemplate(id: String): String? = BUILTIN.firstOrNull { it.id == id }?.template
}

/**
 * The single place that knows which search engines exist, their order and the default.
 * Built-ins cannot be edited or deleted; custom engines can.
 */
class SearchEngineManager(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("overlay", Context.MODE_PRIVATE)

    enum class Error { NAME, URL, NOT_EDITABLE }

    var defaultId: String
        get() {
            val saved = sp.getString(KEY_DEFAULT, null) ?: sp.getString(LEGACY_ENGINE, null)
            return all().firstOrNull { it.id == saved }?.id ?: SearchEngineCatalog.ID_BRAVE
        }
        set(value) {
            sp.edit().putString(KEY_DEFAULT, value).apply()
        }

    fun default(): SearchEngine {
        val id = defaultId
        return engines().firstOrNull { it.id == id } ?: SearchEngineCatalog.BUILTIN.first()
    }

    /** All engines in the user's chosen order. */
    fun engines(): List<SearchEngine> {
        val all = all()
        val byId = all.associateBy { it.id }
        val ordered = loadOrder().mapNotNull { byId[it] }.distinctBy { it.id }
        val seen = ordered.map { it.id }.toSet()
        return ordered + all.filter { it.id !in seen }
    }

    fun add(name: String, template: String): Error? {
        val n = name.trim()
        val t = template.trim()
        if (n.isEmpty()) return Error.NAME
        if (!SearchEngineCatalog.isValidTemplate(t)) return Error.URL
        val user = loadUser().toMutableList()
        var id = "u_${System.currentTimeMillis()}"
        while (user.any { it.id == id }) id += "_"
        user.add(SearchEngine(id, n, t, false))
        saveUser(user)
        return null
    }

    fun update(id: String, name: String, template: String): Error? {
        val n = name.trim()
        val t = template.trim()
        val user = loadUser().toMutableList()
        val index = user.indexOfFirst { it.id == id }
        if (index < 0) return Error.NOT_EDITABLE
        if (n.isEmpty()) return Error.NAME
        if (!SearchEngineCatalog.isValidTemplate(t)) return Error.URL
        user[index] = SearchEngine(id, n, t, false)
        saveUser(user)
        return null
    }

    /** Deletes a custom engine. Returns false for built-ins or unknown ids. */
    fun delete(id: String): Boolean {
        val user = loadUser()
        if (user.none { it.id == id }) return false
        val wasDefault = defaultId == id
        saveUser(user.filterNot { it.id == id })
        saveOrder(loadOrder().filterNot { it == id })
        if (wasDefault) defaultId = SearchEngineCatalog.ID_BRAVE
        return true
    }

    /** Moves an engine up (negative delta) or down (positive delta) in the list. */
    fun move(id: String, delta: Int) {
        val ids = engines().map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        if (to == from) return
        ids.add(to, ids.removeAt(from))
        saveOrder(ids)
    }

    /** Keeps the old single "custom search URL" setting working. */
    fun setLegacyCustom(template: String) {
        if (!SearchEngineCatalog.isValidTemplate(template)) return
        val user = loadUser().toMutableList()
        val entry = SearchEngine(LEGACY_CUSTOM_ID, "Custom", template.trim(), false)
        val index = user.indexOfFirst { it.id == LEGACY_CUSTOM_ID }
        if (index >= 0) user[index] = entry else user.add(entry)
        saveUser(user)
    }

    private fun all(): List<SearchEngine> = SearchEngineCatalog.BUILTIN + loadUser()

    private fun loadUser(): List<SearchEngine> {
        if (!sp.contains(KEY_USER)) migrateLegacy()
        val raw = sp.getString(KEY_USER, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val name = o.optString("name")
                val template = o.optString("template")
                if (id.isBlank() || name.isBlank() || !SearchEngineCatalog.isValidTemplate(template)) null
                else SearchEngine(id, name, template, false)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun migrateLegacy() {
        val array = JSONArray()
        val legacyTemplate = sp.getString(LEGACY_CUSTOM_URL, null)
        if (sp.getString(LEGACY_ENGINE, null) == LEGACY_CUSTOM_ID &&
            legacyTemplate != null &&
            SearchEngineCatalog.isValidTemplate(legacyTemplate)
        ) {
            array.put(
                JSONObject().put("id", LEGACY_CUSTOM_ID).put("name", "Custom").put("template", legacyTemplate.trim()),
            )
        }
        sp.edit().putString(KEY_USER, array.toString()).apply()
    }

    private fun saveUser(list: List<SearchEngine>) {
        val array = JSONArray()
        list.forEach { e ->
            array.put(JSONObject().put("id", e.id).put("name", e.name).put("template", e.template))
        }
        sp.edit().putString(KEY_USER, array.toString()).apply()
    }

    private fun loadOrder(): List<String> {
        val raw = sp.getString(KEY_ORDER, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveOrder(ids: List<String>) {
        val array = JSONArray()
        ids.forEach { array.put(it) }
        sp.edit().putString(KEY_ORDER, array.toString()).apply()
    }

    private companion object {
        const val KEY_USER = "engines_user"
        const val KEY_ORDER = "engines_order"
        const val KEY_DEFAULT = "engine_default"
        const val LEGACY_ENGINE = "search_engine"
        const val LEGACY_CUSTOM_URL = "custom_search_url"
        const val LEGACY_CUSTOM_ID = "Custom"
    }
}
