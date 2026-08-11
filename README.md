# TW SpeedTrap

A free, ad-free, bilingual (Français / English) speed-camera voice alert app
for Taiwan. It runs in the background and speaks warnings while Google Maps
handles navigation — including over Bluetooth while Android Auto owns the car
screen. Built for personal use and shared with friends via GitHub Releases;
not published on Google Play.

**Status: work in progress.**

## What it does

- Speaks short FR/EN warnings when you approach a speed camera
  ("Radar fixe, 300 mètres, limite 60" / "Fixed camera, 300 metres, limit 60").
- Works fully offline once the camera database is downloaded.
- Updates its camera database weekly from Taiwanese government open data.
- No ads, no analytics, no account. Location never leaves the device.

## Data source and attribution

Camera locations come exclusively from Taiwanese government open data,
released under the [Open Government Data License, version 1.0
(政府資料開放授權條款—第1版)](https://data.gov.tw/license), which permits
derivative works with attribution:

- [測速執法設置點 (dataset 7320)](https://data.gov.tw/dataset/7320) — 內政部警政署
- [國道公路固定式測速照相地點 (dataset 13940)](https://data.gov.tw/dataset/13940)

資料來源：政府資料開放平臺 (data.gov.tw)。

This is a clean-room implementation: no data, code, assets, or audio from any
other speed-camera app is used.

## Building

Requirements: JDK 21, Android SDK (platform 36). Then:

```sh
./gradlew assembleDebug
```

The data pipeline (Python 3.12+, managed with [uv](https://docs.astral.sh/uv/))
lives in `pipeline/` — see its README once it lands.

## License

- **Code:** [MIT](LICENSE).
- **Camera data:** [政府資料開放授權條款—第1版](https://data.gov.tw/license)
  (Taiwan Open Government Data License v1.0) — separate from the code license.
