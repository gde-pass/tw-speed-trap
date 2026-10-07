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
- [100855](https://data.gov.tw/dataset/100855) 國道公路警察局交流道區重點違規錄影地點 —
  freeway-police lane-line cameras at interchanges (`tech`)
- [130111](https://data.gov.tw/dataset/130111) 臺北市固定測速照相地點表 —
  Taipei fixed cameras; red-light-only devices become `red_light`
- [135957](https://data.gov.tw/dataset/135957) 臺北市智慧管理科技執法設備資料表 —
  Taipei tech enforcement (red-light intersections, parking, lane control)
- [164507](https://data.gov.tw/dataset/164507) 路口安全自動偵測系統設置地點 —
  New Taipei intersection enforcement (闖紅燈, 不停讓行人, lane discipline);
  rows that only enforce parking or heavy-vehicle bans are skipped
- [25935](https://data.gov.tw/dataset/25935) 桃園市測速照相設備地點 —
  Taoyuan speed and red-light cameras
- [178168](https://data.gov.tw/dataset/178168) 桃園市科技執法設備地點 —
  Taoyuan tech enforcement
- [53645](https://data.gov.tw/dataset/53645) 臺南市固定式交通違規照相設備設置地點 —
  Tainan fixed and junction cameras; the city publishes text locations only,
  so rows are joined to the curated coordinates in `data/geocodes/53645.yaml`
  (OSM junction geocoding + national-list twins; a new upstream row is
  reported as "geocode missing" until it is curated)
- [83881](https://data.gov.tw/dataset/83881) 臺中市科學儀器執法設備取締地點(固定式) —
  Taichung police fixed cameras (speed + red-light, with bearing and limit)
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
- County sets: [27969](https://data.gov.tw/dataset/27969) /
  [172905](https://data.gov.tw/dataset/172905) 彰化,
  [178085](https://data.gov.tw/dataset/178085) /
  [178086](https://data.gov.tw/dataset/178086) 雲林,
  [178159](https://data.gov.tw/dataset/178159) 基隆,
  [156415](https://data.gov.tw/dataset/156415) /
  [172940](https://data.gov.tw/dataset/172940) 澎湖,
  [172174](https://data.gov.tw/dataset/172174) 苗栗,
  [159972](https://data.gov.tw/dataset/159972) 屏東,
  [178144](https://data.gov.tw/dataset/178144) 新竹市,
  [173211](https://data.gov.tw/dataset/173211) /
  [109336](https://data.gov.tw/dataset/109336) 新竹縣,
  [178121](https://data.gov.tw/dataset/178121) 金門,
  [178734](https://data.gov.tw/dataset/178734) 臺東 (three Big5 files behind
  one resource URL),
  [38357](https://data.gov.tw/dataset/38357) 南投
- County portals that data.gov.tw does not index, fetched by URL
  (`cli.ELAND_*`, `fetch.DirectDataset`; same 政府資料開放授權條款):
  宜蘭縣政府警察局科學儀器執法設備設置地點 (固定式) and (科技執法) on
  [opendata.e-land.gov.tw](https://opendata.e-land.gov.tw) — the county's
  only machine-readable lists (the data.gov.tw entry 128438 is a ghost)

Average-speed (區間測速) rows in any source are excluded from point import —
sections are hand-curated in `data/sections.yaml` with entry/exit pairs.
Rows whose every enforcement item is parking or a vehicle-class ban
(違規停車, 禁行大貨車, 限制車種) are skipped as well: they never concern a
moving rider.

### Sanity checks on parsed rows

- `freeway_check.py` — 國道 rows whose kilometre marker and position disagree
  with the rest of their freeway are dropped (and cross-source marker twins
  merged).
- `districts.py` — a row placed more than 1 km outside the county it is filed
  under, or 2 km outside the township its description leads with, is dropped:
  7320 put five 霧峰/大里/烏日 cameras 28 km west in 彰化 and a 梧棲 junction in
  the sea. Boundaries are `data/districts.json`, the MOI township polygons
  (data.gov.tw 7441) simplified by `tools/build_districts.py`; regenerate it
  from a fresh download when townships change.
- `data/overrides.yaml` — curated drops for rows whose coordinate is wrong by
  road-level evidence while another source holds the camera correctly; an
  override that matches no row any more is reported by the build.

Everything dropped is counted in the build report and written to
`unresolved.csv` — never silently.

### Geocoding a text-only list

`data/geocodes/<id>.yaml` holds hand-verified coordinates for a dataset
that names its locations but gives no coordinates (臺南 53645: 「中華路與中
央路口」). The table was produced offline from the Geofabrik Taiwan OSM
extract with pyosmium: index every named `highway` way (provincial 台N refs
come from the `TW:provincial` route relations, not the ways), take the
shared node of the two named roads, and keep the node cluster inside the
row's 行政區. Rows that already exist in the national list reuse its
coordinate so they dedupe onto it. The table states the method per entry;
the build reports every row it cannot join.

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

Diffs the data.gov.tw catalog export and the portal's live title search
against `data/dataset_watch.yaml` (new enforcement datasets, delisted
sources — including the county-portal URLs under `direct:` — and
coordinate-less datasets gaining coordinates). The monthly `dataset-watch`
workflow runs it and opens a GitHub issue on findings. After evaluating a
reported dataset, record it in the baseline so it stops being reported.
County portals the export never lists are worth a direct `package_search`
(CKAN) now and then: 宜蘭's lists were found that way.
