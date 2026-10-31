package dev.telemetryshield

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Loads, downloads and stores block lists and the allowlist. */
object Blocklists {

    private const val BASE_ASSET = "telemetry_base.txt"
    private const val DOWNLOADED_FILE = "downloaded_blocklist.txt"
    private const val KEY_URLS = "list_urls"
    private const val KEY_ALLOW = "allowlist"
    private const val KEY_UPSTREAM = "upstream_dns"
    private const val KEY_LAST_UPDATE = "last_update"

    /** HaGeZi "native tracker" lists: vendor/OS telemetry only, no general ad blocking. */
    val DEFAULT_URLS = listOf(
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/native.samsung.txt",
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/native.xiaomi.txt",
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/native.huawei.txt",
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/native.oppo-realme.txt",
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/native.vivo.txt",
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/native.tiktok.txt",
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/native.amazon.txt",
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/native.winoffice.txt",
    )

    @Volatile var matcher = DomainMatcher(emptySet(), emptySet())
        private set

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("shield", Context.MODE_PRIVATE)

    fun urls(ctx: Context): List<String> =
        prefs(ctx).getString(KEY_URLS, null)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: DEFAULT_URLS

    fun setUrls(ctx: Context, urls: List<String>) =
        prefs(ctx).edit().putString(KEY_URLS, urls.joinToString("\n")).apply()

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

    fun lastUpdate(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_UPDATE, 0)

    /** Rebuilds the matcher from the bundled list, the downloaded lists and the allowlist. */
    @Synchronized
    fun reload(ctx: Context): Int {
        val blocked = HashSet<String>(4096)
        ctx.assets.open(BASE_ASSET).bufferedReader().useLines { lines ->
            lines.forEach { blocked.addAll(DomainMatcher.parseLine(it)) }
        }
        val downloaded = File(ctx.filesDir, DOWNLOADED_FILE)
        if (downloaded.exists()) {
            downloaded.bufferedReader().useLines { lines ->
                lines.forEach { blocked.addAll(DomainMatcher.parseLine(it)) }
            }
        }
        matcher = DomainMatcher(blocked, allowlist(ctx))
        return blocked.size
    }

    fun ensureLoaded(ctx: Context) {
        if (matcher.size == 0) reload(ctx)
    }

    /** Downloads every configured list. Blocking — call off the main thread. Returns a status message. */
    fun download(ctx: Context): String {
        val all = LinkedHashSet<String>()
        val urls = urls(ctx)
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
                    conn.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { all.addAll(DomainMatcher.parseLine(it)) }
                    }
                    ok++
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                failures += "${u.substringAfterLast('/')}: ${e.message}"
            }
        }
        if (ok == 0 && urls.isNotEmpty()) {
            return "Update failed, kept previous lists.\n" + failures.joinToString("\n")
        }
        val tmp = File(ctx.filesDir, "$DOWNLOADED_FILE.tmp")
        tmp.bufferedWriter().use { w -> all.forEach { w.write(it); w.newLine() } }
        tmp.renameTo(File(ctx.filesDir, DOWNLOADED_FILE))
        prefs(ctx).edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
        val total = reload(ctx)
        val msg = "Loaded $ok/${urls.size} lists. $total domains blocked in total."
        return if (failures.isEmpty()) msg else msg + "\nFailed:\n" + failures.joinToString("\n")
    }
}
