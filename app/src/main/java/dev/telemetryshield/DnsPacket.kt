package dev.telemetryshield

/**
 * Minimal IPv4 / UDP / DNS packet handling for the DNS-only tunnel.
 * Pure Kotlin (no Android APIs) so it can be unit tested on a plain JVM.
 */
object DnsPacket {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    /** A DNS query pulled out of an IPv4/UDP packet read from the tun interface. */
    class Query(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val dns: ByteArray,      // raw DNS message (UDP payload)
        val name: String,        // lower-case query name, no trailing dot
        val qtype: Int,
        val questionEnd: Int,    // offset in [dns] just past the first question
    )

    class Question(val name: String, val qtype: Int, val end: Int)

    /** Returns the DNS query in [packet], or null if it isn't an IPv4 UDP packet to port 53. */
    fun parseIpv4UdpDns(packet: ByteArray, length: Int): Query? {
        if (length < 28) return null
        if ((packet[0].toInt() ushr 4) and 0xF != 4) return null
        val ihl = (packet[0].toInt() and 0xF) * 4
        if (ihl < 20 || length < ihl + 8) return null
        if (packet[9].toInt() and 0xFF != 17) return null           // UDP only
        if (u16(packet, 6) and 0x3FFF != 0) return null              // ignore fragments
        val totalLen = u16(packet, 2)
        val end = if (totalLen in (ihl + 8)..length) totalLen else length

        val srcPort = u16(packet, ihl)
        val dstPort = u16(packet, ihl + 2)
        if (dstPort != 53) return null
        val udpLen = u16(packet, ihl + 4)
        val payloadStart = ihl + 8
        val payloadEnd = if (udpLen >= 8) minOf(end, ihl + udpLen) else end
        if (payloadEnd - payloadStart < 12) return null

        val dns = packet.copyOfRange(payloadStart, payloadEnd)
        val q = parseQuestion(dns) ?: return null
        return Query(
            srcIp = packet.copyOfRange(12, 16),
            dstIp = packet.copyOfRange(16, 20),
            srcPort = srcPort,
            dstPort = dstPort,
            dns = dns,
            name = q.name,
            qtype = q.qtype,
            questionEnd = q.end,
        )
    }

    /** Parses the first question of a DNS query message. */
    fun parseQuestion(dns: ByteArray): Question? {
        if (dns.size < 12) return null
        if (dns[2].toInt() and 0x80 != 0) return null                // must be a query (QR=0)
        if (u16(dns, 4) < 1) return null                            // QDCOUNT
        val sb = StringBuilder()
        var off = 12
        while (true) {
            if (off >= dns.size) return null
            val len = dns[off].toInt() and 0xFF
            if (len == 0) { off++; break }
            if (len and 0xC0 != 0) return null                       // no compression in questions
            if (off + 1 + len > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 1..len) sb.append(Char(dns[off + i].toInt() and 0xFF))
            off += 1 + len
            if (sb.length > 253) return null
        }
        if (off + 4 > dns.size) return null
        val qtype = u16(dns, off)
        return Question(sb.toString().lowercase(), qtype, off + 4)
    }

    /**
     * Builds the answer for a blocked name:
     *  A → 0.0.0.0, AAAA → ::, anything else → NOERROR with no records.
     */
    fun buildBlockedResponse(q: Query): ByteArray {
        val question = q.dns.copyOfRange(12, q.questionEnd)
        val answer: ByteArray = when (q.qtype) {
            TYPE_A -> answerRecord(TYPE_A, ByteArray(4))
            TYPE_AAAA -> answerRecord(TYPE_AAAA, ByteArray(16))
            else -> ByteArray(0)
        }
        val out = ByteArray(12 + question.size + answer.size)
        out[0] = q.dns[0]; out[1] = q.dns[1]                         // ID
        out[2] = (0x80 or (q.dns[2].toInt() and 0x79)).toByte()     // QR=1, keep opcode + RD
        out[3] = 0x80.toByte()                                       // RA=1, RCODE=0
        put16(out, 4, 1)                                             // QDCOUNT
        put16(out, 6, if (answer.isEmpty()) 0 else 1)                // ANCOUNT
        System.arraycopy(question, 0, out, 12, question.size)
        System.arraycopy(answer, 0, out, 12 + question.size, answer.size)
        return out
    }

    private fun answerRecord(type: Int, rdata: ByteArray): ByteArray {
        val r = ByteArray(12 + rdata.size)
        put16(r, 0, 0xC00C)                                          // pointer to question name
        put16(r, 2, type)
        put16(r, 4, 1)                                               // class IN
        put32(r, 6, 60)                                              // short TTL so allowlisting takes effect quickly
        put16(r, 10, rdata.size)
        System.arraycopy(rdata, 0, r, 12, rdata.size)
        return r
    }

    /** Wraps [payload] in UDP + IPv4 headers. */
    fun buildIpv4Udp(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        put16(p, 6, 0x4000)                                          // Don't Fragment
        p[8] = 64                                                    // TTL
        p[9] = 17                                                    // UDP
        System.arraycopy(srcIp, 0, p, 12, 4)
        System.arraycopy(dstIp, 0, p, 16, 4)
        put16(p, 10, ipChecksum(p, 0, 20))
        put16(p, 20, srcPort)
        put16(p, 22, dstPort)
        put16(p, 24, 8 + payload.size)
        // UDP checksum 0 = "not computed", which is valid for IPv4.
        System.arraycopy(payload, 0, p, 28, payload.size)
        return p
    }

    fun ipChecksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) { sum += u16(b, i); i += 2 }
        if (len % 2 == 1) sum += (b[off + len - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    fun u16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte(); b[off + 1] = v.toByte()
    }

    private fun put32(b: ByteArray, off: Int, v: Int) {
        put16(b, off, v ushr 16); put16(b, off + 2, v)
    }
}
