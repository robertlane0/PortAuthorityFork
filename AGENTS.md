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

## Migration plan (unfinished work)

`MIGRATION_PLAN.md` documents the modernization plan. Partially applied (minSdk raised, sdk versions cleaned). Still TODO:
- Bump OkHttp from `3.14.9` to current; re-check `proguard-rules.pro`
- Delete dead `minSdk < 31` coarse-location branch in `MainActivity.ssidAccess()`; fix approximate-location-only permission case
- Add `app/src/main/c/Application.mk` with explicit `APP_ABI := armeabi-v7a arm64-v8a x86_64`, `APP_PLATFORM := android-31`
- Remove unused `androidx.legacy:legacy-support-v4:1.0.0` dependency
- Bump test deps (JUnit 4.13→4.13.2, Mockito 1.10.19→current)
- (Optional) `ContextCompat.registerReceiver` with `RECEIVER_NOT_EXPORTED` flag for `NETWORK_STATE_CHANGED_ACTION`

## Key quirks

- `lint.abortOnError false` — lint warnings/errors do not block compilation
- Debug builds include `com.squareup.leakcanary:leakcanary-android:2.9.1`
- ProGuard: `dontwarn okhttp3.**`, `dontwarn okio.**`, `keepnames` for `PublicSuffixDatabase`
- Minimum OS is Android 12 (API 31) — any `Build.VERSION.SDK_INT` branching for pre-31 can be deleted
- NO unit tests exist — any testing done is manual/device-based
- Host discovery depends on SELinux legacy compatibility domain (pinned `targetSdk 31`); see `MIGRATION_PLAN.md` Section 2 for monitoring/debugging (`adb logcat | grep avc`)

## Commit conventions

Base work off the `development` branch per upstream conventions. PRs target `development`.

