package dev.telemetryshield

/**
 * Suffix-based domain matching: blocking "example.com" also blocks "a.b.example.com".
 * Allowlist entries win over blocklist entries.
 */
class DomainMatcher(val blocked: Set<String>, val allowed: Set<String>) {

    val size: Int get() = blocked.size

    fun isBlocked(name: String): Boolean {
        val d = name.trimEnd('.').lowercase()
        if (d.isEmpty()) return false
        if (matches(allowed, d)) return false
        return matches(blocked, d)
    }

    fun withAllowed(newAllowed: Set<String>) = DomainMatcher(blocked, newAllowed)

    private fun matches(set: Set<String>, domain: String): Boolean {
        if (set.isEmpty()) return false
        var cur = domain
        while (true) {
            if (cur in set) return true
            val dot = cur.indexOf('.')
            if (dot < 0) return false
            cur = cur.substring(dot + 1)
        }
    }

    companion object {
        private val IGNORED = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback")
        private val VALID = Regex("^[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?(\\.[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?)+$")
        private val IP_LIKE = Regex("^[0-9.]+$|:")

        /**
         * Parses one line of a hosts file ("0.0.0.0 a.com"), a plain domain list ("a.com"),
         * or a simple Adblock-style rule ("||a.com^"). Returns the domains on the line.
         */
        fun parseLine(raw: String): List<String> {
            var line = raw
            val hash = line.indexOf('#')
            if (hash >= 0) line = line.substring(0, hash)
            line = line.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[") || line.startsWith("@@")) return emptyList()

            if (line.startsWith("||")) {
                var d = line.substring(2)
                val cut = d.indexOfFirst { it == '^' || it == '$' || it == '/' }
                if (cut >= 0) d = d.substring(0, cut)
                return listOfNotNull(clean(d))
            }

            val tokens = line.split(Regex("\\s+"))
            val candidates = if (tokens.size >= 2 && IP_LIKE.containsMatchIn(tokens[0])) tokens.drop(1) else tokens.take(1)
            return candidates.mapNotNull { clean(it) }
        }

        private fun clean(token: String): String? {
            val d = token.trim().trimEnd('.').lowercase()
            if (d.isEmpty() || d in IGNORED || '*' in d) return null
            if (IP_LIKE.containsMatchIn(d)) return null
            return if (VALID.matches(d)) d else null
        }
    }
}
