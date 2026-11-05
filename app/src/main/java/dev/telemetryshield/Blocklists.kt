package dev.telemetryshield

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Loads, downloads and stores block lists and the allowlist. */
object Blocklists {

    private const val BASE_ASSET = "telemetry_base.txt"
    private const val LOCATION_ASSET = "location_sdks.txt"
    private const val KEY_BLOCK_LOCATION = "block_location_sdks"
    private const val LEGACY_DOWNLOADED_FILE = "downloaded_blocklist.txt"
    private const val KEY_URLS = "list_urls"
    private const val KEY_PRESETS = "enabled_presets"
    private const val KEY_ALLOW = "allowlist"
    private const val KEY_UPSTREAM = "upstream_dns"
    private const val KEY_LAST_UPDATE = "last_update"
    private const val KEY_AUTO_UPDATE = "auto_update"

    /** A built-in, switchable list: either downloaded from [url] or bundled as [asset]. */
    class Preset(val id: String, val label: String, val url: String? = null, val asset: String? = null)

    private fun hagezi(name: String) =
        "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/native.$name-onlydomains.txt"

    val PRESETS = listOf(
        Preset("samsung", "Samsung", url = hagezi("samsung")),
        Preset("apple", "Apple (Apple Music, Apple TV, iCloud apps on Android)", url = hagezi("apple")),
        Preset("motorola", "Motorola / Lenovo", asset = "motorola.txt"),
        Preset("xiaomi", "Xiaomi", url = hagezi("xiaomi")),
        Preset("huawei", "Huawei", url = hagezi("huawei")),
        Preset("oppo-realme", "OPPO / Realme", url = hagezi("oppo-realme")),
        Preset("vivo", "Vivo", url = hagezi("vivo")),
        Preset("amazon", "Amazon", url = hagezi("amazon")),
        Preset("winoffice", "Microsoft", url = hagezi("winoffice")),
        Preset("tiktok", "TikTok", url = hagezi("tiktok")),
    )

    /** Old per-vendor HaGeZi URLs saved by earlier versions; now covered by [PRESETS]. */
    private val LEGACY_PRESET_URL = Regex("hagezi/dns-blocklists.*/native\\.")

    @Volatile var matcher = DomainMatcher(emptySet(), emptySet())
        private set

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("shield", Context.MODE_PRIVATE)

    private fun listsDir(ctx: Context) = File(ctx.filesDir, "lists").apply { mkdirs() }

    private fun fileFor(ctx: Context, url: String) =
        File(listsDir(ctx), "u" + Integer.toHexString(url.hashCode()) + ".txt")

    // ---------- presets ----------

    fun enabledPresets(ctx: Context): Set<String> =
        HashSet(prefs(ctx).getStringSet(KEY_PRESETS, null) ?: PRESETS.map { it.id }.toSet())

    fun setPresetEnabled(ctx: Context, id: String, on: Boolean) {
        val set = enabledPresets(ctx).toMutableSet()
        if (on) set.add(id) else set.remove(id)
        prefs(ctx).edit().putStringSet(KEY_PRESETS, set).apply()
        reload(ctx)
    }

    /** True if a switched-on downloadable preset hasn't been downloaded yet. */
    fun needsDownload(ctx: Context): Boolean {
        val on = enabledPresets(ctx)
        return PRESETS.any { it.id in on && it.url != null && !fileFor(ctx, it.url).exists() } ||
            customUrls(ctx).any { !fileFor(ctx, it).exists() }
    }

    // ---------- custom lists ----------

    fun customUrls(ctx: Context): List<String> =
        (prefs(ctx).getString(KEY_URLS, null) ?: "").lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !LEGACY_PRESET_URL.containsMatchIn(it) }

    fun setCustomUrls(ctx: Context, urls: List<String>) =
        prefs(ctx).edit().putString(KEY_URLS, urls.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")).apply()

    // ---------- allowlist / settings ----------

    fun allowlist(ctx: Context): Set<String> =
        HashSet(prefs(ctx).getStringSet(KEY_ALLOW, emptySet()) ?: emptySet())

    fun setAllowlist(ctx: Context, domains: Collection<String>) {
        val clean = domains.flatMap { DomainMatcher.parseLine(it) }.toSet()
        prefs(ctx).edit().putStringSet(KEY_ALLOW, clean).apply()
        matcher = matcher.withAllowed(clean)
    }

    fun addAllowed(ctx: Context, domain: String) = setAllowlist(ctx, allowlist(ctx) + domain)

    fun upstream(ctx: Context): String = prefs(ctx).getString(KEY_UPSTREAM, "") ?: ""

    fun setUpstream(ctx: Context, value: String) = prefs(ctx).edit().putString(KEY_UPSTREAM, value.trim()).apply()

    fun blockLocationSdks(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BLOCK_LOCATION, true)

    fun setBlockLocationSdks(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_BLOCK_LOCATION, on).apply()
        reload(ctx)
    }

    fun autoUpdate(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO_UPDATE, true)

    fun setAutoUpdate(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_AUTO_UPDATE, on).apply()

    fun lastUpdate(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_UPDATE, 0)

    // ---------- loading ----------

    private fun addAsset(ctx: Context, name: String, into: MutableSet<String>) {
        ctx.assets.open(name).bufferedReader().useLines { lines ->
            lines.forEach { into.addAll(DomainMatcher.parseLine(it)) }
        }
    }

    private fun addFile(file: File, into: MutableSet<String>) {
        if (!file.exists()) return
        file.bufferedReader().useLines { lines -> lines.forEach { into.addAll(DomainMatcher.parseLine(it)) } }
    }

    /** Rebuilds the matcher from the built-in lists, switched-on presets, custom lists and the allowlist. */
    @Synchronized
    fun reload(ctx: Context): Int {
        File(ctx.filesDir, LEGACY_DOWNLOADED_FILE).delete()
        val blocked = HashSet<String>(4096)
        addAsset(ctx, BASE_ASSET, blocked)
        if (blockLocationSdks(ctx)) addAsset(ctx, LOCATION_ASSET, blocked)
        val on = enabledPresets(ctx)
        for (p in PRESETS) {
            if (p.id !in on) continue
            p.asset?.let { addAsset(ctx, it, blocked) }
            p.url?.let { addFile(fileFor(ctx, it), blocked) }
        }
        for (u in customUrls(ctx)) addFile(fileFor(ctx, u), blocked)
        matcher = DomainMatcher(blocked, allowlist(ctx))
        return blocked.size
    }

    fun ensureLoaded(ctx: Context) {
        if (matcher.size == 0) reload(ctx)
    }

    /** Downloads every switched-on preset and custom list. Blocking — call off the main thread. */
    @Synchronized
    fun download(ctx: Context): String {
        val on = enabledPresets(ctx)
        val urls = PRESETS.filter { it.id in on }.mapNotNull { it.url } + customUrls(ctx)
        var ok = 0
        val failures = mutableListOf<String>()
        for (u in urls) {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "TelemetryShield")
                try {
                    if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode}")
                    val domains = LinkedHashSet<String>()
                    conn.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { domains.addAll(DomainMatcher.parseLine(it)) }
                    }
                    if (domains.isEmpty()) throw RuntimeException("no domains found")
                    val tmp = File(listsDir(ctx), "download.tmp")
                    tmp.bufferedWriter().use { w -> domains.forEach { w.write(it); w.newLine() } }
                    tmp.renameTo(fileFor(ctx, u))
                    ok++
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                failures += "${u.substringAfterLast('/')}: ${e.message}"
            }
        }
        if (ok > 0) prefs(ctx).edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
        val total = reload(ctx)
        val msg = if (urls.isEmpty()) "No downloadable lists are switched on. $total domains blocked."
            else "Updated $ok/${urls.size} lists. $total domains blocked in total."
        return if (failures.isEmpty()) msg else msg + "\n\nFailed (kept previous copy):\n" + failures.joinToString("\n")
    }
}
