# Data pipeline

Builds the camera database from Taiwanese government open data
([data.gov.tw](https://data.gov.tw), 政府資料開放授權條款第1版).

Sources (resource URLs are resolved live via the dataset API each run —
they change over time):

- [7320](https://data.gov.tw/dataset/7320) 測速執法設置點 — national fixed
  speed cameras (內政部警政署)
- [13940](https://data.gov.tw/dataset/13940) 國道公路固定式測速照相地點 —
  freeway radar cameras
- [130111](https://data.gov.tw/dataset/130111) 臺北市固定測速照相地點表 —
  Taipei fixed cameras; red-light-only devices become `red_light`
- [135957](https://data.gov.tw/dataset/135957) 臺北市智慧管理科技執法設備資料表 —
  Taipei tech enforcement (red-light intersections, parking, lane control)
- [25935](https://data.gov.tw/dataset/25935) 桃園市測速照相設備地點 —
  Taoyuan speed and red-light cameras
- [170673](https://data.gov.tw/dataset/170673) 臺中市科技執法取締地點 —
  Taichung tech enforcement
- [160171](https://data.gov.tw/dataset/160171)
  高雄市111年交通局建置科技執法設備設置地點 — Kaohsiung tech enforcement

Average-speed (區間測速) rows in any source are excluded from point import —
sections are hand-curated in `data/sections.yaml` with entry/exit pairs.

## Run

```sh
cd pipeline
uv sync
uv run build-db --out out \
  --geojson ../data/cameras.geojson \
  --assets-db ../app/src/main/assets/cameras.db \
  --cache out/cache          # optional: reuse downloads while iterating
```

Outputs:

- `out/cameras.db` — SQLite database the app ships/downloads
- `out/manifest.json` — schema/data version, count, SHA-256, download URL
- `out/unresolved.csv` — source rows without usable coordinates (never
  silently dropped)
- `../data/cameras.geojson` — committed, human-readable snapshot

## Tests

```sh
uv run pytest
```
