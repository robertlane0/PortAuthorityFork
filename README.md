# Port Authority Fork

A modernized fork of [Port Authority](https://github.com/aaronjwood/PortAuthority) — a fast Android port scanner and network discovery tool.

This fork raises `minSdkVersion` to 31 (Android 12), drops legacy platform compatibility code, updates dependencies, and fixes the approximate-location SSID bug. Host discovery relies on a JNI netlink ARP reader that requires `targetSdkVersion` 31 (pinned permanently — see [MIGRATION_PLAN.md](./MIGRATION_PLAN.md) for rationale).

## Features

- Heavily threaded LAN/WAN TCP port scanning
- LAN host discovery via ARP (native `ipneigh` library)
- Public IP discovery (open-source [API](https://github.com/aaronjwood/public-ip-api))
- MAC address vendor detection (OUI database)
- Custom port range scans
- Open discovered HTTP(S) services in browser
- Lightweight service fingerprinting (SSH/HTTP(S) server type and version)
- DNS record lookups (supports most record types)
- Wake-on-LAN for discovered hosts
- No ads, no analytics, no tracking

## Why a fork?

The original Port Authority is no longer actively maintained upstream. This fork:

- Raises the minimum API level from 19 to 31, removing ~10 years of backward-compatibility code
- Fixes a bug where approximate-only location permission on Android 12+ silently prevents SSID reading
- Updates OkHttp from 3.x to 4.x, JUnit, Mockito, and other dependencies
- Pins the NDK ABI set explicitly (`armeabi-v7a`, `arm64-v8a`, `x86_64`)
- Removes unused `androidx.legacy:legacy-support-v4`
- Future-proofs the `NETWORK_STATE_CHANGED_ACTION` broadcast receiver with `RECEIVER_NOT_EXPORTED`
- Drops the original donation/Play Store/F-Droid distribution links

## Prerequisites

- Android Studio (or command-line build)
- Android SDK 31 (`compileSdkVersion` / `targetSdkVersion`)
- NDK (for the `ipneigh` native library) — bundled via Android Studio or set `ANDROID_NDK_HOME`
- Java 8 (JDK 1.8)

## Tech Stack

| Component | Version |
|-----------|---------|
| AGP | 7.2.2 |
| Gradle | 7.3.3 |
| Java compatibility | 1.8 |
| OkHttp | 4.12.0 |
| minidns | 1.0.2 |
| LeakCanary | 2.9.1 (debug only) |
| `minSdkVersion` | 31 |
| `targetSdkVersion` | 31 (pinned) |
| `compileSdkVersion` | 31 |

## Architecture

```
app/
├── build.gradle              # Module build config + dependencies
├── proguard-rules.pro        # R8/ProGuard keep rules
└── src/main/
    ├── AndroidManifest.xml
    ├── c/                    # JNI native library (ipneigh)
    │   ├── Android.mk
    │   ├── Application.mk    # ABI pinning: armeabi-v7a, arm64-v8a, x86_64
    │   ├── ipneigh.c         # NETLINK_ROUTE ARP table reader
    │   ├── libnetlink.c/.h
    │   ├── ll_map.c/.h
    │   └── ...
    ├── java/com/aaronjwood/portauthority/
    │   ├── activity/         # Main, Preferences, Dns, LAN/WAN host screens
    │   ├── async/            # AsyncTasks for scanning, downloads, DNS, WOL
    │   ├── network/          # Wireless, Host, DNS/NetBIOS/mDNS resolvers
    │   ├── runnable/         # Threaded scan workers
    │   ├── utils/            # Constants, Errors, UserPreference
    │   ├── parser/           # OUI and port data parsers
    │   ├── adapter/          # Host list adapter
    │   ├── db/               # SQLite database (OUI + port data)
    │   └── response/         # Async response interfaces
    └── res/                  # Layouts, strings (i18n: de, fr, it, ja, nl, pt, sr, zh), themes
```

### Product Flavors

| Flavor | App ID suffix | Purpose |
|--------|---------------|---------|
| `free` | `.free` | Standard build |
| `donate` | `.donate` | Support version |

## Build & Run

```sh
# Build debug APK (free flavor)
./gradlew assembleFreeDebug

# Build debug APK (donate flavor)
./gradlew assembleDonateDebug

# Release build (R8 minified + shrunk)
./gradlew assembleFreeRelease

# Install on connected device/emulator
./gradlew installFreeDebug
```

Install the APK from `app/build/outputs/apk/` on an Android 12+ device.

### Build Configuration

- `lint.abortOnError = false` — lint failures won't block compilation
- Debug builds include LeakCanary for memory leak detection
- Release builds use `proguard-android-optimize.txt` + `proguard-rules.pro`

## Development

### SDK Note

`targetSdkVersion` is **permanently pinned at 31**. The native ARP host discovery (`ipneigh.c`) opens a `NETLINK_ROUTE` socket that is blocked by SELinux at higher target levels. See [MIGRATION_PLAN.md](./MIGRATION_PLAN.md) for the full rationale.

### Lint

```sh
./gradlew lint
```

Lint warnings do not fail the build. Only pre-existing warnings are expected.

### Tests

No test infrastructure exists. All testing is manual/device-based. Test dependencies (`junit:4.13.2`, `mockito-core:5.14.2`) are declared in `build.gradle` for future use.

### Dependency Management

```sh
./gradlew app:dependencies --configuration releaseRuntimeClasspath
```

## Performance Notes

- Port scanning is heavily threaded — uses a `ScheduledThreadPoolExecutor` with configurable thread count
- Host discovery typically completes in under 5 seconds on a local /24 subnet
- Scanning 1000 ports on a packet-dropping host: ~10 seconds
- Scanning all 65,535 ports on a packet-rejecting host: <30 seconds
- Thread count and timeouts are configurable in Settings

## Troubleshooting

| Symptom | Likely cause |
|---------|-------------|
| Host discovery finds no devices | SELinux blocking netlink — check `adb logcat \| grep avc` |
| SSID always shows as unavailable | Only approximate location was granted; grant precise (fine) location |
| Crash on large port range | Out of memory from too many threads — reduce port scan thread count in Settings |
| Cannot find known-open ports | Increase port scan timeout in Settings |
| Security app warns about email | Port 25 (SMTP) is being scanned — benign; no data is transmitted |
| MAC address shows "Unavailable" | Android 11+ restricts MAC access for non-privileged apps |

## License

[GNU General Public License v3.0](./LICENSE)

This is a fork of [Port Authority](https://github.com/aaronjwood/PortAuthority) by Aaron J Wood, also GPL v3.
