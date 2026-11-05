package dev.telemetryshield

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

class MainActivity : Activity() {

    private companion object {
        const val REQ_VPN = 1
        const val REQ_NOTIF = 2

        // Dark palette
        val BG = 0xFF0D1015.toInt()
        val CARD = 0xFF161B22.toInt()
        val STROKE = 0xFF232A34.toInt()
        val FIELD = 0xFF0F1318.toInt()
        val TEXT = 0xFFE8EDF2.toInt()
        val SUB = 0xFF8B95A1.toInt()
        val GREEN = 0xFF3DDC84.toInt()
        val RED = 0xFFFF6B6B.toInt()
        val AMBER = 0xFFFFB454.toInt()
        val BLUE = 0xFF5AA9FF.toInt()
        val OFF_GREY = 0xFF5B6470.toInt()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val numbers = NumberFormat.getIntegerInstance()

    private lateinit var heroIconBg: GradientDrawable
    private lateinit var heroIcon: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var protectionSwitch: Switch
    private lateinit var blockedTile: TextView
    private lateinit var lookupsTile: TextView
    private lateinit var domainsTile: TextView

    private lateinit var btStatus: TextView
    private lateinit var scanStatus: TextView
    private lateinit var nfcStatus: TextView
    private lateinit var beaconAppsBox: LinearLayout

    private lateinit var recentBox: LinearLayout
    private lateinit var listInfo: TextView
    private lateinit var updateButton: TextView
    private lateinit var urlsField: EditText
    private lateinit var allowField: EditText
    private lateinit var upstreamField: EditText

    private var lastRecent: List<String>? = null
    private var suppressSwitch = false
    private var startRequestedAt = 0L

    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goEdgeToEdge()
        setContentView(buildUi())
        Thread {
            Blocklists.ensureLoaded(this)
            runOnUiThread { updateListInfo() }
            // First run (or after an update that added lists): fetch switched-on lists quietly.
            if (Blocklists.needsDownload(this)) {
                try { Blocklists.download(this) } catch (_: Exception) {}
                runOnUiThread { updateListInfo() }
            }
        }.start()
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker) }

    // ======================= styling helpers =======================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun goEdgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.setBackgroundColor(BG)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        strokeColor?.let { setStroke(dp(1), it) }
    }

    private fun ripple(base: Drawable?, radiusDp: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), base, rounded(Color.WHITE, radiusDp))

    private fun text(t: String, size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(0f, 1.15f)
    }

    private fun matchWrap(topMarginDp: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(topMarginDp) }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 20, STROKE)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = matchWrap().apply { bottomMargin = dp(14) }
    }

    private fun cardTitle(parent: LinearLayout, title: String, subtitle: String? = null) {
        parent.addView(text(title, 18f, TEXT, bold = true))
        subtitle?.let { parent.addView(text(it, 13.5f, SUB).apply { layoutParams = matchWrap(4) }) }
    }

    private fun styledSwitch(): Switch = Switch(this).apply {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(GREEN, 0xFFC4CBD3.toInt()))
        trackTintList = ColorStateList(states, intArrayOf(0x993DDC84.toInt(), 0xFF3A424D.toInt()))
    }

    /** A row with a title/subtitle on the left and a switch on the right. */
    private fun switchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): Pair<View, Switch> {
        val sw = styledSwitch().apply {
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 15.5f, TEXT, bold = subtitle != null))
            subtitle?.let { addView(text(it, 13f, SUB).apply { layoutParams = matchWrap(2) }) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
            addView(sw)
            setOnClickListener { sw.toggle() }
        }
        return row to sw
    }

    private fun pillButton(label: String, filled: Boolean, onClick: (TextView) -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (filled) BG else BLUE)
        background = ripple(if (filled) rounded(GREEN, 24) else rounded(Color.TRANSPARENT, 24, 0xFF2F3B4A.toInt()), 24)
        setPadding(dp(18), dp(13), dp(18), dp(13))
        layoutParams = matchWrap(12)
        isClickable = true
        setOnClickListener { onClick(this) }
    }

    /** Label on the left, coloured value + chevron on the right. Returns the value view. */
    private fun statusRow(parent: LinearLayout, label: String, onClick: () -> Unit): TextView {
        val value = text("", 14f, SUB, bold = true)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(12))
            background = ripple(null, 12)
            isClickable = true
            setOnClickListener { onClick() }
            addView(text(label, 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
            addView(text("  ›", 18f, SUB))
        }
        parent.addView(divider())
        parent.addView(row)
        return value
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(STROKE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun field(lines: Int, mono: Boolean, hintText: String) = EditText(this).apply {
        inputType = if (lines > 1)
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        if (lines > 1) { minLines = lines; maxLines = 10; gravity = Gravity.TOP } else setSingleLine()
        textSize = 13f
        if (mono) typeface = Typeface.MONOSPACE
        setTextColor(TEXT)
        setHintTextColor(OFF_GREY)
        hint = hintText
        background = rounded(FIELD, 12, STROKE)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = matchWrap(10)
    }

    private fun tile(label: String): Pair<View, TextView> {
        val value = text("0", 22f, TEXT, bold = true).apply { gravity = Gravity.CENTER }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(FIELD, 14)
            setPadding(dp(6), dp(12), dp(6), dp(12))
            addView(value)
            addView(text(label, 12f, SUB).apply { gravity = Gravity.CENTER })
        }
        return box to value
    }

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    // ======================= layout =======================

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- header
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(16))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_shield)
                imageTintList = ColorStateList.valueOf(GREEN)
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) })
            addView(text("Telemetry Shield", 22f, TEXT, bold = true))
        })

        // ---- hero / protection
        val hero = card().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(18), dp(24), dp(18), dp(18)) }
        heroIconBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        heroIcon = ImageView(this).apply { setImageResource(R.drawable.ic_shield) }
        hero.addView(FrameLayout(this).apply {
            background = heroIconBg
            addView(heroIcon, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(104), dp(104)))
        heroTitle = text("", 24f, TEXT, bold = true).apply { gravity = Gravity.CENTER; layoutParams = matchWrap(14) }
        heroSub = text("", 14f, SUB).apply { gravity = Gravity.CENTER; layoutParams = matchWrap(4) }
        hero.addView(heroTitle)
        hero.addView(heroSub)

        val (protRow, protSw) = switchRow("Protection", "Filters tracking on Wi-Fi and mobile data", Stats.running) { on ->
            if (suppressSwitch) return@switchRow
            if (on) startProtection() else stopProtection()
        }
        protectionSwitch = protSw
        hero.addView(protRow.apply { layoutParams = matchWrap(14) })

        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = matchWrap(8) }
        val (t1, v1) = tile("Blocked"); val (t2, v2) = tile("Lookups"); val (t3, v3) = tile("On block list")
        blockedTile = v1; lookupsTile = v2; domainsTile = v3
        listOf(t1, t2, t3).forEachIndexed { i, t ->
            tiles.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(8)
            })
        }
        hero.addView(tiles)
        root.addView(hero)

        // ---- store beacons
        val beacons = card()
        cardTitle(beacons, "Store beacons")
        val (beaconRow, _) = switchRow(
            "Block store beacon tracking",
            "Stops apps from reporting your store visits to beacon and location-tracking companies. Works while protection is on.",
            Blocklists.blockLocationSdks(this),
        ) { on ->
            Thread {
                Blocklists.setBlockLocationSdks(this, on)
                runOnUiThread { updateListInfo(); toast(if (on) "Beacon tracking blocked" else "Beacon tracking allowed") }
            }.start()
        }
        beacons.addView(beaconRow)
        btStatus = statusRow(beacons, "Bluetooth") { RadioCheck.openBluetoothSettings(this) }
        scanStatus = statusRow(beacons, "Bluetooth scanning") {
            RadioCheck.openScanningSettings(this)
            toast("Look for Location services › Bluetooth scanning")
        }
        nfcStatus = if (RadioCheck.hasNfc(this)) statusRow(beacons, "NFC") { RadioCheck.openNfcSettings(this) }
            else text("", 1f)
        beacons.addView(divider())
        beacons.addView(text(
            "Stores can't read your info over Bluetooth or NFC. Beacons only broadcast; tracking happens when an app " +
                "on your phone hears one. NFC works only within a few centimetres.", 12.5f, SUB
        ).apply { layoutParams = matchWrap(12) })
        beacons.addView(pillButton("Check which apps can detect beacons", filled = false) { checkBeaconApps() })
        beaconAppsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = matchWrap(4) }
        beacons.addView(beaconAppsBox)
        root.addView(beacons)

        // ---- recently blocked
        val recent = card()
        cardTitle(recent, "Recently blocked", "Tap one to allow it if an app stopped working.")
        recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = matchWrap(6) }
        recent.addView(recentBox)
        root.addView(recent)

        // ---- company lists
        val lists = card()
        cardTitle(lists, "Phone & company lists", "Built-in trackers in each company's phones, apps and services.")
        listInfo = text("", 12.5f, SUB).apply { layoutParams = matchWrap(6) }
        lists.addView(listInfo)
        updateButton = pillButton("Update lists", filled = false) { onUpdateLists() }
        val enabled = Blocklists.enabledPresets(this)
        for (preset in Blocklists.PRESETS) {
            val (row, _) = switchRow(preset.label, null, preset.id in enabled) { on ->
                Thread {
                    Blocklists.setPresetEnabled(this, preset.id, on)
                    runOnUiThread {
                        updateListInfo()
                        if (on && Blocklists.needsDownload(this)) onUpdateLists()
                    }
                }.start()
            }
            lists.addView(divider())
            lists.addView(row)
        }
        lists.addView(updateButton)
        root.addView(lists)

        // ---- advanced (collapsed)
        val adv = card()
        val advBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val chevron = text("▾", 18f, SUB)
        adv.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener {
                val show = advBody.visibility != View.VISIBLE
                advBody.visibility = if (show) View.VISIBLE else View.GONE
                chevron.text = if (show) "▴" else "▾"
            }
            addView(text("Advanced", 18f, TEXT, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron)
        })

        advBody.addView(text("Extra block lists", 15f, TEXT, bold = true).apply { layoutParams = matchWrap(16) })
        advBody.addView(text("One URL per line. Hosts files, domain lists and ||domain^ rules work.", 12.5f, SUB))
        urlsField = field(2, mono = true, hintText = "https://…").apply { setText(Blocklists.customUrls(this@MainActivity).joinToString("\n")) }
        advBody.addView(urlsField)
        advBody.addView(pillButton("Save & update", filled = false) { onUpdateLists() })

        advBody.addView(text("Allowlist", 15f, TEXT, bold = true).apply { layoutParams = matchWrap(20) })
        advBody.addView(text("Never blocked, subdomains included. One per line.", 12.5f, SUB))
        allowField = field(3, mono = true, hintText = "example.com").apply {
            setText(Blocklists.allowlist(this@MainActivity).sorted().joinToString("\n"))
        }
        advBody.addView(allowField)
        advBody.addView(pillButton("Save allowlist", filled = false) {
            Blocklists.setAllowlist(this, allowField.text.lines())
            allowField.setText(Blocklists.allowlist(this).sorted().joinToString("\n"))
            toast("Allowlist saved")
        })

        advBody.addView(text("Upstream DNS", 15f, TEXT, bold = true).apply { layoutParams = matchWrap(20) })
        advBody.addView(text("Blank uses each network's own DNS (recommended). Or e.g. 9.9.9.9, 1.1.1.1", 12.5f, SUB))
        upstreamField = field(1, mono = false, hintText = "Network default").apply { setText(Blocklists.upstream(this@MainActivity)) }
        advBody.addView(upstreamField)
        advBody.addView(pillButton("Save upstream", filled = false) {
            Blocklists.setUpstream(this, upstreamField.text.toString()); toast("Saved")
        })
        adv.addView(advBody)
        root.addView(adv)

        // ---- tips
        root.addView(text(
            "Tips\n" +
                "• Set Settings › Network › Private DNS to Off or Automatic, or apps' lookups skip this filter.\n" +
                "• Keep it on after restarts: Settings › Network › VPN › Telemetry Shield › Always-on VPN.\n" +
                "• Android allows one VPN at a time.",
            12.5f, SUB
        ).apply { setPadding(dp(6), dp(4), dp(6), 0) })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            clipToPadding = false
            addView(root)
        }
        // Keep content clear of the status bar, navigation bar and keyboard.
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                top = i.top; bottom = i.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            root.setPadding(dp(16), top + dp(12), dp(16), bottom + dp(24))
            insets
        }
        return scroll
    }

    // ======================= live state =======================

    private fun refresh() {
        val now = System.currentTimeMillis()
        val starting = !Stats.running && startRequestedAt > 0 && now - startRequestedAt < 5000
        val on = Stats.running || starting

        if (protectionSwitch.isChecked != on) {
            suppressSwitch = true
            protectionSwitch.isChecked = on
            suppressSwitch = false
        }
        val accent = if (Stats.running) GREEN else if (starting) AMBER else OFF_GREY
        heroIcon.imageTintList = ColorStateList.valueOf(accent)
        heroIconBg.setColor((accent and 0x00FFFFFF) or 0x26000000)
        heroTitle.text = when {
            Stats.running -> "You're protected"
            starting -> "Starting…"
            else -> "Not protected"
        }
        val err = Stats.lastError
        heroSub.text = when {
            err != null && !Stats.running -> "⚠ $err"
            Stats.running -> "Tracking is being blocked on every network"
            else -> "Turn on protection to block tracking"
        }
        heroSub.setTextColor(if (err != null && !Stats.running) AMBER else SUB)
        blockedTile.text = numbers.format(Stats.blockedCount)
        blockedTile.setTextColor(if (Stats.blockedCount > 0) GREEN else TEXT)
        lookupsTile.text = numbers.format(Stats.queryCount)
        domainsTile.text = numbers.format(Blocklists.matcher.size.toLong())

        setStatus(btStatus, when (RadioCheck.bluetoothOn(this)) {
            true -> "On" to SUB; false -> "Off" to GREEN; null -> "Unknown" to SUB
        })
        setStatus(scanStatus, when (RadioCheck.bleScanningOn(this)) {
            true -> "On · turn off" to RED; false -> "Off" to GREEN; null -> "Check" to AMBER
        })
        if (RadioCheck.hasNfc(this)) setStatus(nfcStatus, when (RadioCheck.nfcOn(this)) {
            true -> "On" to SUB; false -> "Off" to GREEN; null -> "Unknown" to SUB
        })

        val recent = Stats.recentBlocked()
        if (recent != lastRecent) {
            lastRecent = recent
            recentBox.removeAllViews()
            if (recent.isEmpty()) {
                recentBox.addView(text(if (Stats.running) "Nothing blocked yet." else "Turn on protection to see blocked trackers.", 13.5f, OFF_GREY)
                    .apply { setPadding(0, dp(8), 0, dp(4)) })
            }
            recent.take(30).forEachIndexed { i, d ->
                if (i > 0) recentBox.addView(divider())
                recentBox.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(2), dp(10), dp(2), dp(10))
                    background = ripple(null, 10)
                    isClickable = true
                    setOnClickListener { confirmAllow(d) }
                    addView(text(d, 13.5f, TEXT).apply { typeface = Typeface.MONOSPACE; setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(text("Allow", 13f, BLUE, bold = true))
                })
            }
        }
    }

    private fun setStatus(view: TextView, value: Pair<String, Int>) {
        view.text = value.first
        view.setTextColor(value.second)
    }

    private fun checkBeaconApps() {
        beaconAppsBox.removeAllViews()
        beaconAppsBox.addView(text("Checking…", 13.5f, SUB).apply { setPadding(0, dp(10), 0, 0) })
        Thread {
            val apps = try { RadioCheck.appsThatCanDetectBeacons(this) } catch (_: Exception) { null }
            runOnUiThread {
                beaconAppsBox.removeAllViews()
                when {
                    apps == null -> beaconAppsBox.addView(text("Couldn't read the app list.", 13.5f, AMBER).apply { setPadding(0, dp(10), 0, 0) })
                    apps.isEmpty() -> beaconAppsBox.addView(text("No apps can currently detect beacons.", 13.5f, GREEN).apply { setPadding(0, dp(10), 0, 0) })
                    else -> {
                        beaconAppsBox.addView(text("${apps.size} apps · red = location all the time. Tap to change permissions.", 12.5f, SUB)
                            .apply { setPadding(0, dp(12), 0, dp(4)) })
                        apps.forEach { app ->
                            beaconAppsBox.addView(divider())
                            beaconAppsBox.addView(LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(dp(2), dp(10), dp(2), dp(10))
                                background = ripple(null, 10)
                                isClickable = true
                                setOnClickListener { RadioCheck.openAppSettings(this@MainActivity, app.pkg) }
                                addView(LinearLayout(this@MainActivity).apply {
                                    orientation = LinearLayout.VERTICAL
                                    addView(text(app.label, 15f, TEXT))
                                    addView(text(app.reasons.joinToString(" · "), 12.5f, if (app.background) RED else SUB))
                                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                                addView(text("›", 18f, SUB))
                            })
                        }
                    }
                }
            }
        }.start()
    }

    private fun updateListInfo() {
        val last = Blocklists.lastUpdate(this)
        listInfo.text = when {
            Blocklists.needsDownload(this) -> "Some switched-on lists aren't downloaded yet. Tap Update lists."
            last == 0L -> "Using built-in lists."
            else -> "Last updated ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last))}"
        }
    }

    private fun confirmAllow(domain: String) {
        dialog()
            .setTitle("Allow $domain?")
            .setMessage("It and its subdomains won't be blocked any more. Apps may take about a minute to notice.")
            .setPositiveButton("Allow") { _, _ ->
                Blocklists.addAllowed(this, domain)
                Stats.forgetRecent(domain)
                allowField.setText(Blocklists.allowlist(this).sorted().joinToString("\n"))
                lastRecent = null
                toast("Allowed $domain")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onUpdateLists() {
        Blocklists.setCustomUrls(this, urlsField.text.lines())
        if (!updateButton.isEnabled) return
        updateButton.isEnabled = false
        updateButton.text = "Updating…"
        Thread {
            val msg = Blocklists.download(this)
            runOnUiThread {
                updateButton.isEnabled = true
                updateButton.text = "Update lists"
                updateListInfo()
                dialog().setMessage(msg).setPositiveButton("OK", null).show()
            }
        }.start()
    }

    // ======================= start / stop =======================

    private fun startProtection() {
        startRequestedAt = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return // continues in onRequestPermissionsResult
        }
        requestVpn()
    }

    private fun stopProtection() {
        startRequestedAt = 0
        startService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_STOP))
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
            startRequestedAt = System.currentTimeMillis()
            Stats.reset()
            lastRecent = null
            startForegroundService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_START))
        } else {
            startRequestedAt = 0
            toast("VPN permission is needed to filter tracking")
        }
        refresh()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
