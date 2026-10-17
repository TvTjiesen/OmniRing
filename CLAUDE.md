# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

LSPosed/Xposed module for Xiaomi 17 Ultra that intercepts the Master Zoom Ring hardware events and remaps them to custom system actions (volume, brightness, app launch). Hooks into `system_server` via YukiHookAPI.

## Build

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # release (needs KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD env vars)
./gradlew :app:testDebugUnitTest  # unit tests
```

Min SDK 35 (Android 15), compile SDK 36, Java 21, Kotlin 2.3.

## Architecture

**Hook layer** (`hook/`) — runs in `system_server` process:
- `HookEntry` — Xposed entry point (`@InjectYukiHookWithXposed`), loads config via `YukiHookPrefsBridge` and delegates to `InputInterceptorHook`
- `InputInterceptorHook` — hooks `InputManagerService.filterInputEvent` / `injectInputEvent` to intercept `MotionEvent` from the zoom ring device (`maxic, focus_ring_bridge`). Also hooks `ShortCutActionsUtils.launchCamera` to block accidental camera launch within 2s window
- `ZoomRingSensorListener` — reads `mt350x OPTICALTRACKING` sensor (type `33171136`) for CW/CCW direction

**Ring detection** (`ring/`) — pure logic, no Android dependencies (testable):
- `ZoomRingDetector` — sliding-window speed detection → classifies events into 4 `GestureType` values (CW_SLOW, CW_FAST, CCW_SLOW, CCW_FAST)
- `ZoomRingEvent` — data class carrying timestamp, scroll value, direction
- `ZoomRingConstants` — hardware IDs, sensor type, speed thresholds

**Action system** (`action/`):
- `Action` interface — `execute(context, intensity)` contract
- `ActionRegistry` — singleton registry of all available actions
- Concrete actions in `actions/` — `VolumeAction`, `BrightnessAction`, `LaunchAppAction`

**Config** (`config/ConfigManager`) — abstracts `SharedPreferences` vs `YukiHookPrefsBridge` via `PrefsStore` interface. Module host reads via `fromContext()`, hook side via `fromYukiPrefs()`.

**UI** (`ui/`) — Jetpack Compose + Miuix design system. `MainActivity` → navigation to `HomeScreen`, `GestureConfigScreen`, `DiagnosticScreen`.

## Key Dependencies

- **YukiHookAPI 1.3.0** — Xposed hook framework (with KSP code generation)
- **Miuix 0.8.8** (`top.yukonga.miuix.kmp:miuix-android`) — Xiaomi-style UI components
- **Xposed API 82** — compileOnly

## Event Flow

```
Zoom Ring HW → focus_ring_bridge input device → InputManagerService (hooked)
             → optical_tracking sensor → ZoomRingSensorListener (direction)
InputInterceptorHook: if not camera foreground → ZoomRingDetector → GestureType → ActionRegistry → Action.execute()
```

Camera passthrough: events pass through unmodified when `com.android.camera` or `com.xiaomi.camera` is the top activity.
