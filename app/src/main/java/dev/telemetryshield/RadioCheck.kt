package dev.telemetryshield

import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings

/**
 * Read-only checks for Bluetooth / NFC exposure. Android doesn't let regular apps
 * switch radios off (Bluetooth since Android 13, NFC never), so we report status
 * and send the user to the right settings screen instead.
 */
object RadioCheck {

    private const val NEVER_FOR_LOCATION = 0x00010000 // PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION

    class ScanApp(val label: String, val pkg: String, val reasons: List<String>, val background: Boolean)

    /** true/false, or null if unknown / no hardware. */
    fun bluetoothOn(ctx: Context): Boolean? = try {
        ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
    } catch (_: Exception) { null }

    fun hasNfc(ctx: Context) = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)

    fun nfcOn(ctx: Context): Boolean? = try {
        NfcAdapter.getDefaultAdapter(ctx)?.isEnabled
    } catch (_: Exception) { null }

    /** "Bluetooth scanning": lets apps & Google services scan for beacons even with Bluetooth off. */
    fun bleScanningOn(ctx: Context): Boolean? = try {
        Settings.Global.getInt(ctx.contentResolver, "ble_scan_always_enabled") == 1
    } catch (_: Exception) { null }

    fun openBluetoothSettings(ctx: Context) = open(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    fun openNfcSettings(ctx: Context) = open(ctx, Intent(Settings.ACTION_NFC_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS))

    fun openScanningSettings(ctx: Context) = open(
        ctx,
        Intent("android.settings.LOCATION_SCANNING_SETTINGS"),
        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
    )

    fun openAppSettings(ctx: Context, pkg: String) = open(
        ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
    )

    private fun open(ctx: Context, vararg intents: Intent) {
        for (i in intents) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }

    /**
     * Installed apps that currently hold the permissions needed to detect Bluetooth beacons
     * and tie them to a location. Apps that declared "never for location" are excluded,
     * because Android filters beacon results out for them.
     */
    fun appsThatCanDetectBeacons(ctx: Context): List<ScanApp> {
        val pm = ctx.packageManager
        val packages: List<PackageInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }
        val result = mutableListOf<ScanApp>()
        for (p in packages) {
            if (p.packageName == ctx.packageName) continue
            val ai: ApplicationInfo = p.applicationInfo ?: continue
            val isSystem = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val updatedSystem = ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            if (isSystem && !updatedSystem) continue
            val perms = p.requestedPermissions ?: continue
            val flags = p.requestedPermissionsFlags ?: continue

            val granted = HashSet<String>()
            var scanNeverForLocation = false
            for (i in perms.indices) {
                if (i >= flags.size) break
                if (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0) granted += perms[i]
                if (perms[i] == "android.permission.BLUETOOTH_SCAN" && flags[i] and NEVER_FOR_LOCATION != 0) {
                    scanNeverForLocation = true
                }
            }
            val fine = "android.permission.ACCESS_FINE_LOCATION" in granted
            val coarse = "android.permission.ACCESS_COARSE_LOCATION" in granted
            val bg = "android.permission.ACCESS_BACKGROUND_LOCATION" in granted

            val canDetect = if (Build.VERSION.SDK_INT >= 31) {
                "android.permission.BLUETOOTH_SCAN" in granted && !scanNeverForLocation
            } else {
                (fine || coarse) && "android.permission.BLUETOOTH_ADMIN" in perms
            }
            if (!canDetect) continue

            val reasons = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 31) reasons += "Nearby devices"
            when {
                bg -> reasons += "Location all the time"
                fine -> reasons += "Precise location"
                coarse -> reasons += "Approximate location"
            }
            result += ScanApp(ai.loadLabel(pm).toString(), p.packageName, reasons, bg)
        }
        return result.sortedWith(compareByDescending<ScanApp> { it.background }.thenBy { it.label.lowercase() })
    }
}
