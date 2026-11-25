package com.safespace.guard

import android.content.Context

/**
 * Decides whether a domain should be blocked.
 * Order: allowlist (recovery/education sites) -> domain list (with parent domains) -> keywords.
 */
class Blocklist(context: Context) {

    private val blocked = HashSet<String>()
    private val allowed = HashSet<String>()

    // Only unambiguous terms, to avoid false positives like "essex" or "sussex".
    private val keywords = listOf(
        "porn", "xvideos", "xnxx", "xhamster", "redtube", "youporn",
        "hentai", "brazzers", "spankbang", "camgirl", "livejasmin"
    )

    init {
        load(context, "blocklist.txt", blocked)
        load(context, "allowlist.txt", allowed)
    }

    private fun load(context: Context, asset: String, into: HashSet<String>) {
        try {
            context.assets.open(asset).bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.substringBefore('#').trim().lowercase()
                    if (line.isEmpty()) continue
                    val parts = line.split(Regex("\\s+"))
                    // Supports plain "domain" lines and hosts-file "0.0.0.0 domain" lines.
                    val domain = if (parts.size >= 2) parts[1] else parts[0]
                    if (domain == "localhost" || domain == "0.0.0.0" || !domain.contains('.')) continue
                    into.add(domain)
                }
            }
        } catch (e: Exception) {
            // Missing asset: keep going with whatever is loaded.
        }
    }

    fun size(): Int = blocked.size

    fun isBlocked(rawName: String): Boolean {
        val name = rawName.trimEnd('.').lowercase()
        if (name.isEmpty()) return false
        if (matchesSuffix(name, allowed)) return false
        if (matchesSuffix(name, blocked)) return true
        return keywords.any { name.contains(it) }
    }

    /** True if the name or any of its parent domains is in the set. */
    private fun matchesSuffix(name: String, set: HashSet<String>): Boolean {
        var d = name
        while (true) {
            if (set.contains(d)) return true
            val dot = d.indexOf('.')
            if (dot < 0 || dot == d.length - 1) return false
            d = d.substring(dot + 1)
        }
    }
}
