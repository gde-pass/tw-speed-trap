# TW SpeedTrap — notes for Claude Code

## Build & test
- Android: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :detection:test :app:testDebugUnitTest assembleDebug ktlintCheck detekt` — run `ktlintFormat` first
- Pipeline: `uv run --project pipeline pytest`; full build: `uv run --project pipeline build-db --out pipeline/out --geojson data/cameras.geojson --assets-db app/src/main/assets/cameras.db --cache pipeline/out/cache`
- Delete `pipeline/out/cache/` to force live fetches; a **local** build-db run refreshes data.kcg.gov.tw (unreachable from CI — Kaohsiung sources ride the snapshot fallback there)
- Gradle prints no test counts — parse `*/build/test-results/**/*.xml` with a python one-liner
- Emulator: AVD `Pixel_XL_API_32`; adb at `~/Library/Android/sdk/platform-tools/adb`; `apksigner`/`aapt2` in `~/Library/Android/sdk/build-tools/<latest>/` need JAVA_HOME exported; screenshots are 1440×2560 rendered at 1125×2000 → multiply displayed coords ×1.28 before `input tap`

## Lint constraints
- detekt: `ReturnCount` max 4 (merge guards / use `when`), MagicNumber off, comments ruleset off
- After post-commit lint fixes, check `git status` is clean before pushing — path-scoped commits have missed follow-up edits (cost one red CI run)

## Release & data protocol
- `appVersion` in app/build.gradle.kts must match the tag (`v$appVersion`); versionCode = major·10000 + minor·100 + patch
- Release workflow rejects APKs not signed with cert sha256 `52e0e492f49e3662b14a36ba2ad1f236af078c65c70b3514369ca70c10e0da1e`
- Bot data commits race pushes: recover with `git pull --rebase -X theirs origin main`, and create/move tags **after** rebasing (a pre-rebase tag points at a dangling commit and the release builds from it)
- Publish gate is `content_hash` (excludes last_seen); the db file's sha256 changes every run by design
- If data changed: dispatch data-update.yml, don't push main while it runs, wait for green

## Architecture invariants
- detection/ is pure JVM and GPX-replay-deterministic: fix timestamps only — no wall clocks, no randomness
- Null camera bearing = alert both directions (fail-safe); dedupe/merges must never narrow a null bearing
- Missing source columns raise SchemaError; unresolved/suppressed rows are always counted, never silent

## Tooling quirks
- The rtk hook sometimes mangles grep/head output — fall back to `python3 -c` one-liners for text extraction
- `pipeline/src/twsp_pipeline/watch.py` contains literal `\ufeff` escape sequences that defeat the Edit tool's matcher — edit that file via a python script
