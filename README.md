# Telemetry Shield

An Android app that blocks tracking and telemetry domains on **every network**: Wi-Fi, mobile data, hotspots, Ethernet. No root needed. It works like AdGuard's "local VPN" mode, but only filters DNS.

## How it works

- The app creates a local VPN that routes **only DNS traffic** (to a fake DNS server, `10.111.222.2`) into itself. All other traffic goes out directly, so speed and battery use are unaffected.
- Each DNS lookup is checked against the block list. Blocked names get `0.0.0.0` / `::`. Other lookups go to the current network's own DNS servers, or to a custom upstream you set.
- Because it follows whichever network is active, it works on any Wi-Fi (including captive-portal hotel Wi-Fi) and on mobile data. Unlike Private DNS / router-level blocking, it doesn't depend on the network.
- Nothing leaves your phone through a third party. The filtering happens on the device.

## Block lists

- **Built-in:** a curated list of analytics, crash-reporting and vendor telemetry endpoints (`app/src/main/assets/telemetry_base.txt`). It deliberately leaves out domains that would break logins, push notifications or app stores.
- **Downloadable:** by default, HaGeZi's "native tracker" lists (Samsung, Xiaomi, Huawei, Oppo/Realme, Vivo, TikTok, Amazon, Windows/Office). Tap **Save & update lists**. You can add any hosts file, plain domain list or `||domain^` Adblock-style list URL. For broader ad + tracker blocking, add e.g. `https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/pro.txt`.
- **Allowlist:** if an app breaks, tap the domain under "Recently blocked" to allow it.

## Build it

**Option A: GitHub, no computer setup needed**
1. Create a new GitHub repository and upload this folder's contents, including the hidden `.github` folder.
2. GitHub Actions builds automatically. Open the **Actions** tab, select the latest run and download the **TelemetryShield-apk** artifact.
3. Unzip it on your phone and install `app-debug.apk`. You'll need to allow "Install unknown apps" for your browser or file manager.

**Option B: Android Studio**
Open the folder in Android Studio (Ladybug or newer), let it sync, then click Run, or use *Build › Build APK(s)*.

Command line: `./gradlew assembleDebug` (needs JDK 17+ and the Android SDK).

## Use it

1. Open the app and tap **Turn on**. Accept the VPN prompt.
2. Tap **Save & update lists** once to download the vendor lists.
3. **Important:** set *Settings › Network & internet › Private DNS* to **Off** or **Automatic**. If it's set to a specific provider hostname, Android sends DNS directly to that provider and skips the filter.
4. To keep it on after reboots: *Settings › Network & internet › VPN › ⚙ Telemetry Shield › Always-on VPN*.

## Limitations

- Android allows only one VPN at a time, so this can't run at the same time as another VPN app.
- Apps that use their own DNS-over-HTTPS or hard-coded IP addresses bypass DNS filtering. Some browsers do this when "Secure DNS" is on in their settings. This limitation affects every DNS-based blocker, including AdGuard's DNS mode.
- Only blocks by domain. It can't strip tracking out of traffic to domains an app needs to function.
- Some phones' battery savers kill background apps. If protection stops on its own, exempt Telemetry Shield from battery optimization.

## Code layout

| File | Purpose |
|---|---|
| `ShieldVpnService.kt` | The local VPN: reads DNS packets from the tunnel, blocks or forwards them |
| `DnsPacket.kt` | IPv4/UDP/DNS parsing and response building |
| `DomainMatcher.kt` | List parsing (hosts / domains / Adblock) and suffix matching |
| `Blocklists.kt` | Loading, downloading and storing lists, allowlist and settings |
| `MainActivity.kt` | The UI |
| `test/CoreTest.kt` | Plain-JVM tests for the packet and matching logic |
