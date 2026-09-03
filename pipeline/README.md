# Data pipeline

Builds the camera database from Taiwanese government open data
([data.gov.tw](https://data.gov.tw), 政府資料開放授權條款第1版).

Sources (resource URLs are resolved live via the dataset API each run —
they change over time):

- [7320](https://data.gov.tw/dataset/7320) 測速執法設置點 — national fixed
  speed cameras (內政部警政署)
- [13940](https://data.gov.tw/dataset/13940) 國道公路固定式測速照相地點 —
  freeway radar cameras
- [100856](https://data.gov.tw/dataset/100856) 國道公路警察局闖紅燈照相地點 —
  freeway-police red-light cameras on interchange ramps (corridor-checked
  against the freeway's kilometre-marker rows)
- [130111](https://data.gov.tw/dataset/130111) 臺北市固定測速照相地點表 —
  Taipei fixed cameras; red-light-only devices become `red_light`
- [135957](https://data.gov.tw/dataset/135957) 臺北市智慧管理科技執法設備資料表 —
  Taipei tech enforcement (red-light intersections, parking, lane control)
- [25935](https://data.gov.tw/dataset/25935) 桃園市測速照相設備地點 —
  Taoyuan speed and red-light cameras
- [178168](https://data.gov.tw/dataset/178168) 桃園市科技執法設備地點 —
  Taoyuan tech enforcement
- [170673](https://data.gov.tw/dataset/170673) 臺中市科技執法取締地點 —
  Taichung tech enforcement
- Kaohsiung 115年 series (files on data.kcg.gov.tw, unreachable from GitHub
  CI — the weekly build keeps the last local snapshot for these):
  [176549](https://data.gov.tw/dataset/176549) 固定式違規照相科技執法設備
  (speed + red-light),
  [176555](https://data.gov.tw/dataset/176555) 不停讓行人,
  [176558](https://data.gov.tw/dataset/176558) 交通局建置科技執法設備,
  [176560](https://data.gov.tw/dataset/176560) 捷運局輕軌沿線,
  [176561](https://data.gov.tw/dataset/176561) 路口科技執法監測系統,
  [177827](https://data.gov.tw/dataset/177827) 租賃式車不停讓行人
- County sets: [172905](https://data.gov.tw/dataset/172905) 彰化,
  [178085](https://data.gov.tw/dataset/178085) /
  [178086](https://data.gov.tw/dataset/178086) 雲林,
  [178159](https://data.gov.tw/dataset/178159) 基隆,
  [156415](https://data.gov.tw/dataset/156415) /
  [172940](https://data.gov.tw/dataset/172940) 澎湖,
  [172174](https://data.gov.tw/dataset/172174) 苗栗,
  [159972](https://data.gov.tw/dataset/159972) 屏東,
  [178144](https://data.gov.tw/dataset/178144) 新竹市

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

## Upstream watch

```sh
uv run dataset-watch            # or --catalog path/to/export.csv to reuse a download
```

Diffs the data.gov.tw catalog export against `data/dataset_watch.yaml`
(new enforcement datasets, delisted sources, coordinate-less datasets
gaining coordinates). The monthly `dataset-watch` workflow runs it and
opens a GitHub issue on findings. After evaluating a reported dataset,
record it in the baseline so it stops being reported.
