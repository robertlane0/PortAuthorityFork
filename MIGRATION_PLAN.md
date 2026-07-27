# Port Authority: Migration Plan to targetSdk 36 (Android 16)

**Repo analyzed:** `PortAuthorityFork` (`com.aaronjwood.portauthority`)
**Current state:** `compileSdk 31` / `targetSdk 31` / `minSdk 19`, AGP 7.2.2, Gradle 7.3.3, versionCode 67 (`2.4.5`), two flavors (`free`, `donate`), one JNI native library (`ipneigh`) built with `ndk-build`.

**Revision note:** the netlink/SELinux investigation from Section 1 has now been run against real Android 16 devices, and the block reproduces there too. Given that, the decision has been made to **raise `minSdkVersion` from 19 to 31** rather than keep supporting the pre-Android-12 install base. This document has been updated to reflect that — see Section 1 for what this does and doesn't fix, and Section 7.1 for the dependency cleanup it unlocks.

This plan is based on a direct read of the project source, not a generic checklist. A few things found in the repo materially change the shape of this migration, most importantly a **2022 commit that deliberately froze `targetSdkVersion` at 31** to work around an Android 12/13 SELinux regression in the app's core host-discovery feature. That issue has to be resolved before anything else here matters — everything else in this plan is fairly standard AGP/SDK-bump work.

---

## 1. The one risk that actually matters: netlink ARP discovery vs. SELinux

`ScanHostsAsyncTask` calls `native int nativeIPNeigh(int fd)`, implemented in `app/src/main/c/ipneigh.c` (`System.loadLibrary("ipneigh")`). This is a stripped-down port of iproute2's `ip neigh`: it opens a raw `NETLINK_ROUTE` socket and issues `RTM_GETNEIGH` to read the kernel's ARP/neighbor table after `ScanHostsRunnable` primes it with TCP connect attempts across the subnet.

The commit history says exactly why `targetSdkVersion` never moved past 31:

> *"Target SDK 31 for now to fix broken Android 12/13 devices which break on host scans due to SELinux blocking nlmsg_getneigh"*

This is tied to the SELinux **app domain**, which Android derives from `targetSdkVersion` (`untrusted_app`, `untrusted_app_29`, `untrusted_app_30`, etc.) — apps declaring an older `targetSdkVersion` get placed in a more permissive legacy domain for compatibility; apps that opt into a newer target get the current, stricter domain, which has progressively locked down raw netlink access from app processes.

### 1.1 Confirmed: the block persists through Android 16
Testing against real Android 16 devices confirms the denial still reproduces at a modern `targetSdkVersion`. That's an important data point beyond "still broken": since Google Play's target-API policy makes permanently freezing `targetSdkVersion` at 31 unsustainable, the app was always going to have to cross into the stricter SELinux domain eventually — and once it does, **every currently-shipping Android version (12 through 16) hits the same block.** There is no longer an OS-version range where staying on the older, native-netlink code path buys anything.

### 1.2 What raising `minSdkVersion` to 31 does — and doesn't — fix
Raising the floor to 31 (Android 12, the version where this restriction begins) is the right call, but it's worth being precise about what it accomplishes: **it does not, by itself, restore ARP access.** What it does is remove the reason to keep two different host-discovery code paths alive — one native-netlink path that only ever worked on pre-Android-12 devices, and a hypothetical fallback for everyone else. With `minSdk 31`, *every* supported device is in the affected range, so the fallback strategy in Section 1.3 needs to be the **only** discovery mechanism, not a contingency for some users.

The upside is real: it collapses a genuinely awkward "does the SELinux domain trick even apply to this specific device" branch into a single, always-on code path, and it's what makes the rest of this plan (Section 7.1 especially) meaningfully simpler.

### 1.3 Host-discovery strategy going forward — decision needed
Since the native path is now confirmed to be dead weight for 100% of the app's supported OS range once `targetSdkVersion` reaches anywhere near current policy requirements, keeping `ipneigh` around only makes sense as a best-effort/legacy-carve-out play, not as the primary mechanism. The three options from the original draft of this plan still apply, but the calculus has shifted:

- **A — Keep the native lib as best-effort.** Still attempt `nativeIPNeigh`, still show MAC/vendor info on the rare device/OEM combination where it happens to succeed, fail gracefully (the `errAccessArp` path already exists) everywhere else. Keeps Section 5's NDK/16 KB alignment work in scope for comparatively little payoff, since testing so far suggests it won't succeed on any in-range device.
- **B — Replace neighbor-table reading with the TCP-connect sweep as the liveness signal.** `ScanHostsRunnable` already attempts a connection to every address in the subnet; treat "connection refused"/fast RST (not just "connection succeeded") as "host is up," and drop reliance on the ARP table for liveness. Host discovery keeps working; **MAC address and vendor (OUI) lookup for other hosts goes away**, since that data is only obtainable via the neighbor table and there's no other permitted API for an app to read another device's MAC address.
- **C — Drop `ipneigh`, `Android.mk`, and the whole `c/` source tree outright.** Same functional outcome as B, but also removes the entire NDK/`ndk-build` toolchain dependency from the project — no more `externalNativeBuild` block, no 16 KB alignment work in Section 5 at all, no NDK version pinning. Simplest end state, but it's a one-way door: re-adding native ARP reading later would mean rebuilding this from scratch.

I'd lean toward **B or C** given the test results — keeping option A alive mostly means carrying real NDK/16 KB compliance cost (Section 5) for a feature that's not expected to work for any currently-supported user. But dropping MAC-vendor detection is a genuine, user-visible feature loss (it's a listed feature in the README), not just a technical cleanup, so I don't want to silently pick one for you.

---

## 2. Recommended rollout shape: staged, not a single jump

Given the history above, don't go 31 → 36 in one PR. Each intermediate target level bundles distinct, unrelated behavior changes, and if something regresses you want to know *which* jump caused it.

| Stage | targetSdk | Why it's its own checkpoint |
|---|---|---|
| 1 | 33 | First version requiring explicit receiver-export flags (Section 4.1) — a hard crash if missed. Also the natural point to land the `minSdk 31` bump and whichever host-discovery strategy is chosen in Section 1.3. |
| 2 | 34 | Smaller behavior-change surface for this app (mostly foreground-service/exact-alarm changes that don't apply here) — cheap checkpoint. |
| 3 | 35 | **Edge-to-edge enforcement becomes non-optional.** This app has zero inset handling today (Section 6) — treat this as its own release. |
| 4 | 36 | 16 KB native page-size compliance (Section 5) and final target-level policy compliance. |

Each stage = its own branch, its own device test pass (Section 8), its own go/no-go decision. Given the app has no server backend and modest complexity outside the native piece, this can likely move fast once each stage is validated — but validate before merging forward.

---

## 3. Toolchain upgrades

Current versions can't reach `compileSdk 36` at all — AGP 7.2.2 doesn't understand it.

| Component | Current | Needed direction |
|---|---|---|
| Android Gradle Plugin | 7.2.2 | Modern AGP 8.x line that documents `compileSdk 36` support. Update in lockstep with Gradle below. |
| Gradle wrapper | 7.3.3 | A current Gradle 8.x release compatible with the AGP chosen. |
| `buildToolsVersion` | 30.0.3 | Latest build-tools matching compileSdk 36 (can usually be omitted and let AGP pick the default). |
| Java source/target compat | 1.8 | Bump to 11 (or 17, matching whatever the chosen AGP's JDK requirement is) — recent AGP requires a newer JDK to *run* Gradle even if `sourceCompatibility` stays lower. |
| NDK | implicit/default | Pin explicitly to a version with reliable 16 KB page alignment (Section 5) rather than "whatever's installed." |
| `sonarqube-gradle-plugin` | 3.0, pointed at `192.168.1.3:9000` | Unrelated to the SDK bump, but this is a dead/private CI config — either fix or strip it out while you're in the build files, so it doesn't silently fail CI. |

**Note on version numbers:** exact current AGP/Gradle/NDK point releases move on a roughly monthly cadence and this project's knowledge has a training cutoff, so don't hard-code the numbers above without checking `developer.android.com` / the AGP release notes at the time of implementation. Android Studio's **Upgrade Assistant** (Tools → AGP Upgrade Assistant) is the fastest way to get exact compatible versions and will also flag most of the build-file mechanics below automatically.

### 3.1 Concrete `app/build.gradle` changes
```groovy
android {
    compileSdkVersion 36
    // buildToolsVersion — omit and let AGP choose the matching default, or pin to latest.

    defaultConfig {
        minSdkVersion 31      // raised from 19 — see Section 1.2
        targetSdkVersion 36   // staged per Section 2, not jumped directly
        versionCode 68        // bump
        versionName "2.5.0"   // bump
        ...
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
    }
}
```

### 3.2 `gradle/wrapper/gradle-wrapper.properties`
Point `distributionUrl` at the Gradle version the chosen AGP requires (check AGP's own compatibility table — mismatches fail the build immediately with a clear error, so this is low-risk to get "roughly right" and let the first build tell you if it's wrong).

---

## 4. Manifest & component changes

### 4.1 BroadcastReceiver export flag (required at targetSdk 33+)
`MainActivity` does:
```java
intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
...
registerReceiver(receiver, intentFilter);
```
Context-registered receivers for **any** targetSdk-33+ app must declare exported/not-exported explicitly, or the app crashes at registration time on Android 13+. `NETWORK_STATE_CHANGED_ACTION` is a protected system broadcast (only the OS can send it), so this should register as **not exported**:

```java
ContextCompat.registerReceiver(
    this,
    receiver,
    intentFilter,
    ContextCompat.RECEIVER_NOT_EXPORTED
);
```
This is a hard crash, not a soft deprecation warning — fix it in the Stage-1 (targetSdk 33) branch before anything else in that stage.

### 4.2 Exported activities
`MainActivity` already has `android:exported="true"` (correctly, since it has the launcher intent-filter). `LanHostActivity`, `WanHostActivity`, `PreferencesActivity`, `DnsActivity` have no intent-filters, so they default to not-exported and need no change. Nothing to do here — just confirmed clean.

### 4.3 Permissions — no change required, but worth re-reading
The manifest only requests `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `INTERNET` — no storage permissions, no background location. Scoped storage and most Android 11–14 permission changes don't touch this app at all. Nothing to change here for API 36 specifically, but see Section 7.3 on the location-permission UX, which is worth revisiting while you're in this code anyway.

---

## 5. Native library: 16 KB page size compliance (targetSdk/compileSdk 36)

**Scope of this section depends on the Section 1.3 decision.** If option C is chosen, this entire section is moot — there's no native library left to align. If option A or B is chosen and `ipneigh.so` still ships (even as best-effort), the work below is still required, since 16 KB compliance is a shipping requirement independent of whether the library actually succeeds at runtime.

The app ships a real JNI library (`ipneigh`, built via `Android.mk`/`ndk-build`, sources: `ipneigh.c`, `libnetlink.c`, `ll_map.c`, etc.). Starting with the Android 15/16 generation, devices can ship with a 16 KB memory page size instead of 4 KB, and **Google Play requires apps with native libraries targeting recent API levels to be 16 KB–aligned** or they'll fail preflight / be rejected. This applies regardless of whether Section 1's netlink issue is fixed or worked around — as long as `ipneigh.so` ships, it must be compliant.

Concrete gaps found in the repo:

1. **No `Application.mk` exists.** There's no explicit `APP_ABI` declaration and no `abiFilters` in `app/build.gradle`'s `externalNativeBuild` block. This means the ABI set being shipped today is whatever `ndk-build`'s defaults resolve to with the installed NDK — that needs to be pinned explicitly, not left implicit, especially since 16 KB alignment support and behavior differs by ABI (it matters most for `arm64-v8a` and `x86_64`).
2. **No page-size flags are set.** Add an explicit `Application.mk` (or set `arguments` in the Gradle `externalNativeBuild.ndkBuild` block) targeting 16 KB-safe linking, and pin a recent NDK version known to default to 16 KB-aligned output for `arm64-v8a`/`x86_64`.

### 5.1 Action items
- Add `app/src/main/c/Application.mk`:
  ```
  APP_ABI := armeabi-v7a arm64-v8a x86_64
  APP_PLATFORM := android-21
  ```
  (Confirm the minimum ABI/platform list against whatever `minSdkVersion` is settled on in Section 7.1 — 32-bit `x86` can likely be dropped entirely; it's essentially unused on real devices at this point and simplifies the alignment work.)
- Pin the NDK version in `local.properties` / `app/build.gradle`'s `android.ndkVersion` rather than relying on "whatever Android Studio has installed."
- Rebuild and verify alignment with `zipalign -c -v -p 16 <apk>` or the `check_elf_alignment` script referenced in Android's native-library alignment guidance, before every release build from here on.
- **Test on an actual 16 KB page-size system image** (available as an emulator system image alongside recent API levels) — this is a real runtime behavior difference, not just a static analysis pass. A misaligned library can crash on load on a 16 KB device even though it links and runs fine in CI.
- Fold this into the Stage-4 (targetSdk 36) branch from Section 2, since it's the natural point to also finish resolving whether `ipneigh.so` even continues to ship (Section 1.2, option C would make this whole section moot).

---

## 6. Edge-to-edge enforcement (targetSdk 35+) — real UI rework

Starting at targetSdk 35, apps are laid out edge-to-edge by the system with **no opt-out**. This app currently has:
- No `fitsSystemWindows` anywhere in any layout.
- No `Toolbar`/`ActionBar` (theme is `Theme.AppCompat.NoActionBar` throughout) — instead, `activity_main.xml` has a hand-rolled header (`programTitle` TextView + `leftDrawerIcon` ImageView) sitting directly at the top of a `DrawerLayout` → `RelativeLayout`.
- Status bar tinting relies entirely on `colorPrimaryDark` in `styles.xml`, which stops being meaningful once the app draws behind the system bars.

Concretely, without changes: the custom header (app name + hamburger icon) will render **underneath the status bar**, and the bottom of the host list (`hostList` in `activity_main.xml`) will render underneath the gesture-navigation bar on gesture-nav devices. This affects `MainActivity`, `LanHostActivity`, `WanHostActivity`, `DnsActivity`, and `PreferencesActivity` — every screen in the app uses this same theme family.

### 6.1 Action items
- Adopt `androidx.activity`'s edge-to-edge helper (`enableEdgeToEdge()`) in each activity's `onCreate`, or manage it manually with `WindowCompat.setDecorFitsSystemWindows(window, false)` — the former is simpler and handles status/nav bar icon contrast for you.
- Add a `ViewCompat.setOnApplyWindowInsetsListener` on the root container of each layout (the `DrawerLayout` in `activity_main.xml`, and the root views of the other four activity layouts) and apply the returned insets as padding to the header (top) and the list/content area (bottom), rather than hard-coded `padding="@dimen/activity_default_margin"` on the whole screen as today.
- Bump `androidx.appcompat` (Section 7) — recent versions integrate more cleanly with the edge-to-edge APIs and `WindowInsetsControllerCompat` for status-bar icon light/dark contrast, which this app will now need to set manually since it can no longer just rely on `colorPrimaryDark`.
- This is genuinely a UI/QA pass, not a one-line fix — budget real time for it, and treat Section 2's Stage 3 (targetSdk 35) as a dedicated visual-QA release: test on both gesture-nav and 3-button-nav devices, portrait and the existing `layout-land` variant, and at least one device with a display cutout/notch.

---

## 7. API cleanup worth doing while the code is open

None of these are hard blockers for reaching targetSdk 36 today, but they're either adjacent deprecations that will matter soon, or debt that makes the rest of this migration harder if left alone.

### 7.1 `minSdkVersion 19` and OkHttp 3.14.9
The dependency comment is explicit about the tradeoff already being made:
```groovy
implementation 'com.squareup.okhttp3:okhttp:3.14.9' // Anything past 3.12.x will break our Android 4 support!
```
Staying on OkHttp 3.14.9 to support Android 4.4 devices means missing several years of TLS, HTTP/2, and security fixes in the networking stack this app depends on for its download features (`DownloadOuisAsyncTask`, `DownloadPortDataAsyncTask`, `WanIpAsyncTask`). Given how small the remaining Android 4.4 install base is by now, this is worth a deliberate decision rather than default inertia: **raise `minSdkVersion`** (even a modest bump, e.g. to 21+, unlocks modern OkHttp and simplifies TLS handling) or **explicitly keep 19 and document why** the security tradeoff is accepted. Either way, decide this once, in writing, rather than let it be an accident of an old comment.

### 7.2 `ConnectivityManager`/`NetworkInfo` (fully deprecated since API 29)
`Wireless.isConnectedWifi()` and `MainActivity`'s receiver both use `getNetworkInfo(ConnectivityManager.TYPE_WIFI)` / `WifiManager.EXTRA_NETWORK_INFO`, both deprecated since Android 10 and already unreliable there for reasons unrelated to this migration (the broadcast intentionally omits SSID/BSSID for privacy on 10+). Migrate to `ConnectivityManager.registerDefaultNetworkCallback`/`registerNetworkCallback` with a `NetworkCallback`. Not a hard requirement for API 36 (the old APIs still function), but it's exactly the kind of pre-existing rough edge that gets **more** likely to break with each future SDK bump — worth fixing in the same pass as Section 4.1's receiver-flag fix, since you're already touching this exact code path.

### 7.3 Location-permission UX for SSID reading
`ssidAccess()` in `MainActivity` requests `ACCESS_FINE_LOCATION` unconditionally on Android 10+. Since Android 12, users can grant only **approximate** location, which is not sufficient to read the connected SSID — the current code doesn't appear to branch on that case (it just goes to `getSSID()` if any location permission was granted). Worth adding a check (`ACCESS_FINE_LOCATION` specifically, not just "any location permission") and a clearer message if the user only grants approximate. Not an API-36 hard requirement, but adjacent enough to the permission code you'll be reading during this work that it's cheap to fix now.

### 7.4 `AsyncTask` (deprecated, not yet removed)
Used pervasively (`ScanHostsAsyncTask`, `ScanPortsAsyncTask`, `DownloadAsyncTask` and its subclasses, `WolAsyncTask`, `DnsLookupAsyncTask`, `WanIpAsyncTask`). Still functions at API 36, so this is **not a blocker** — call it out as tracked technical debt rather than in-scope work for this migration, unless the team wants to fold an `ExecutorService`/coroutines rewrite into the same effort.

### 7.5 Unused dependency
`androidx.legacy:legacy-support-v4:1.0.0` doesn't appear to be referenced anywhere in `java/` or `res/` (the `android.support.PARENT_ACTIVITY` meta-data key in the manifest is just a string constant, not a real dependency on this artifact). Worth removing while touching the dependency block in Section 3 — one less deprecated artifact to carry forward, and one less thing Jetifier has to process.

### 7.6 Test dependencies
`mockito-core:1.10.19` predates Mockito 2's inline mocking and modern JDK support by years; `junit:junit:4.13` can bump to `4.13.2`. Not related to `targetSdk` at all, but a modern AGP/JDK combination is a natural forcing function to also modernize the test stack, since very old Mockito versions are a common source of mysterious failures against newer JDKs.

---

## 8. Testing matrix

Given Section 1's history, this app's testing needs are unusually device-sensitive for its size — the regression that matters most is OEM/SELinux-policy-dependent, not something a single emulator run will catch.

| Layer | What to test | Why |
|---|---|---|
| Emulators, API 31/33/34/35/36 | Full feature pass on stock AOSP images | Baseline behavior-change coverage per Section 2's stages |
| **16 KB page-size system image** (API 35+) | Native library load + host discovery | Section 5 — this is a distinct runtime environment from the 4 KB default images |
| ≥2 physical OEM devices per Android version tested (not just Pixel) | Host discovery specifically; capture `adb logcat \| grep avc` | Section 1 — the original regression was OEM SELinux policy-flavored |
| Gesture-nav **and** 3-button-nav devices | Full visual pass, both orientations, incl. `layout-land` | Section 6 |
| Permission-grant matrix: Fine granted / Coarse-only / Denied | SSID/BSSID display and the `ssidAccess()` dialog flow | Section 7.3 |
| Both flavors (`free`, `donate`) | Full smoke test each | AppId suffixes differ; don't assume parity |
| Release (minified/shrunk) build specifically | Full smoke test, not just debug | R8 config hasn't been re-validated against newer defaults; `proguard-rules.pro` only has OkHttp-era rules today |

---

## 9. Release & rollout

1. Bump `versionCode`/`versionName` per stage (Section 2) — ship each stage as its own release, not one giant jump, so a regression is attributable.
2. Use Play Console **staged rollout** (start small, e.g. 5–10%) for the targetSdk 35 (edge-to-edge) and targetSdk 36 (native/16 KB) stages specifically — these are the two stages with the highest chance of a visible or crash-level regression.
3. Watch crash/ANR rate and the `avc denied` signal (if you've instrumented for it) closely during rollout of whichever stage first re-enables the netlink path.
4. Update the Play Console **Data safety** section if the location-permission justification text changes as part of Section 7.3.
5. This project is also distributed via **F-Droid** — its build recipe pins its own tool versions independently of what's in this repo's `gradle.properties`/wrapper. Coordinate the F-Droid metadata update (NDK/AGP versions, reproducible-build settings) as a separate follow-up once the Play build is validated, since F-Droid's build environment needs to independently support whatever NDK/AGP versions you land on for Section 5.
6. Google Play's target-API-level policy requires the app to target within one level of the current API within a rolling window after each new Android release — treat Section 2's staged plan as the *baseline* pace, not a one-time project, so the app doesn't end up frozen at a stale target again the way it was after 2022.

---

## 10. Condensed execution checklist

- [ ] **Investigate** the netlink/SELinux risk in isolation (Section 1) — blocks everything else
- [ ] Decide fallback strategy (A/B/C) for `ipneigh` if the denial reproduces
- [ ] Upgrade AGP + Gradle wrapper + JDK compat to a version supporting `compileSdk 36` (Section 3)
- [ ] Fix `BroadcastReceiver` export flag (Section 4.1) — required at targetSdk 33
- [ ] Ship Stage 1 (targetSdk 33), full device matrix pass
- [ ] Ship Stage 2 (targetSdk 34), regression pass
- [ ] Add `Application.mk` / pin ABIs & NDK version, verify 16 KB alignment (Section 5)
- [ ] Implement edge-to-edge insets handling across all 5 activity layouts (Section 6)
- [ ] Ship Stage 3 (targetSdk 35), dedicated visual QA pass
- [ ] Complete Stage 4 (targetSdk 36), full native + device matrix pass including 16 KB emulator image
- [ ] Decide & document `minSdkVersion`/OkHttp tradeoff (Section 7.1)
- [ ] Migrate `ConnectivityManager`/`NetworkInfo` usage (Section 7.2)
- [ ] Fix approximate-location handling in `ssidAccess()` (Section 7.3)
- [ ] Remove unused `legacy-support-v4` dependency, bump test deps (Sections 7.5–7.6)
- [ ] Re-validate ProGuard/R8 rules against the new AGP/R8 defaults on a real release build
- [ ] Staged Play Console rollout per stage, F-Droid metadata follow-up

