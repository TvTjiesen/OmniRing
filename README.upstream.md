# HyperZoomRing

LSPosed module that remaps the Xiaomi 17 Ultra's Master Zoom Ring gestures to custom system actions.

## Features

- **Direction detection** — Clockwise (CW) and counter-clockwise (CCW) via mt350x optical tracking sensor
- **4 gesture types** — CW Slow, CW Fast, CCW Slow, CCW Fast, each configurable
- **Built-in actions** — Volume up/down, brightness up/down, launch app
- **Camera passthrough** — Zoom ring works normally when camera app is in foreground
- **Camera wake block** — Prevents zoom ring from accidentally launching the camera
- **Diagnostic mode** — Real-time event log with direction, speed, and intensity data

## Requirements

- Xiaomi 17 Ultra
- LSPosed / LSPatch
- Android 15+ (API 35+)

## How It Works

The module hooks `InputManagerService.filterInputEvent` in system_server to intercept zoom ring input events (`maxic, focus_ring_bridge` device). Rotation direction is obtained from the `mt350x OPTICALTRACKING` sensor via `SensorManager`. Camera launch blocking hooks `ShortCutActionsUtils.launchCamera` with a time-window guard to only block zoom-ring-triggered launches.

```
Zoom Ring Hardware (Maxic MT350x)
  ├─ SMP2P → focus_ring_bridge.ko → InputManagerService [Hook: intercept & consume]
  └─ Sensor HAL → SensorManager → optical_tracking sensor [direction: CW/CCW]
```

## Building

```bash
./gradlew :app:assembleDebug
```

## License

GPL-3.0 — see [LICENSE](LICENSE)
