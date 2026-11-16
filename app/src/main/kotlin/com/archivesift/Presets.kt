package com.archivesift

import android.content.Context
import android.net.Uri
import com.archivesift.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

private const val PRESET_LIMIT = 128L * 1024
const val MAX_RULES = 32
fun encodePreset(name: String, rules: List<Rule>): String = JSONObject().put("version", 1).put("name", name)
    .put("rules", JSONArray().also { array -> rules.forEach {
        array.put(JSONObject().put("exclude", it.exclude).put("mode", it.mode.name).put("pattern", it.text))
    } }).toString(2)

fun decodePreset(text: String): Pair<String, List<Rule>> {
    val obj = JSONObject(text)
    require(obj.getInt("version") == 1) { "Unsupported preset version." }
    val name = obj.getString("name").trim()
    require(name.isNotEmpty() && name.length <= 60 && name.none { it.isISOControl() }) { "Preset name must contain 1–60 characters." }
    val array = obj.getJSONArray("rules")
    require(array.length() <= MAX_RULES) { "Too many rules in preset." }
    val rules = (0 until array.length()).map { i ->
        val rule = array.getJSONObject(i)
        Rule(rule.getBoolean("exclude"), Mode.valueOf(rule.getString("mode")), rule.getString("pattern"))
    }
    require(compileRules(rules).errors.isEmpty()) { "Preset contains invalid rules." }
    return name to rules
}

class Presets(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("rule_presets", Context.MODE_PRIVATE)
    fun names() = prefs.all.keys.sorted()
    fun save(name: String, rules: List<Rule>) {
        val text = encodePreset(name, rules)
        val (clean, _) = decodePreset(text)
        require(!prefs.contains(clean) && names().size < 64) { "Preset name already exists or the preset limit was reached." }
        if (!prefs.edit().putString(clean, text).commit()) throw IOException("Could not save the preset.")
    }
    fun load(name: String): List<Rule> = decodePreset(prefs.getString(name, null) ?: error("Preset unavailable.")).second
    fun remove(name: String) { if (!prefs.edit().remove(name).commit()) throw IOException("Could not remove the preset.") }
    fun read(uri: Uri, check: () -> Unit): Pair<String, List<Rule>> {
        val out = ByteArrayOutputStream()
        (ctx.contentResolver.openInputStream(uri) ?: throw IOException("Could not read the preset.")).use {
            copyBounded(it, out, ByteBudget(PRESET_LIMIT), PRESET_LIMIT, check)
        }
        return decodePreset(out.toString("UTF-8"))
    }
}
