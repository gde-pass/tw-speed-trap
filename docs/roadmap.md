# Roadmap

Ideas agreed for later versions, in rough priority order.

## Adaptive GPS sampling (deferred pending device evidence)

The 2026-08 audit designed a provably-safe scheme (7×7 grid wake query
covering ~2.97 km, relax to 5 s intervals only beyond a 2.5 km wake
boundary with ≤50 m accuracy, never while a section traversal is active,
priority stays HIGH_ACCURACY — switching to balanced power is ruled out
permanently because its position error exceeds the wake margin). It is
**not shipped** because two premises are unprovable device-agnostically:
whether a 5 s interval actually lets vendor GNSS HALs duty-cycle (if the
receiver stays hot, the saving is only AP wakeups, which the notification
throttle already removed), and the RELAXED→FULL re-fix latency bound.
Evidence needed before shipping: batterystats deltas for 1 s vs 5 s
HIGH_ACCURACY over ≥1 h screen-off runs on ≥2 chipset families showing
≥5 %/h saving; p99 re-fix latency <6 s over ≥100 switch trials; and
confirmation the fused provider doesn't batch 5 s updates under
Doze-adjacent states while a foreground service holds location.

## Bluetooth auto-start device allowlist

Auto-start currently triggers on *any* Bluetooth ACL connection (speaker,
car, headset). Field use is fine because the setting is opt-in, but a
device picker (list bonded devices, store selected MACs, match
EXTRA_DEVICE in the receiver) would stop a living-room speaker from
starting GPS. Needs BLUETOOTH_CONNECT UI and a settings schema addition.

## Signed data manifests

The db update channel is TLS-to-GitHub plus a SHA-256 that rides in the
same manifest — integrity against corruption, not authenticity against a
compromised release channel. The 2026-08 audit hardened the surrounding
surface (URL allowlist, size caps, db self-description checks, version
format guard); actual authenticity needs an Ed25519 signature over the
manifest, a signing step in data-update.yml (key in Actions secrets), and
a pinned public key + verification in the app. Straightforward, but key
management deserves its own change.

## Known upstream data errors (report to source agencies)

Mislocated freeway rows are now auto-suppressed by the pipeline
(`freeway_check.py`): straight-line distance between two 國道 kilometre
markers can never exceed their km difference, so rows violating that
bound against ≥2 same-freeway rows are dropped and reported in the build
log. The 2026-08 run caught 15 rows — the long-known 7320 國道一號南向
279公里 placed on 建國高架 in Taipei (~200 km off; 13940 has the device
correctly at 23.373209, 120.350715), plus shared-geocode errors present
in *both* national sets (N1 66.6/68 placed in the 五峰 mountains, N1
26.8/35.56, N3 218.9/284.07) and 13940-only ones (N3 262.01 ~40 km off).
The same module also merges cross-source rows carrying an identical
marker up to 1 km apart (previous dedupe radius was 45 m), removing
double alerts for one device geocoded twice (e.g. N3 313.7 at 335 m,
N4 4.4 at 613 m). Still worth reporting upstream — suppression loses the
device entirely when both sources share the wrong geocode.

## CI: bot data commits skip CI

The weekly data commit is pushed with GITHUB_TOKEN, so GitHub suppresses
workflow triggering — the committed db/geojson are never CI-validated.
Fixing it means pushing with a PAT or deploy key (then guarding against
trigger loops). The build-db --min-count gate now bounds the damage.

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

- ~~Recognize 「A至B」-style rows in dataset 7320 as section hints~~ —
  shipped in v1.2: rows along a curated section corridor are suppressed.
- ~~Auto-start on Bluetooth connect; auto-stop when stationary~~ — shipped
  in v1.2, both opt-in. Auto-start degrades to a tap-to-start notification
  where Android 12+ blocks background service starts; if that fallback
  fires too often in practice, the CompanionDeviceManager presence API is
  the exemption-carrying upgrade path.
- Verify the remaining `sections.yaml` candidates as second anchors turn up
  (each is a ~10-minute job with `scratchpad` tooling — see the comment
  block in that file for what each zone still needs).
