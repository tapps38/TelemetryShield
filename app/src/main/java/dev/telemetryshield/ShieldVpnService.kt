package dev.telemetryshield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A local, DNS-only VPN. Only traffic to the fake DNS server address is routed into the
 * tunnel; everything else goes straight out on Wi-Fi / mobile data as normal. Each DNS
 * query is checked against the block list: blocked names get 0.0.0.0 / ::, everything
 * else is forwarded to the current network's own DNS servers (or a custom upstream).
 */
class ShieldVpnService : VpnService() {

    companion object {
        const val ACTION_START = "dev.telemetryshield.START"
        const val ACTION_STOP = "dev.telemetryshield.STOP"
        private const val TAG = "TelemetryShield"
        private const val VPN_ADDRESS = "10.111.222.1"
        private const val DNS_ADDRESS = "10.111.222.2"
        private const val CHANNEL_ID = "shield"
        private const val NOTIFICATION_ID = 1
        private val FALLBACK_DNS = listOf("1.1.1.1", "9.9.9.9")
    }

    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var pool: ExecutorService? = null
    private var wakeWrite: FileDescriptor? = null
    @Volatile private var running = false

    private var cachedUpstream: List<InetAddress> = emptyList()
    private var cachedUpstreamAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        // ACTION_START from the UI, or android.net.VpnService from "Always-on VPN".
        showForegroundNotification()
        if (!running) startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        try {
            val builder = Builder()
                .setSession("Telemetry Shield")
                .addAddress(VPN_ADDRESS, 32)
                .addDnsServer(DNS_ADDRESS)
                .addRoute(DNS_ADDRESS, 32)
                .setMtu(1500)
                .setBlocking(true)
                // No IPv6 routes are added; without this, IPv6 traffic would be blocked.
                .allowFamily(OsConstants.AF_INET)
                .allowFamily(OsConstants.AF_INET6)
            // Keep this app outside its own tunnel so upstream DNS + list downloads go direct,
            // and so ConnectivityManager.activeNetwork reports the real underlying network.
            try { builder.addDisallowedApplication(packageName) } catch (_: PackageManager.NameNotFoundException) {}
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

            val pfd = builder.establish() ?: run {
                Stats.lastError = "VPN permission not granted"
                stopSelf(); return
            }
            tun = pfd
            pool = Executors.newFixedThreadPool(8)
            val pipe = Os.pipe()
            wakeWrite = pipe[1]
            running = true
            Stats.running = true
            Stats.lastError = null
            worker = Thread({ runLoop(pfd, pipe[0]) }, "dns-loop").also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            Stats.lastError = "Start failed: ${e.message}"
            stopVpn()
            stopSelf()
        }
    }

    private fun runLoop(pfd: ParcelFileDescriptor, wakeRead: FileDescriptor) {
        try {
            Blocklists.ensureLoaded(this)
        } catch (e: Exception) {
            Log.e(TAG, "blocklist load failed", e)
            Stats.lastError = "Block list load failed: ${e.message}"
        }
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        val tunPoll = StructPollfd().apply { fd = pfd.fileDescriptor; events = OsConstants.POLLIN.toShort() }
        val wakePoll = StructPollfd().apply { fd = wakeRead; events = OsConstants.POLLIN.toShort() }
        try {
            while (running) {
                try {
                    Os.poll(arrayOf(tunPoll, wakePoll), -1)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue else throw e
                }
                if (wakePoll.revents.toInt() != 0 || !running) break
                if (tunPoll.revents.toInt() and OsConstants.POLLIN == 0) continue

                val n = input.read(buf)
                if (n <= 0) continue
                val q = DnsPacket.parseIpv4UdpDns(buf, n) ?: continue
                Stats.recordQuery()
                if (Blocklists.matcher.isBlocked(q.name)) {
                    Stats.recordBlocked(q.name)
                    reply(output, q, DnsPacket.buildBlockedResponse(q))
                } else {
                    pool?.execute { forward(output, q) }
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "loop error", e)
                Stats.lastError = "Tunnel error: ${e.message}"
            }
        } finally {
            try { Os.close(wakeRead) } catch (_: Exception) {}
        }
    }

    private fun forward(output: FileOutputStream, q: DnsPacket.Query) {
        for (server in upstreamServers().take(3)) {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 4000
                    s.send(DatagramPacket(q.dns, q.dns.size, InetSocketAddress(server, 53)))
                    val rb = ByteArray(4096)
                    val rp = DatagramPacket(rb, rb.size)
                    s.receive(rp)
                    reply(output, q, rb.copyOf(rp.length))
                }
                return
            } catch (e: IOException) {
                // try the next server
            }
        }
    }

    private fun reply(output: FileOutputStream, q: DnsPacket.Query, dns: ByteArray) {
        val packet = DnsPacket.buildIpv4Udp(q.dstIp, q.srcIp, q.dstPort, q.srcPort, dns)
        synchronized(output) {
            try { if (running) output.write(packet) } catch (_: IOException) {}
        }
    }

    /** DNS servers of whatever network (Wi-Fi, mobile, Ethernet) is currently underneath us. */
    @Synchronized
    private fun upstreamServers(): List<InetAddress> {
        val now = System.currentTimeMillis()
        if (now - cachedUpstreamAt < 5_000 && cachedUpstream.isNotEmpty()) return cachedUpstream
        val result = mutableListOf<InetAddress>()
        val custom = Blocklists.upstream(this)
        if (custom.isNotBlank()) {
            custom.split(',', ' ').filter { it.isNotBlank() }.forEach {
                try { result += InetAddress.getByName(it.trim()) } catch (_: Exception) {}
            }
        }
        if (result.isEmpty()) {
            try {
                val cm = getSystemService(ConnectivityManager::class.java)
                val net = cm.activeNetwork
                cm.getLinkProperties(net)?.dnsServers
                    ?.filter { it.hostAddress != DNS_ADDRESS }
                    ?.let { result += it }
            } catch (_: Exception) {}
        }
        FALLBACK_DNS.forEach { result += InetAddress.getByName(it) }
        cachedUpstream = result
        cachedUpstreamAt = now
        return result
    }

    private fun showForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Telemetry Shield", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ShieldVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Telemetry Shield is on")
            .setContentText("Blocking tracking & telemetry on every network")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun stopVpn() {
        running = false
        Stats.running = false
        wakeWrite?.let { try { Os.close(it) } catch (_: Exception) {} }   // wakes the poll loop
        wakeWrite = null
        try { worker?.join(1000) } catch (_: InterruptedException) {}
        worker = null
        pool?.shutdownNow()
        pool = null
        try { tun?.close() } catch (_: IOException) {}
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        // Another VPN app took over, or the user revoked permission.
        stopVpn()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
