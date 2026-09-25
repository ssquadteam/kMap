package com.github.ssquadteam.kmap.lang

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class Lang(private val plugin: JavaPlugin, private val fallback: String) {
    private val tables = HashMap<String, Map<String, String>>()

    fun load() {
        tables.clear()
        for (code in CODES) {
            val res = plugin.getResource("lang/$code.yml") ?: continue
            val y = YamlConfiguration.loadConfiguration(res.reader(Charsets.UTF_8))
            val disk = File(plugin.dataFolder, "lang/$code.yml")
            val merged = HashMap<String, String>()
            for (k in y.getKeys(true)) if (!y.isConfigurationSection(k)) merged[k] = y.getString(k) ?: ""
            if (disk.isFile) {
                val d = YamlConfiguration.loadConfiguration(disk)
                for (k in d.getKeys(true)) if (!d.isConfigurationSection(k)) merged[k] = d.getString(k) ?: ""
            }
            tables[code] = merged
        }
    }

    fun code(player: Player?): String {
        if (player == null) return fallback
        val loc = player.locale().toString().lowercase()
        val exact = loc.replace('-', '_')
        if (tables.containsKey(exact)) return exact
        val short = exact.substringBefore('_')
        return if (tables.containsKey(short)) short else fallback
    }

    fun get(player: Player?, key: String, vararg args: Any): String {
        val table = tables[code(player)] ?: tables[fallback] ?: tables["en"] ?: emptyMap()
        var s = table[key] ?: tables["en"]?.get(key) ?: key
        for ((i, a) in args.withIndex()) s = s.replace("{$i}", a.toString())
        return s
    }

    companion object {
        val CODES = listOf("en", "ru", "uk", "de", "nl", "fr", "es", "pt_br", "it", "pl", "tr", "cs", "sv")
    }
}
