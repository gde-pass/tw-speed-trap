# Roadmap

Ideas agreed for later versions, in rough priority order.

## In-app marking of average-speed section gantries (planned)

**Problem:** section entry/exit points in `pipeline/data/sections.yaml` are
curated from geometry and government rows, and the ~20 candidate zones can't
be verified without someone physically there. Riding every road in Taiwan to
check them is not realistic.

**Feature:** while detection is running, a big "mark" button (usable with
gloves, one tap) records the current GPS fix + bearing + timestamp as a
section **start** or **end** gantry — tap once passing the entry portal/sign,
once at the exit. Marks are reviewable afterwards on the map screen.

**Data flow:**

- Marks are stored locally and exportable as a ready-to-paste
  `sections.yaml` fragment (or a pre-filled GitHub issue), so a ride
  becomes a pipeline PR with zero transcription.
- Optionally let friends' installs collect marks too. Multiple marks of the
  same gantry get averaged (weighted by GPS accuracy) so positions become
  more precise as passes accumulate; the pipeline can then attach a
  confidence per endpoint.
- **Privacy constraint:** the app promises location never leaves the device
  and the only network traffic is the db update. Crowd-collection must not
  break that: sharing stays explicit and manual (user-triggered export /
  share-sheet of the marks file only — individual gantry points, never
  tracks), opt-in per export, nothing automatic in the background.

**Why this beats more geometry work:** a mark made at the physical gantry is
ground truth; it both verifies the shipped zones (瑪陵 exit bearing, 蘇花改
portal offsets, 向上路 中興路 end) and unlocks the whole candidates list at
the bottom of `sections.yaml`.

## Municipal dataset parsers (shipped in v1.1)

Shipped: Taichung 170673, Taipei 130111 + 135957, Taoyuan 25935, Kaohsiung
160171 — the database's first `red_light` and `tech` points. Evaluated and
skipped (re-check when upstream improves):

- **Tainan 139129** — no coordinate columns at all, only road descriptions.
- **Chiayi 52544** — zero points >50 m from national data.
- **New Taipei** — city-wide dataset 26835 is delisted; what remains is ~29
  per-district micro-datasets (a few rows each, mostly already in 7320) and a
  科技執法 list that exists only as police-website announcements, not open
  data. The catalog sweep later surfaced **123740** (city-wide 新北市固定式
  測速照相): evaluated, only 2 of 190 points are new — skipped.
- **Kaohsiung** — the catalog sweep found the current 115年 series (176549,
  176555, 176558, 176560, 176561, 177827); integrated (+358 points, replacing
  the 2022 snapshot 160171). The file host (data.kcg.gov.tw) is unreachable
  from GitHub CI, so these ride the snapshot fallback between local refreshes
  — refresh by running `build-db` locally and committing the assets db.

The 2026-08 county sweep closed out the remaining catalog candidates:
integrated 彰化 172905, 雲林 178085/178086, 基隆 178159, 澎湖 156415/172940
(+80 points after dedupe). Unusable and recorded in the watch baseline:
屏東 144578 / 苗栗 172178 / 高雄 mobile 152480 (no coordinates), 屏東 144579
and 雪山隧道 100857 (section lists without coordinates — copied into
`sections.yaml` candidates), 南投 38357/176021 (host drops connections),
嘉義縣 178140/178143 (TLS handshake too weak for modern OpenSSL), 臺東
177486 (no CSV resource).

Upstream monitoring is automated: the monthly `dataset-watch` workflow diffs
the data.gov.tw catalog against `pipeline/data/dataset_watch.yaml`, checks
that used datasets still resolve, re-checks coordinate-less ones (Tainan
139129), and opens a GitHub issue on findings.

## Smaller items

- Recognize 「A至B」-style rows in dataset 7320 as section hints instead of
  point cameras (suppress the redundant point alert near a curated section
  exit).
- Auto-start detection on Bluetooth connect; auto-stop when stationary for
  N minutes.
- Verify the remaining `sections.yaml` candidates as second anchors turn up
  (each is a ~10-minute job with `scratchpad` tooling — see the comment
  block in that file for what each zone still needs).
