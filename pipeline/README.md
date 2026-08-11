# Data pipeline

Builds the camera database from Taiwanese government open data
([data.gov.tw](https://data.gov.tw), 政府資料開放授權條款第1版).

Sources (resource URLs are resolved live via the dataset API each run —
they change over time):

- [7320](https://data.gov.tw/dataset/7320) 測速執法設置點 — national fixed
  speed cameras (內政部警政署)
- [13940](https://data.gov.tw/dataset/13940) 國道公路固定式測速照相地點 —
  freeway radar cameras

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
