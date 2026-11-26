# Plexo — Multi-Network Bonding Download Manager for Android

**Plexo** is an accelerated Android download manager built with Kotlin, Jetpack Compose, OkHttp, Room, and Material 3. It splits large files into HTTP byte ranges and downloads them concurrently across **every active network interface at once** (Wi-Fi + 5G/4G Mobile Data + USB Ethernet/Tethering) so their bandwidths combine into one unified download.

---

## Architecture Overview

| Layer | Key Classes | Responsibility |
| :--- | :--- | :--- |
| **Multi-Network Bonding** | `NetworkBondingManager`, `OkHttpBoundNetworkClientFactory`, `ProbeService` | Requests simultaneous Wi-Fi, Cellular, and Ethernet network leases via `ConnectivityManager.requestNetwork()`, binds each `OkHttpClient` to `network.socketFactory` + `network.getAllByName`, filters loopback/VPN/unvalidated interfaces, detects metered links, and probes URLs (`HEAD` with `Range: bytes=0-0` fallback). |
| **Download Engine** | `DownloadEngine`, `ChunkScheduler`, `PositionalPartWriter`, `DiskWriter`, `SpeedLimiter`, `TelemetryTracker` | Work-stealing chunk queue (splits slowest in-progress chunk in half when unassigned queue empties), lock-free `FileChannel.write(ByteBuffer, position)` to `<name>.plexo.part`, 15-second stall detector, exponential backoff with jitter, HTTP 403/410 expired-link recovery, SHA-256/MD5 verification, and Scoped Storage / MediaStore / SAF export. |
| **Persistence** | `PlexoDatabase`, `DownloadDao`, `ChunkDao`, `NetworkConfigDao`, `SettingsRepository` | Room SQLite schema flushing chunk offsets every 2 seconds and on pause/exit; DataStore preferences for speed limits, chunk sizes, stream defaults, auto-sorting, and download scheduling. |
| **Background Execution** | `DownloadForegroundService` | `dataSync` Foreground Service with `WifiLock`, `PARTIAL_WAKE_LOCK`, and ongoing notification controls (`Pause All`, `Resume All`, `Cancel`). |
| **UI & System Integration** | `DashboardScreen`, `DownloadDetailScreen`, `SettingsScreen`, `NetworksManagerSheet`, `NewDownloadSheet` | Jetpack Compose Material 3 UI featuring animated `NetworkFlowCanvas`, 60-second `ThroughputChartCanvas`, 192-cell `ChunkMapCanvas`, Share Sheet (`ACTION_SEND`) & browser link interception (`ACTION_VIEW`), and clipboard URL detection. |

---

## How to Test Wi-Fi + 5G/4G Mobile Data Bonding on a Physical Android Device

Android normally powers down the cellular data radio as soon as Wi-Fi connects. Plexo overrides this cleanly using separate `ConnectivityManager.requestNetwork()` leases for `TRANSPORT_WIFI`, `TRANSPORT_CELLULAR`, and `TRANSPORT_ETHERNET`.

### Prerequisites
1. A physical Android device (Android 9+ recommended; Android 10–15 ideal) with an active **SIM / 5G or 4G LTE plan** and a **Wi-Fi network** (plus an optional **USB-C Ethernet adapter**).
2. *(Optional OEM tip)* On some Android devices (e.g., Pixel, Samsung, OnePlus), enable **Developer Options → Mobile data always active** if your carrier/OEM aggressively sleeps the cellular radio before the first download starts.
3. A test download URL hosted on a CDN that supports `Accept-Ranges: bytes` (for example, a large Linux ISO or public speed-test binary such as Hetzner/Cloudflare/Ubuntu CDN).

### Step-by-Step Verification
1. **Connect Wi-Fi and ensure Mobile Data is enabled** in Android Quick Settings.
2. **Launch Plexo** and tap the **Networks Pill** (`1 Net` / `2 Nets`) in the top-right corner of the Dashboard.
3. Locate your **5G / Cellular** adapter. Because Cellular is metered, Plexo keeps it **OFF by default** to prevent accidental mobile data usage.
4. **Toggle 5G / Cellular ON** (this automatically lifts the Wi-Fi-only guardrail) and optionally tap the pencil icon to rename your adapters (e.g., `Home Fiber Wi-Fi` and `5G Cellular`).
5. Tap **+ New** (or share any download link from Chrome using Android's Share Sheet → **Plexo**).
6. Tap the probe magnifying glass to confirm **`Split bonding ready`** (`Accept-Ranges: bytes`), verify both Wi-Fi and 5G checkboxes are checked, and tap **Start Now**.
7. Tap the active download card to open the **Download Detail Screen**:
   - **Live Bonding Flow Canvas** animates packets streaming simultaneously from both Wi-Fi (Cyan) and 5G (Amber) into the unified Plexo hub.
   - **Per-Network Breakdown Table** displays live independent speed, active stream count, downloaded bytes, and share % for each adapter.
   - **192-Cell Chunk Map** color-codes active cells by the exact adapter currently downloading them.
8. **Hot-Unplug Resilience Test:** Turn off Wi-Fi mid-download. Plexo immediately detects `onLost`, reclaims Wi-Fi's unfinished chunks, and finishes the download over 5G with zero lost bytes. Turn Wi-Fi back on mid-download and watch it hot-plug back in automatically!

---

## Running Automated Tests

Run all JVM and Robolectric tests (including SocketFactory binding, ProbeService, Work-Stealing Scheduler, throttled parallel SHA-256 assembly, and Room DAO persistence):

```bash
gradle :app:testDebugUnitTest
```
