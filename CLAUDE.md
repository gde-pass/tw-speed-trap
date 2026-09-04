# TW SpeedTrap — notes for Claude Code

## Build & test
- Android: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :detection:test :app:testDebugUnitTest assembleDebug ktlintCheck detekt` — run `ktlintFormat` first
- Pipeline: `uv run --project pipeline pytest`; full build: `uv run --project pipeline build-db --out pipeline/out --geojson data/cameras.geojson --assets-db app/src/main/assets/cameras.db --cache pipeline/out/cache`
- Delete `pipeline/out/cache/` to force live fetches; a **local** build-db run refreshes the hosts GitHub runners can't reach — data.kcg.gov.tw (Kaohsiung), www.klg.gov.tw (基隆 178159), www-ws.pthg.gov.tw (屏東 159972), odws.hccg.gov.tw (新竹市 178144) and tgos.tw 13940 (403) — which ride the snapshot fallback on CI (the weekly pipeline step takes ~10 min burning their retries; that's normal)
- Scripts needing pipeline deps (yaml, requests, `twsp_pipeline`): `uv run --project pipeline python script.py` from the repo root — system `python3` has neither
- Gradle prints no test counts — parse `*/build/test-results/**/*.xml` with a python one-liner
- Emulator: AVD `Pixel_XL_API_32`; adb at `~/Library/Android/sdk/platform-tools/adb`; `apksigner`/`aapt2` in `~/Library/Android/sdk/build-tools/<latest>/` need JAVA_HOME exported; screenshots are 1440×2560 rendered at 1125×2000 → multiply displayed coords ×1.28 before `input tap`
- `adb root` works on the AVD — start the non-exported service directly (`am start-foreground-service -n io.github.gdepass.twspeedtrap/.service.DetectionService`, append `-a io.github.gdepass.twspeedtrap.STOP` to stop) after `pm grant`ing location perms; no UI tapping needed
- Simulated drive: `adb emu geo fix <lon> <lat>` at 1 Hz, lat step 0.00015°/s ≈ 60 km/h; check audio focus via the `twspeedtrap` entry in `dumpsys audio`'s focus stack
- Emulator TTS synthesizes but playback stalls, so utterance callbacks never fire — good for reproducing callback-loss bugs, useless for verifying normal TTS completion

## Lint constraints
- detekt: `ReturnCount` max 4 (merge guards / use `when`), MagicNumber off, comments ruleset off
- detekt `ComplexCondition` fires at 4 boolean operands — keep conditions ≤3, hoist a named local
- After post-commit lint fixes, check `git status` is clean before pushing — path-scoped commits have missed follow-up edits (cost one red CI run)

## Release & data protocol
- `appVersion` in app/build.gradle.kts must match the tag (`v$appVersion`); versionCode = major·10000 + minor·100 + patch
- Release workflow rejects APKs not signed with cert sha256 `52e0e492f49e3662b14a36ba2ad1f236af078c65c70b3514369ca70c10e0da1e`
- Bot data commits race pushes: recover with `git pull --rebase -X theirs origin main`, and create/move tags **after** rebasing (a pre-rebase tag points at a dangling commit and the release builds from it)
- Publish gate is `content_hash` (excludes last_seen); the db file's sha256 changes every run by design
- If data changed: dispatch data-update.yml, don't push main while it runs, wait for green
- Release workflow publishes only a bare changelog link — after it's green, add real notes with `gh release edit vX.Y.Z --notes` (match prior releases' tone: rider-facing bullets + test count)
- A green data-update always commits its own snapshot (db sha changes per run) even when its content_hash equals the local build — `git pull --ff-only origin main` before pushing again
- Run `gh workflow run` on its own with a short timeout (it hung >2 min chained after a push), then poll with a background `until`-loop on `gh run view --json status`; `gh run watch` blew a 10-min timeout once

## Pipeline data sources
- The weekly job's usual failure is an upstream header rename (135957 moved to the county template + Big5, 172940 dropped a suffix): read the SchemaError's `found` list in `gh run view --log-failed`, fix with a candidate-column list (first present wins, none present still raises) as in `_parse_county_standard`, and copy verbatim live rows into the test fixture
- Dataset-watch triage: probe each flagged id with the pipeline's own `resolve_csv_url`/`download`/`decode_bytes` (plain `requests` fails TLS on GRCA/TWCA hosts — use `fetch._session`); the value bar is points >50 m from any camera in data/cameras.geojson; the catalog export's coverage jumps (115→176 matches in 2026-09) so old datasets surface as "new"; record every evaluated id in dataset_watch.yaml with a dated comment, then close the issue the workflow comments on
- Data quality traps seen: coordinate columns swapped (金門 178121, 雲林), lon/lat 10–30 km off the named place (100856 ramps → the `freeway_check` corridor rule drops 國道N號 rows without a km marker that sit >15 km from that freeway's marker rows), "CSV" resources that are scanned PDFs (臺東), download fields holding several CRLF-joined URLs
- `fetch._get` returns bytes (`_get_json` for JSON) under a 120 s wall-clock deadline (`DEADLINE_S`); a deadline hit blacklists the host for the run like a connect timeout; hosts that omit their intermediate cert get it bundled in `pipeline/src/twsp_pipeline/certs/` (recipe: leaf's AIA CA-Issuers URL → PEM → `openssl verify -CAfile <certifi> -untrusted int.pem leaf.pem` → drop in certs/)

## Architecture invariants
- detection/ is pure JVM and GPX-replay-deterministic: fix timestamps only — no wall clocks, no randomness
- Null camera bearing = alert both directions (fail-safe); dedupe/merges must never narrow a null bearing
- Missing source columns raise SchemaError; unresolved/suppressed rows are always counted, never silent
- The overlay bubble must come down when the app's last activity is destroyed: an overlay window pins the process at perceptible oom_adj (200), so a bubble that outlives its app is never reclaimed and only the settings toggle can kill it
- Audio focus is refcounted in FocusLedger: track an utterance id only if the TTS enqueue returned SUCCESS, and every request must have a guaranteed abandon (completion callbacks + watchdog) — never a single last-utterance-id gate

## Tooling quirks
- The rtk hook sometimes mangles grep/head output — fall back to `python3 -c` one-liners for text extraction
- `pipeline/src/twsp_pipeline/watch.py` contains literal `\ufeff` escape sequences that defeat the Edit tool's matcher — edit that file via a python script
- Bash cwd persists across tool calls, so a `cd pipeline && …` in one parallel call breaks siblings — use absolute paths or `uv run --project pipeline …` from the repo root
- rtk also truncates `cat`/`head` of background-task output files — read them with a `python3 -c "print(open(...).read())"` one-liner
