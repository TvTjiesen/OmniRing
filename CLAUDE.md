# CLAUDE.md

> **READ [`HANDOVER.md`](HANDOVER.md) FIRST — it is the single source of truth for this project.**
> It carries the device safety gates, the verification discipline (install ≠ loaded ≠ loaded-new),
> the architectural invariants, what's proven vs. waiting on HyperOS 3 receipts, and what the owner
> has already decided but **not yet approved for implementation**. Do not start work from memory or
> from this file alone.

This file provides guidance to coding agents when working in this repository. Keep it pointing at
`HANDOVER.md`; don't duplicate content here (two live docs drift apart — that's explicitly banned).

## What This Is

LSPosed/Xposed module for the Xiaomi 17 Ultra (Leica edition) that turns the **Master Zoom Ring**
into a general-purpose dial: scrolling lists crown-style, video seek, volume / brightness, etc.
It runs inside `system_server`. GPL-3.0 fork of NextAlone/HyperZoomRing — the modification notice
required by GPL §5a lives in [`MODIFICATIONS.md`](MODIFICATIONS.md) and must keep being appended.

Installed application ID since 2026-10-31: `io.github.tvtjiesen.omniring`
(`namespace` / Kotlin package is still `xyz.nextalone.hyperzoomring` — those are different things).

## Build

```bash
source /e/dev/env.sh                      # adb / python / SDK live here
./gradlew --offline :app:testDebugUnitTest   # baseline: 219 assertions, 0 failures
./gradlew --offline :app:assembleDebug
./gradlew --offline :app:assembleRelease                     # dual-declaration lane
./gradlew --offline -Phzr.legacyOnly=true assembleRelease    # legacy-only lane (testers / store)
python tools/i18n_check.py                 # four locales must stay in sync
```

Min SDK 35 (Android 15) · compile SDK 37 (Android 17) · Java 21 · Kotlin 2.3 · Compose + Miuix.
No `KEYSTORE_FILE` env vars → release falls back to the **debug keystore** on purpose (so beta
installs can keep updating in place). Formal release signing is a double-click: `tools/sign_key.bat`
(see `docs/RELEASE-KEY-HOWTO.md`). Never route that passphrase through an agent or a file.

Deploy/verify by script, not by hand: `SER=<serial> bash tools/deploy_verify.sh`
(and `tools/legacy_bench_verify.sh` for the old-LSPosed lane).

## Architecture (read `HANDOVER.md` §4 for the invariants)

**Two module entries** — `assets/xposed_init → HookEntry` (YukiHook / legacy) and
`META-INF/xposed/{module.prop,java_init.list} → CrownModule` (libxposed API 102). LSPosed 2.2.1 uses
the 102 one; **LSPosed ≤ v1.11.0 loads nothing at all when both are declared**, which is why the
`legacyOnly` build lane exists. Probe `hzr_entry` reports which entry actually ran.

**Hook layer** (`hook/`) — `InputInterceptorHook.startRuntime()` is the part shared by both entries
(sensor listener, config channel, scene detection); new behaviour goes into the shared segment.
`CrownHooks` installs the 102-side hooks. Both hook `InputManagerService.filterInputEvent` /
`injectInputEvent` / `interceptKeyBeforeQueueing` and `ShortCutActionsUtils.launchCamera`.
`SensorRingDriver` drives the `mt350x OPTICALTRACKING` sensor path (the only one that works on
HyperOS 4, where `InputFilterEnabled=false`).

**Ring logic** (`ring/`) — pure, testable: `ZoomRingDetector` (direction/backlash/peak decay),
`CrownModel` (the tuned feel parameters), `RingSourceArbiter` (lease-based arbitration between the
two event sources so HyperOS 3 doesn't execute twice — fail-open, deliberately not a hard disable),
`ForegroundGate` (camera + app-transition gates), `ScrubHoldModel` (video seek bar drag).

**Config** (`config/`) — `ConfigManager` over a `PrefsStore` abstraction. The system-server side
**cannot read the app's private prefs**, so config is pushed by the App over a signature-level
broadcast (`ConfigPayload` / `ConfigSync`) and mirrored into `Settings.Global` (`hzr_snapshot`) for
boot self-healing. Action names and the permission name derive from `BuildConfig.APPLICATION_ID` —
hard-coded literals there are regression-prone and pinned by tests.

**Heartbeat / probes** (`util/Heartbeat.kt`, `hook/DebugProbe.kt`) — the only trustworthy liveness
evidence. Payload fields are append-only and `;`-separated. See `HANDOVER.md` §2 before believing
any "it works" claim.

**UI** (`ui/`) — Compose + Miuix (iOS-style top bar collapse, liquid-glass bottom bar, cold-start
crown animation in `ui/launch/`). Compose body text is **not** exposed to `uiautomator` — never
guess tap coordinates on it.

## Conventions

- Every delivery round appends to `MODIFICATIONS.md` (GPL §5a) and bumps `DebugProbe.BUILD_TAG`
  (`cfgNN`) plus `versionCode`; tuned parameters get baked into code defaults, not left in debug keys.
- Local commits only; do not push (owner's standing instruction).
- Taste and feel are the owner's call; device installs, credentials, irreversible actions and any
  UI-injection on his daily phone are gated on him being present.
