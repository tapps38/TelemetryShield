import dev.telemetryshield.DnsPacket
import dev.telemetryshield.DomainMatcher

fun check(cond: Boolean, msg: String) { if (!cond) throw AssertionError(msg) else println("ok  $msg") }

fun dnsQuery(name: String, qtype: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    out.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 1)) // RD, 1 question, 1 additional (EDNS)
    for (label in name.split('.')) { out.write(label.length); out.write(label.toByteArray()) }
    out.write(0)
    out.write(qtype shr 8); out.write(qtype and 0xFF); out.write(0); out.write(1)
    out.write(byteArrayOf(0, 0, 41, 4, 0xD0.toByte(), 0, 0, 0, 0, 0, 0)) // OPT record
    return out.toByteArray()
}

fun main() {
    // --- domain parsing / matching
    check(DomainMatcher.parseLine("0.0.0.0 app-measurement.com # firebase") == listOf("app-measurement.com"), "hosts line")
    check(DomainMatcher.parseLine("127.0.0.1 a.com b.net") == listOf("a.com", "b.net"), "multi-domain hosts line")
    check(DomainMatcher.parseLine("||tracking.miui.com^") == listOf("tracking.miui.com"), "adblock rule")
    check(DomainMatcher.parseLine("ads.example.org") == listOf("ads.example.org"), "plain domain")
    check(DomainMatcher.parseLine("127.0.0.1 localhost").isEmpty(), "skip localhost")
    check(DomainMatcher.parseLine("! comment").isEmpty() && DomainMatcher.parseLine("# c").isEmpty(), "comments")
    check(DomainMatcher.parseLine("::1 ip6-localhost").isEmpty(), "skip ipv6 localhost")
    check(DomainMatcher.parseLine("1.2.3.4").isEmpty(), "skip bare IP")
    check(DomainMatcher.parseLine("com").isEmpty(), "skip bare TLD")
    check(DomainMatcher.parseLine("*.metric.account-samsung.com") == listOf("metric.account-samsung.com"), "wildcard line")
    check(DomainMatcher.parseLine("a.*.example.com").isEmpty(), "mid wildcard skipped")

    val m = DomainMatcher(setOf("app-measurement.com", "events.data.microsoft.com"), setOf("ok.events.data.microsoft.com"))
    check(m.isBlocked("app-measurement.com"), "exact block")
    check(m.isBlocked("Region1.App-Measurement.com."), "subdomain + case + trailing dot")
    check(!m.isBlocked("notapp-measurement.com"), "no partial-label match")
    check(m.isBlocked("mobile.events.data.microsoft.com"), "deep subdomain")
    check(!m.isBlocked("ok.events.data.microsoft.com"), "allowlist wins")
    check(!m.isBlocked("google.com"), "unlisted passes")

    // --- packet round trip
    val dns = dnsQuery("Region1.App-Measurement.com", DnsPacket.TYPE_A)
    val src = byteArrayOf(10, 111, 222.toByte(), 1); val dst = byteArrayOf(10, 111, 222.toByte(), 2)
    val pkt = DnsPacket.buildIpv4Udp(src, dst, 40000, 53, dns)
    check(DnsPacket.ipChecksum(pkt, 0, 20) == 0, "IP header checksum validates")
    val q = DnsPacket.parseIpv4UdpDns(pkt, pkt.size)!!
    check(q.name == "region1.app-measurement.com" && q.qtype == 1 && q.srcPort == 40000, "parse query from packet")
    check(DnsPacket.parseIpv4UdpDns(pkt.copyOf(pkt.size + 50), pkt.size) != null, "buffer larger than packet")

    val resp = DnsPacket.buildBlockedResponse(q)
    check(resp[0] == 0x12.toByte() && resp[1] == 0x34.toByte(), "response keeps ID")
    check(resp[2].toInt() and 0x80 != 0 && resp[2].toInt() and 1 == 1, "QR set, RD kept")
    check(DnsPacket.u16(resp, 6) == 1 && DnsPacket.u16(resp, 10) == 0, "one answer, no additional")
    val rdata = resp.copyOfRange(resp.size - 4, resp.size)
    check(rdata.all { it == 0.toByte() } && DnsPacket.u16(resp, resp.size - 6) == 4, "A answer is 0.0.0.0")

    val q6 = DnsPacket.parseIpv4UdpDns(DnsPacket.buildIpv4Udp(src, dst, 1, 53, dnsQuery("x.com", 28)), 61)!!
    check(DnsPacket.buildBlockedResponse(q6).size == 12 + q6.questionEnd - 12 + 12 + 16, "AAAA answer is ::")
    val qh = DnsPacket.parseIpv4UdpDns(DnsPacket.buildIpv4Udp(src, dst, 1, 53, dnsQuery("x.com", 65)), 61)!!
    check(DnsPacket.u16(DnsPacket.buildBlockedResponse(qh), 6) == 0, "HTTPS type gets empty NOERROR")

    // the response must itself be parseable as DNS by a real decoder; check with question parse after flipping QR
    val flipped = resp.copyOf(); flipped[2] = (flipped[2].toInt() and 0x7F).toByte()
    check(DnsPacket.parseQuestion(flipped)!!.name == "region1.app-measurement.com", "response question intact")

    check(DnsPacket.parseIpv4UdpDns(DnsPacket.buildIpv4Udp(src, dst, 1, 443, dns), 28 + dns.size) == null, "non-53 ignored")
    check(DnsPacket.parseIpv4UdpDns(ByteArray(10), 10) == null, "short packet ignored")
    println("ALL PASSED")
}
