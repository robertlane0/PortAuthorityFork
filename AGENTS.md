# Port Authority Fork — Agent Guide

## Project structure

- Single Android module `:app` — AGP 7.2.2 / Gradle 7.3.3 / Java 1.8
- Two product flavors: `free` (appId suffix `.free`) and `donate` (suffix `.donate`)
- Native library `ipneigh` built via `ndk-build` from `app/src/main/c/Android.mk` — no `Application.mk`, no `abiFilters` (needs pinning per migration plan)
- No test directory exists (`app/src/test/` is absent); no test infrastructure set up
- No CI workflow files

## Build & verify

```sh
./gradlew assembleFreeDebug          # build free flavor debug APK
./gradlew assembleDonateDebug        # build donate flavor debug APK
./gradlew assembleFreeRelease        # release build (R8 minified, shrunk)
./gradlew lint                       # runs lint (abortOnError false, failures won't fail build)
```

Gradle config: `android.enableJetifier=true`, `android.useAndroidX=true`, caching + VFS watch enabled.

## Current SDK state

| Setting | Value | Why |
|---------|-------|-----|
| `compileSdkVersion` | 31 | Pinned with `targetSdk` |
| `targetSdkVersion` | 31 | **Pinned permanently** — required for native ARP host discovery to work via netlink socket (SELinux legacy domain). Do not bump. |
| `minSdkVersion` | 31 | Recently raised from 19. See `MIGRATION_PLAN.md` Section 3. |

See `MIGRATION_PLAN.md` for the full rationale on the permanent `targetSdk 31` pin.

## Migration plan — complete minSdk 31 cleanup

`minSdkVersion` is already bumped to 31 in `app/build.gradle`. The following work completes the cleanup that the bump unlocks, per `MIGRATION_PLAN.md` Section 3. Each step is ordered to avoid merge conflicts or broken intermediate states.

### Step 1 — Add explanatory comment to `targetSdkVersion` in `app/build.gradle`

`app/build.gradle:23` — change:
```groovy
targetSdkVersion 31    // Pinned permanently — see MIGRATION_PLAN.md Section 1
```

### Step 2 — Bump OkHttp `3.14.9` → current

- **`app/build.gradle:81`** — update the version and remove the Android-4-era comment:
  ```groovy
  implementation 'com.squareup.okhttp3:okhttp:4.12.0'
  ```
- **`app/proguard-rules.pro`** — verify existing rules still apply. Newer OkHttp may no longer trigger the `javax.annotation` warnings, so `-dontwarn javax.annotation.**` can potentially be removed; confirm with a release build.
- No Java source changes needed — `DownloadAsyncTask.java` and `WanIpAsyncTask.java` use the stable public API.

### Step 3 — Refactor `MainActivity.ssidAccess()` + fix approximate-location bug

**`app/src/main/java/.../activity/MainActivity.java`** — lines 153–197:
- Delete the outer `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)` guard (line 160) — always true at minSdk 31.
- Collapse the `>= Q` branches: at minSdk 31, every device is API 29+. Remove the `version = "8-9"` / `"10+"` string logic, the `COARSE_LOCATION_REQUEST` (line 74) code path, the `UserPreference.saveCoarseLocationPermDiag()` call, and the `Manifest.permission.ACCESS_COARSE_LOCATION` fallback.
- **Fix the bug**: after the user grants permission, check specifically for `ACCESS_FINE_LOCATION` (not just any location permission). If only approximate is granted on API 31+, `getSSID()` returns `null`. Show a clear message directing the user to grant precise location.
- Keep `ACCESS_COARSE_LOCATION` declared in `AndroidManifest.xml` — Android requires both `COARSE` and `FINE` in the manifest for the precise/approximate toggle to appear in the system permission dialog.

**`app/src/main/java/.../utils/UserPreference.java`** — remove dead code:
- Delete `COARSE_LOCATION_PERM_DIAG` constant (line 26)
- Delete `saveCoarseLocationPermDiag()` (lines 38–41)
- Delete `getCoarseLocationPermDiag()` (lines 54–57)
- Rename remaining constants/variables for clarity (e.g. `FINE_LOCATION_PERM_DIAG` → `LOCATION_PERM_DIAG`)

### Step 4 — Create `app/src/main/c/Application.mk`

New file at `app/src/main/c/Application.mk`:
```makefile
APP_ABI := armeabi-v7a arm64-v8a x86_64
APP_PLATFORM := android-31
```

This pins the ABI set explicitly instead of relying on `ndk-build`'s implicit defaults. Drops unused 32-bit `x86`.

### Step 5 — Remove unused `androidx.legacy:legacy-support-v4:1.0.0`

- **`app/build.gradle:79`** — delete line.
- **`app/src/main/AndroidManifest.xml`** — replace the three `android.support.PARENT_ACTIVITY` meta-data entries (lines 32–34, 40–42, 48–50) with the native framework attribute:
  ```xml
  android:parentActivityName=".activity.MainActivity"
  ```
  The `android.support.PARENT_ACTIVITY` meta-data was a support-library fallback for `AppCompatActivity`'s "Up" navigation. The native `android:parentActivityName` has been available since API 16 — well within minSdk 31.

### Step 6 — Bump test dependencies

**`app/build.gradle:84-85`**:
```groovy
testImplementation 'junit:junit:4.13.2'
testImplementation 'org.mockito:mockito-core:5.14.2'
```

Note: Mockito 5.x uses the Java 8+ `mockmaker` by default. If the project's Java source compatibility (`JavaVersion.VERSION_1_8`) is sufficient, no extra config needed. Verify with a test run if any tests exist in the future.

### Step 7 — (Optional) `ContextCompat.registerReceiver` with `RECEIVER_NOT_EXPORTED`

**`app/src/main/java/.../activity/MainActivity.java`** (line ~720):
```java
// was: registerReceiver(receiver, intentFilter);
ContextCompat.registerReceiver(this, receiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
```

Add import:
```java
import androidx.core.content.ContextCompat;  // already imported at line 43
```

Harmless at any targetSdk (the compat shim is a no-op below API 33). Future-proofs the `NETWORK_STATE_CHANGED_ACTION` receiver against a potential future `targetSdk` bump past 33.

### Step 8 — Bump `versionCode` / `versionName`

**`app/build.gradle:24-25`**:
```groovy
versionCode 68
versionName "2.5.0"
```

### Verification

```sh
./gradlew assembleFreeRelease   # release build (R8 minified) — catches ProGuard regressions
./gradlew lint                  # expect clean or only pre-existing warnings
```

## Key quirks

- `lint.abortOnError false` — lint warnings/errors do not block compilation
- Debug builds include `com.squareup.leakcanary:leakcanary-android:2.9.1`
- ProGuard: `dontwarn okhttp3.**`, `dontwarn okio.**`, `keepnames` for `PublicSuffixDatabase`
- Minimum OS is Android 12 (API 31) — any `Build.VERSION.SDK_INT` branching for pre-31 can be deleted
- NO unit tests exist — any testing done is manual/device-based
- Host discovery depends on SELinux legacy compatibility domain (pinned `targetSdk 31`); see `MIGRATION_PLAN.md` Section 2 for monitoring/debugging (`adb logcat | grep avc`)

## Commit conventions

Base work off the `development` branch per upstream conventions. PRs target `development`.

