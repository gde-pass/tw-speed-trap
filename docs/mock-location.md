# On-device testing with mock locations

Test alert behaviour on a real phone without riding.

## Setup (once)

1. Enable **Developer options** (tap Build number 7×).
2. Install a mock-location app — [GPS Emulator (github.com/mcastillof/FakeTraveler)]
   or any similar F-Droid app works.
3. In Developer options → **Select mock location app**, pick it.

## Testing a camera approach

1. Open the map screen in TW SpeedTrap and pick a camera to test
   (or use `data/cameras.geojson` in a GeoJSON viewer).
2. In the mock app, set a route that crosses the camera position at a
   realistic speed (≥ 40 km/h — below 15 km/h the bearing filter is
   deliberately disabled and behaviour differs).
3. Start detection in TW SpeedTrap, then start the mock route.
4. Expected: chime + one spoken alert entering the alert radius (the
   below-100 km/h distance setting, 300 m by default; the above-100 km/h
   distance on a mocked highway run), no re-alert while inside, re-arm only
   after leaving 1.5× the radius, and — if enabled — the descending
   all-clear chime once the camera falls behind.

`adb logcat -s DetectionService` shows each alert with camera id and
distance — this is the ground truth when the speaker is off.

## Emulator alternative

```sh
adb emu geo fix <longitude> <latitude>   # note: longitude first
```

Repeated fixes ~1 s apart simulate movement; emulated GPS reports speed 0,
so the alert radius is the below-100 km/h distance setting (300 m default).
