# TW SpeedTrap

A free, ad-free, bilingual (Français / English) speed-camera voice alert app
for Taiwan. It runs in the background and speaks warnings while Google Maps
handles navigation — including over Bluetooth while Android Auto owns the car
screen. Built for personal use and shared with friends via GitHub Releases;
not published on Google Play.

## What it does

- Speaks short FR/EN alerts approaching a camera:
  « Radar fixe, 300 mètres, limite 60 » / "Fixed camera, 300 metres, limit 60",
  with an optional chime, over the navigation audio channel (music ducks,
  Bluetooth helmets work, Android Auto keeps the screen).
- **1,900+ cameras** from government open data, fully **offline** once
  installed; the database self-updates weekly over Wi-Fi.
- Direction-aware (skips cameras facing the other way), speed-scaled alert
  distance, +10 km/h tolerance (Taiwan's enforcement margin) — all
  configurable.
- Average-speed sections (區間測速): entry/exit announcements and a
  projected-average warning, correct even through tunnels.
- No ads, no analytics, no account. Location never leaves the phone; the
  only network traffic is the database update check.

## Install (friends)

1. Install [Obtainium](https://github.com/ImranR98/Obtainium/releases) —
   it auto-updates apps straight from GitHub Releases.
2. In Obtainium: **Add App** → paste `https://github.com/gde-pass/tw-speed-trap`
   → leave "Include prereleases" **off** → Add. (Or import `.obtainium.json`
   from this repo.)
3. Open TW SpeedTrap and follow the four onboarding steps — **do not skip
   the battery-optimisation step**; it is what keeps alerts alive with the
   screen off.

Direct download: grab `tw-speed-trap-vX.Y.Z.apk` from
[Releases](https://github.com/gde-pass/tw-speed-trap/releases) and sideload.

## Data source and attribution

Camera locations come exclusively from Taiwanese government open data,
released under the [Open Government Data License v1.0
(政府資料開放授權條款—第1版)](https://data.gov.tw/license), which permits
derivative works with attribution:

- [測速執法設置點 (dataset 7320)](https://data.gov.tw/dataset/7320) — 內政部警政署
- [國道公路固定式測速照相地點 (dataset 13940)](https://data.gov.tw/dataset/13940)

資料來源：政府資料開放平臺 (data.gov.tw)。

This is a clean-room implementation: no data, code, assets, or audio from
any other speed-camera app is used. Map tiles © OpenStreetMap contributors.

## Building

Requirements: JDK 21, Android SDK platform 37.

```sh
./gradlew assembleDebug                                   # debug APK
./gradlew :detection:test :app:testDebugUnitTest          # unit + replay tests
./gradlew ktlintCheck detekt                              # lint
```

Release signing is environment-driven — see [docs/signing.md](docs/signing.md).
Releases are cut by pushing a `vX.Y.Z` tag; CI signs the APK and attaches it
to a GitHub Release.

## Repository layout

- `app/` — Android app (Compose, single activity; foreground detection
  service; TTS announcer; DataStore settings; WorkManager updater)
- `detection/` — pure-JVM alert engine (grid index, hysteresis, bearing
  filter, average-speed tracker) + GPX replay harness and its sample track
- `pipeline/` — Python data pipeline (uv): fetch → decode → reproject →
  dedupe → SQLite/GeoJSON/manifest. See [pipeline/README.md](pipeline/README.md)
- `data/` — committed GeoJSON snapshot of the camera database
- `docs/` — [signing](docs/signing.md), [mock-location
  testing](docs/mock-location.md), [ride QA checklist](docs/qa-checklist.md),
  [roadmap](docs/roadmap.md)
- `.github/workflows/` — CI, weekly data update, tag-triggered release

The camera database is refreshed weekly by CI; when the data changes, the
new SQLite + manifest replace the assets on the rolling
[`data` prerelease](https://github.com/gde-pass/tw-speed-trap/releases/tag/data)
and the app picks them up on its next check.

## Testing without riding

- `detection/` replay harness runs a GPX track through the engine in plain
  JUnit — see `GpxReplayTest`.
- On-device: [docs/mock-location.md](docs/mock-location.md).
- Real-route checklist: [docs/qa-checklist.md](docs/qa-checklist.md).

## License

- **Code:** [MIT](LICENSE).
- **Camera data:** [政府資料開放授權條款—第1版](https://data.gov.tw/license)
  (Taiwan Open Government Data License v1.0) — separate from the code
  license.
