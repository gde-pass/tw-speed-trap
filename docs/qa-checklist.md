# Manual QA checklist — Taichung route

A ~30 minute ride covering every alert path. Best route: a loop in the
西屯/南屯 area — 臺灣大道, 五權西路, and 環中路 have a high camera density
(176 cameras in Taichung; check the map screen for your exact loop).

## Before riding

- [ ] Fresh install: onboarding shows location → background location →
      notifications → battery optimisation, in that order, each card
      disappearing once granted.
- [ ] Settings → database shows today's-ish data version and ~1900 cameras.
- [ ] "Check for update now" on Wi-Fi answers "Already up to date" (or
      updates).
- [ ] Language override to Français recreates the UI in French; TTS test
      alert speaks French (use mock location, see mock-location.md).
- [ ] Bluetooth: pair the helmet/headset, play music from another app.

## On the ride (phone mounted, screen on)

- [ ] Speed readout tracks the speedometer (GPS reads a few km/h lower —
      expected).
- [ ] Approaching a known fixed camera at steady speed: chime + one alert
      at roughly `lead-time × speed` before the camera; distance and limit
      in the utterance match the camera.
- [ ] Music ducks during the alert and resumes after — it must not stop.
- [ ] Passing the same camera again (loop back): it alerts again (re-armed).
- [ ] A camera on the opposite carriageway (one you can identify on the map
      with a bearing) does NOT alert.
- [ ] Ride a stretch with no cameras: silence, no phantom alerts.
- [ ] Screen off for 5+ minutes while riding: alerts still fire (this is
      the battery-optimisation test — the single most common failure).
- [ ] Google Maps navigating in front, TW SpeedTrap in background: both
      audio streams work; alert speaks over/after Maps guidance.
- [ ] Notification shows live speed and nearest-camera distance; Stop
      button in the notification stops detection.

## After

- [ ] Battery drain for the ride is acceptable (~5–8%/h screen-off is
      normal for 1 Hz GPS).
- [ ] `adb logcat -s DetectionService` (or a logcat app) shows one alert
      per approach, none doubled.
- [ ] Stop detection: notification disappears, GPS indicator goes away.
