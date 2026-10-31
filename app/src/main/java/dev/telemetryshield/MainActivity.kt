package dev.telemetryshield

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {

    private companion object {
        const val REQ_VPN = 1
        const val REQ_NOTIF = 2
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var counts: TextView
    private lateinit var recentBox: LinearLayout
    private lateinit var listInfo: TextView
    private lateinit var urlsField: EditText
    private lateinit var allowField: EditText
    private lateinit var upstreamField: EditText
    private var lastRecent: List<String> = emptyList()
    private lateinit var btStatus: TextView
    private lateinit var scanStatus: TextView
    private lateinit var nfcStatus: TextView
    private lateinit var beaconAppsBox: LinearLayout

    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        Thread {
            Blocklists.ensureLoaded(this)
            runOnUiThread { updateListInfo() }
        }.start()
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker) }

    // ---------- UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(Color.GRAY)
    }

    private fun multiline(lines: Int) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = lines; maxLines = 12; gravity = Gravity.TOP; textSize = 12f
        typeface = Typeface.MONOSPACE
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        root.addView(TextView(this).apply {
            text = "Telemetry Shield"; textSize = 26f; setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(hint("Blocks tracking & telemetry domains on Wi-Fi, mobile data and any other network, using a local DNS filter. No traffic leaves your phone through us."))

        status = TextView(this).apply { textSize = 18f; setPadding(0, dp(16), 0, dp(8)) }
        root.addView(status)
        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        root.addView(toggle)
        counts = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
        root.addView(counts)

        root.addView(heading("Recently blocked"))
        root.addView(hint("Tap a domain to allow it if something broke."))
        recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(recentBox)


        root.addView(heading("Bluetooth & NFC"))
        root.addView(hint(
            "Stores can't read personal info off your phone over Bluetooth or NFC. In-store Bluetooth " +
            "beacons only broadcast; the tracking happens when an app on your phone hears one and " +
            "reports your visit. NFC only works within a few centimetres and payments need your phone unlocked."
        ))
        root.addView(CheckBox(this).apply {
            text = "Block beacon & location-tracking companies"
            isChecked = Blocklists.blockLocationSdks(this@MainActivity)
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, on ->
                Thread {
                    Blocklists.setBlockLocationSdks(this@MainActivity, on)
                    runOnUiThread { updateListInfo() }
                }.start()
            }
        })

        btStatus = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
        root.addView(btStatus)
        root.addView(Button(this).apply {
            text = "Bluetooth settings"
            setOnClickListener { RadioCheck.openBluetoothSettings(this@MainActivity) }
        })
        scanStatus = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
        root.addView(scanStatus)
        root.addView(Button(this).apply {
            text = "Bluetooth scanning setting"
            setOnClickListener {
                RadioCheck.openScanningSettings(this@MainActivity)
                toast("Look for Location services › Bluetooth scanning")
            }
        })
        nfcStatus = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
        root.addView(nfcStatus)
        if (RadioCheck.hasNfc(this)) {
            root.addView(Button(this).apply {
                text = "NFC settings"
                setOnClickListener { RadioCheck.openNfcSettings(this@MainActivity) }
            })
        }

        root.addView(heading("Apps that can detect store beacons"))
        root.addView(hint("These apps have the permissions needed to hear Bluetooth beacons and know where you are. Tap one to open its settings and remove Nearby devices or Location if it doesn't need them."))
        beaconAppsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(Button(this).apply {
            text = "Check my apps"
            setOnClickListener { checkBeaconApps() }
        })
        root.addView(beaconAppsBox)

        root.addView(heading("Block lists"))
        listInfo = hint("")
        root.addView(listInfo)
        root.addView(hint("One URL per line. Hosts files, plain domain lists and ||domain^ rules all work."))
        urlsField = multiline(4).apply { setText(Blocklists.urls(this@MainActivity).joinToString("\n")) }
        root.addView(urlsField)
        root.addView(Button(this).apply { text = "Save & update lists"; setOnClickListener { onUpdateLists(this) } })

        root.addView(heading("Allowlist"))
        root.addView(hint("Domains here are never blocked (subdomains included)."))
        allowField = multiline(3).apply { setText(Blocklists.allowlist(this@MainActivity).sorted().joinToString("\n")) }
        root.addView(allowField)
        root.addView(Button(this).apply {
            text = "Save allowlist"
            setOnClickListener {
                Blocklists.setAllowlist(this@MainActivity, allowField.text.lines())
                allowField.setText(Blocklists.allowlist(this@MainActivity).sorted().joinToString("\n"))
                toast("Allowlist saved")
            }
        })

        root.addView(heading("Upstream DNS"))
        root.addView(hint("Leave blank to use each network's own DNS (recommended; works on captive Wi-Fi). Or enter servers like 9.9.9.9, 1.1.1.1"))
        upstreamField = EditText(this).apply {
            setSingleLine(); setText(Blocklists.upstream(this@MainActivity)); hint = "network default"
        }
        root.addView(upstreamField)
        root.addView(Button(this).apply {
            text = "Save upstream"
            setOnClickListener { Blocklists.setUpstream(this@MainActivity, upstreamField.text.toString()); toast("Saved") }
        })

        root.addView(heading("Tips"))
        root.addView(hint(
            "• Turn off Settings › Network › Private DNS (or set it to Automatic), otherwise apps' DNS skips this filter.\n" +
            "• To start automatically after reboot: Settings › Network › VPN › Telemetry Shield › Always-on VPN.\n" +
            "• Android allows one VPN at a time, so this can't run alongside another VPN app."
        ))

        return ScrollView(this).apply { addView(root) }
    }

    private fun refresh() {
        val bt = RadioCheck.bluetoothOn(this)
        btStatus.text = "Bluetooth: " + when (bt) { true -> "ON"; false -> "off"; null -> "unknown" }
        val scan = RadioCheck.bleScanningOn(this)
        scanStatus.text = "Bluetooth scanning (works even with Bluetooth off): " +
            when (scan) { true -> "ON — consider turning it off"; false -> "off"; null -> "check in settings" }
        nfcStatus.text = if (!RadioCheck.hasNfc(this)) "NFC: not on this phone" else
            "NFC: " + when (RadioCheck.nfcOn(this)) { true -> "ON (only works within a few cm)"; false -> "off"; null -> "unknown" }
        val on = Stats.running
        status.text = if (on) "● Protection ON" else "○ Protection OFF"
        status.setTextColor(if (on) Color.rgb(46, 125, 50) else Color.rgb(183, 28, 28))
        toggle.text = if (on) "Turn off" else "Turn on"
        counts.text = buildString {
            append("${Stats.blockedCount} blocked of ${Stats.queryCount} DNS lookups this session")
            Stats.lastError?.let { append("\n⚠ $it") }
        }
        val recent = Stats.recentBlocked()
        if (recent != lastRecent) {
            lastRecent = recent
            recentBox.removeAllViews()
            if (recent.isEmpty()) recentBox.addView(hint("Nothing yet."))
            recent.take(40).forEach { d ->
                recentBox.addView(TextView(this).apply {
                    text = d; typeface = Typeface.MONOSPACE; textSize = 13f
                    setPadding(0, dp(6), 0, dp(6))
                    setOnClickListener { confirmAllow(d) }
                })
            }
        }
    }

    private fun checkBeaconApps() {
        beaconAppsBox.removeAllViews()
        beaconAppsBox.addView(hint("Checking…"))
        Thread {
            val apps = try { RadioCheck.appsThatCanDetectBeacons(this) } catch (e: Exception) { null }
            runOnUiThread {
                beaconAppsBox.removeAllViews()
                when {
                    apps == null -> beaconAppsBox.addView(hint("Couldn't read the app list."))
                    apps.isEmpty() -> beaconAppsBox.addView(hint("No installed apps can currently detect beacons. 👍"))
                    else -> apps.forEach { app ->
                        beaconAppsBox.addView(TextView(this).apply {
                            text = app.label + "\n" + app.reasons.joinToString(" · ")
                            textSize = 14f
                            if (app.background) setTextColor(Color.rgb(183, 28, 28))
                            setPadding(0, dp(8), 0, dp(8))
                            setOnClickListener { RadioCheck.openAppSettings(this@MainActivity, app.pkg) }
                        })
                    }
                }
            }
        }.start()
    }

    private fun updateListInfo() {
        val last = Blocklists.lastUpdate(this)
        listInfo.text = "${Blocklists.matcher.size} domains loaded. " +
            if (last == 0L) "Lists not downloaded yet — only the built-in list is active."
            else "Updated ${DateFormat.getDateTimeInstance().format(Date(last))}."
    }

    private fun confirmAllow(domain: String) {
        AlertDialog.Builder(this)
            .setTitle("Allow $domain?")
            .setMessage("It (and its subdomains) won't be blocked any more. Apps may need about a minute to notice.")
            .setPositiveButton("Allow") { _, _ ->
                Blocklists.addAllowed(this, domain)
                Stats.forgetRecent(domain)
                allowField.setText(Blocklists.allowlist(this).sorted().joinToString("\n"))
                toast("Allowed $domain")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onUpdateLists(button: Button) {
        Blocklists.setUrls(this, urlsField.text.lines())
        button.isEnabled = false
        button.text = "Updating…"
        Thread {
            val msg = Blocklists.download(this)
            runOnUiThread {
                button.isEnabled = true
                button.text = "Save & update lists"
                updateListInfo()
                AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show()
            }
        }.start()
    }

    // ---------- start / stop ----------

    private fun onToggle() {
        if (Stats.running) {
            startService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_STOP))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return // continue in onRequestPermissionsResult
        }
        requestVpn()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) requestVpn() // works with or without the notification permission
    }

    private fun requestVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, REQ_VPN)
        else onActivityResult(REQ_VPN, RESULT_OK, null)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VPN) return
        if (resultCode == RESULT_OK) {
            Stats.reset()
            startForegroundService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_START))
        } else {
            toast("VPN permission is needed to filter DNS")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
