# Port Authority: minSdk 31 Modernization Plan

**targetSdk stays pinned at 31 — permanently. This is no longer a plan to reach API 36.**

**Repo analyzed:** `PortAuthorityFork` (`com.aaronjwood.portauthority`)
**Current state:** `compileSdk 31` / `targetSdk 31` / `minSdk 19`, AGP 7.2.2, Gradle 7.3.3, versionCode 67 (`2.4.5`), two flavors (`free`, `donate`), one JNI native library (`ipneigh`) built with `ndk-build`.
**Decided change:** raise `minSdkVersion` to 31. Leave `targetSdkVersion` at 31 indefinitely.

---

## 0. What changed and why this document looks different now

The original ask was a plan to get this app to `targetSdk 36`. Over the course of investigating that, two things became clear:

1. `ScanHostsAsyncTask`'s native ARP/neighbor-table reader (`nativeIPNeigh`, in `app/src/main/c/ipneigh.c`, using a raw `NETLINK_ROUTE` socket) is blocked by SELinux once the app crosses into a modern `targetSdkVersion`'s stricter app domain — confirmed reproducing on real Android 16 devices at a modern target level, not just Android 12/13 as the original 2022 fix addressed.
2. The fix that worked in 2022 — pinning `targetSdkVersion` at 31 to stay in the older, more permissive SELinux compatibility domain — **still works today**, including on Android 16. That's the actual justification for this pivot: raising `targetSdk` breaks host discovery; keeping it at 31 doesn't.

Given that host discovery is this app's core value proposition, the decision made is to **permanently stop advancing `targetSdkVersion`** rather than trade it away for API-36 compliance. `minSdkVersion` is being raised to 31 anyway, independent of that decision — it removes a decade of Android-4-through-11 compatibility code and constraints for comparatively little cost, discussed in Section 3.

This document replaces the earlier targetSdk-36 migration plan. Most of that plan's content — the staged 33→34→35→36 rollout, the `BroadcastReceiver` export-flag fix, edge-to-edge rework, 16 KB native page-size alignment — was gated on crossing specific `targetSdkVersion` thresholds that this app will no longer cross, so it's been dropped rather than carried forward as dead weight. If this decision is ever revisited, that earlier plan is still a reasonable starting point and can be reconstructed.

---

## 1. The tradeoff, stated plainly

**What pinning `targetSdk 31` buys you:** `nativeIPNeigh`'s netlink ARP read keeps working reliably — not "best-effort," actually working — across the full range of devices this app will run on (Android 12 through the current OS), because the app stays in the legacy-permissive SELinux app domain regardless of which physical OS version the device is running. This is the same mechanism, and the same justification, as the original 2022 fix.

**What it costs you:** Google Play's target API level requirement. Play requires apps to target within roughly one year of the current major Android release to remain eligible for new installs and updates; this ratchets forward with every annual Android release. `targetSdk 31` (Android 12, released 2021) is already well outside any reasonable reading of that window. Concretely, this likely means one or more of:

- The app may already be blocked from receiving further updates via the Play Console (or close to that point) — **check the Play Console policy status page for this app directly**; I can't see that from here.
- Even if updates are still technically possible today, this will stop being true on a predictable annual cadence going forward, not eventually — it's a matter of when, and "when" may already have arrived.
- Play may eventually stop showing the app to new users on qualifying devices, even if existing installs keep working.

I can't verify the exact current enforcement dates/mechanics without live access to Play Console policy documentation, and this project's own knowledge of exact deadlines has a cutoff — check `play.google.com/console` → Policy → target API level requirements for the current, authoritative cutoff before treating this decision as settled.

**A mitigating factor already in place:** this project already ships on **F-Droid** in addition to Play (per the README), and F-Droid doesn't enforce a target API level policy at all. If Play distribution becomes non-viable under this decision, F-Droid remains a fully unaffected distribution channel. Worth a deliberate conversation at some point about whether Play stays a first-class distribution target long-term under a permanent `targetSdk 31` — happy to think through that with you separately if useful, but it's a distribution/business decision rather than a code one, so it's not resolved here.

---

## 2. The dependency this whole strategy rests on — and how to monitor it

Pinning `targetSdkVersion` is what keeps the app in the older SELinux app domain; that mapping is Android platform policy, not something this app controls. It's been stable since Android 12 through 16, but "stable so far" isn't the same as "guaranteed forever" — a future major Android release could, in principle, retire or tighten that legacy domain the same way Android 12 tightened the one before it. If that happens, host discovery breaks again with zero code change on this app's part.

Treat this as a standing operational dependency, not a one-time fix:

- Keep (or add) `avc: denied`-style logging/telemetry around the `nativeIPNeigh` call path so a regression shows up in crash/error reporting rather than silently as "host discovery just doesn't work" reports from users.
- Do a manual smoke test of host discovery on the newest available Android OS (device or emulator) around each annual Android release, specifically to catch this before users do.
- If it ever does break, the earlier targetSdk-36 plan's Section 1.3 options (TCP-connect-based liveness detection instead of ARP, dropping MAC/vendor lookup; or removing the native library outright) are the fallback path — worth keeping that reasoning in mind even though it's not the active plan today.

---

## 3. `minSdkVersion`: 19 → 31

This is being raised independent of the targetSdk decision, and it's a clean win:

```groovy
defaultConfig {
    minSdkVersion 31       // was 19
    targetSdkVersion 31    // unchanged — see Section 1 for why this is pinned, not an oversight
    versionCode 68          // bump
    versionName "2.5.0"     // bump
    ...
}
```

Add a comment at the `targetSdkVersion` line pointing at this document (or at least at the 2022 commit message) — the whole point of this plan is that a future contributor shouldn't "helpfully" bump `targetSdkVersion` during some unrelated dependency update without understanding why it's pinned.

### 3.1 What raising minSdk unlocks
- **OkHttp.** The existing dependency comment — `implementation 'com.squareup.okhttp3:okhttp:3.14.9' // Anything past 3.12.x will break our Android 4 support!` — only existed to protect `minSdk 19`. With the floor now at 31, bump OkHttp to a current release. Real win: several years of TLS/HTTP/2/security fixes land in the networking stack behind `DownloadOuisAsyncTask`, `DownloadPortDataAsyncTask`, and `WanIpAsyncTask` for free. Double check `proguard-rules.pro`'s OkHttp-era rules still make sense against whatever version you land on.
- **Dead permission-branching code.** `ssidAccess()` in `MainActivity` branches on `Build.VERSION.SDK_INT >= O` (26) and again on `>= Q` (29) to decide between the Android 8–9 coarse-location SSID flow and the Android 10+ fine-location flow. With `minSdk 31`, every supported device is already past both checks — the entire coarse-location branch and the `"8-9"` vs `"10+"` version-string logic is dead code, worth deleting.
- **A real bug worth fixing while you're in that code:** since Android 12, users can grant only *approximate* location, which isn't sufficient to read the connected SSID. The current code doesn't distinguish that case — it proceeds to `getSSID()` if *any* location permission was granted. With `minSdk 31`, Android 12+ is the only case that exists, so this is worth prioritizing: check for `ACCESS_FINE_LOCATION` specifically and show a clearer message if the user only granted approximate. Keep `ACCESS_COARSE_LOCATION` declared in the manifest alongside `ACCESS_FINE_LOCATION` regardless — Android requires both to be requested together for the system to even offer the precise/approximate toggle in its permission dialog.
- **Toolchain headroom.** AGP/Gradle/`buildToolsVersion` are all quite dated (7.2.2 / 7.3.3 / 30.0.3) independent of any SDK-target question — worth a modest, low-risk bump for build tooling security and maintenance even though there's no longer a hard requirement to reach an AGP version that understands `compileSdk 36`. `compileSdk` itself can also be bumped somewhat (e.g., to whatever a current AndroidX/Material release expects) without touching `targetSdkVersion` at all — compile-time API surface and runtime behavior gating are independent, and most AndroidX libraries only care about the former. Treat this as optional maintenance, not required.

### 3.2 Native library — ABI pinning still worth doing, page-size work is not
Separate from anything targetSdk-related: `app/src/main/c/` has no `Application.mk` and `app/build.gradle`'s `externalNativeBuild` block has no `abiFilters`, so the ABI set actually shipped in `ipneigh.so` today is whatever `ndk-build`'s implicit defaults resolve to. That's worth pinning explicitly regardless of this decision (confirm `arm64-v8a` and `armeabi-v7a` are both actually being built, drop 32-bit `x86` if unused):

```
# app/src/main/c/Application.mk
APP_ABI := armeabi-v7a arm64-v8a x86_64
APP_PLATFORM := android-31
```

The 16 KB native page-size alignment work from the earlier plan is **not** required here — that Play requirement is keyed to targeting Android 15+, which this app deliberately isn't doing. No need to touch NDK version pinning or linker flags for that reason; ABI correctness is the only native-build item left in scope.

### 3.3 Other low-cost cleanup (unrelated to the targetSdk decision either way)
- **Remove `androidx.legacy:legacy-support-v4:1.0.0`** — not referenced anywhere in `java/` or `res/` (the `android.support.PARENT_ACTIVITY` manifest meta-data key is just a string constant, not a real dependency on this artifact).
- **Bump test dependencies** — `mockito-core:1.10.19` predates Mockito 2 by years; `junit:junit:4.13` → `4.13.2`. Unrelated to SDK targets, just overdue.
- **`ConnectivityManager`/`NetworkInfo` modernization** — `Wireless.isConnectedWifi()` and `MainActivity`'s receiver use `getNetworkInfo(ConnectivityManager.TYPE_WIFI)`/`WifiManager.EXTRA_NETWORK_INFO`, both deprecated since Android 10. Since the `BroadcastReceiver` export-flag requirement that would otherwise force you into this code (targetSdk 33+) no longer applies, this is now purely optional cleanup, not a blocker — worth tracking as debt, not scheduling as required work.
- **`ContextCompat.registerReceiver` with an explicit export flag** for the `WifiManager.NETWORK_STATE_CHANGED_ACTION` receiver is cheap, harmless at any targetSdk (the compat shim no-ops below API 33), and future-proofs the one piece of this app that would otherwise hard-crash if `targetSdk` were ever bumped past 33 later. Low cost, no downside — worth doing even though it's not required today.

---

## 4. Testing

Narrower than the original plan's multi-stage matrix, but the one thing that matters most is now **more** important, not less, since the whole strategy depends on it continuing to hold:

| What | Why |
|---|---|
| Host discovery (ARP path) on real devices spanning Android 12 through the newest shipping OS, **multiple OEMs, not just Pixel** | This is the load-bearing assumption of the entire plan (Section 2) — confirm it still works today, on current devices, before shipping this change |
| `adb logcat \| grep avc` during the above | Baseline signal to compare against if a future OS update ever regresses this |
| Permission-grant matrix: fine granted / coarse-only / denied | `ssidAccess()` changes in Section 3.1 |
| Both flavors (`free`, `donate`) | AppId suffixes differ; don't assume parity |
| Release (minified/shrunk) build | Confirm R8/ProGuard still behaves correctly against the bumped OkHttp and any other dependency changes |

---

## 5. Release checklist

- [ ] Confirm current Play Console target-API-level policy status for this app before doing anything else — this decision may already have consequences in flight
- [ ] Bump `minSdkVersion` to 31, add an explanatory comment next to the (unchanged) `targetSdkVersion 31` line
- [ ] Bump OkHttp off 3.14.9 to a current release; re-check ProGuard rules
- [ ] Delete the dead coarse-location branch in `ssidAccess()`; fix the approximate-location-only case
- [ ] Add `Application.mk` with explicit ABI pinning
- [ ] Remove unused `legacy-support-v4` dependency; bump JUnit/Mockito
- [ ] (Optional, cheap) Add the `RECEIVER_NOT_EXPORTED` flag to the `NETWORK_STATE_CHANGED_ACTION` receiver registration
- [ ] Run the full host-discovery test matrix from Section 4 before shipping
- [ ] Bump `versionCode`/`versionName`, ship
- [ ] Decide, separately, whether Play remains a first-class distribution target long-term under this decision (Section 1) — not blocking this release, but worth a deliberate answer at some point

