"""Pipeline entry point: fetch → decode → parse → dedupe → emit + report."""

import argparse
import shutil
import sqlite3
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from .decode import decode_bytes
from .dedupe import collapse_id_duplicates, dedupe
from .emit import write_geojson, write_manifest, write_sqlite, write_unresolved
from .fetch import FetchError, download, extract_csv_payloads, resolve_csv_url
from .freeway_check import check_freeway_markers
from .model import Camera, Unresolved
from .parse import (
    SOURCE_130111,
    SOURCE_156415,
    SOURCE_172905,
    SOURCE_172940,
    SOURCE_178085,
    SOURCE_178086,
    SOURCE_178159,
    SOURCE_13940,
    SOURCE_135957,
    SOURCE_170673,
    SOURCE_176549,
    SOURCE_176555,
    SOURCE_176558,
    SOURCE_176560,
    SOURCE_176561,
    SOURCE_177827,
    SOURCE_25935,
    SOURCE_7320,
    SOURCE_100856,
    SOURCE_178168,
    SOURCE_172174,
    SOURCE_178144,
    SOURCE_159972,
    SOURCE_178121,
    parse_130111,
    parse_13940,
    parse_156415,
    parse_172905,
    parse_172940,
    parse_178085,
    parse_178086,
    parse_178159,
    parse_135957,
    parse_170673,
    parse_176549,
    parse_176555,
    parse_176558,
    parse_176560,
    parse_176561,
    parse_177827,
    parse_25935,
    parse_7320,
    parse_100856,
    parse_178168,
    parse_172174,
    parse_178144,
    parse_159972,
    parse_178121,
)
from .sections import load_sections, suppress_section_hint_points

# Order = dedupe priority: national sets first (13940's stable equipment ids
# and 7320's coverage win ties; 100856's ramp red-lights overlap neither), then
# municipal sets richest-metadata first — 130111 before 135957 so Taipei
# red-light twins keep bearing and speed limit, 25935 before 178168 so Taoyuan
# speed twins keep their equipment ids, 176549 before the other Kaohsiung sets
# for the same reason.
DATASETS = (
    (13940, parse_13940, SOURCE_13940),
    (7320, parse_7320, SOURCE_7320),
    (100856, parse_100856, SOURCE_100856),
    (130111, parse_130111, SOURCE_130111),
    (25935, parse_25935, SOURCE_25935),
    (178168, parse_178168, SOURCE_178168),
    (135957, parse_135957, SOURCE_135957),
    (170673, parse_170673, SOURCE_170673),
    (176549, parse_176549, SOURCE_176549),
    (176558, parse_176558, SOURCE_176558),
    (176560, parse_176560, SOURCE_176560),
    (176561, parse_176561, SOURCE_176561),
    (176555, parse_176555, SOURCE_176555),
    (177827, parse_177827, SOURCE_177827),
    (172905, parse_172905, SOURCE_172905),
    (178085, parse_178085, SOURCE_178085),
    (178086, parse_178086, SOURCE_178086),
    (178159, parse_178159, SOURCE_178159),
    (156415, parse_156415, SOURCE_156415),
    (172940, parse_172940, SOURCE_172940),
    (172174, parse_172174, SOURCE_172174),
    (159972, parse_159972, SOURCE_159972),
    (178144, parse_178144, SOURCE_178144),
    (178121, parse_178121, SOURCE_178121),
)


def load_previous_snapshot(db_path: Path, source: str) -> list[Camera] | None:
    """Rows for one source from the last committed database. Used when a
    dataset's host rejects the CI runner (tgos.tw 403s GitHub IPs): better to
    keep yesterday's freeway cameras than to drop them or fail the week's
    update. last_seen is preserved so staleness stays visible."""
    if not db_path.exists():
        return None
    conn = sqlite3.connect(db_path)
    try:
        rows = conn.execute(
            """
            SELECT id, lat, lon, type, speed_limit, bearing, city, description,
                   source, last_seen, section_id, section_role
            FROM cameras WHERE source = ?
            """,
            (source,),
        ).fetchall()
    finally:
        conn.close()
    return [Camera(*row) for row in rows] or None


def _fetch_texts(dataset_id: int, cache_dir: Path | None) -> list[str]:
    cache_file = cache_dir / f"{dataset_id}.bin" if cache_dir else None
    if cache_file and cache_file.exists():
        raw = cache_file.read_bytes()
        print(f"  using cached download: {cache_file}")
    else:
        url = resolve_csv_url(dataset_id)
        print(f"  resolved URL: {url}")
        raw = download(url)
        if cache_file:
            cache_file.parent.mkdir(parents=True, exist_ok=True)
            cache_file.write_bytes(raw)
    return [decode_bytes(payload) for payload in extract_csv_payloads(raw)]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build the TW SpeedTrap camera database")
    parser.add_argument("--out", type=Path, default=Path("pipeline/out"), help="artifact output directory")
    parser.add_argument("--geojson", type=Path, default=Path("data/cameras.geojson"))
    parser.add_argument("--assets-db", type=Path, default=None, help="also copy cameras.db here (app assets)")
    parser.add_argument("--cache", type=Path, default=None, help="cache downloads in this directory")
    parser.add_argument(
        "--sections",
        type=Path,
        default=Path("pipeline/data/sections.yaml"),
        help="curated average-speed sections YAML",
    )
    parser.add_argument(
        "--fallback-db",
        type=Path,
        default=Path("app/src/main/assets/cameras.db"),
        help="previous database used to keep a source's rows when its host is unreachable",
    )
    parser.add_argument(
        "--min-count",
        type=int,
        default=2000,
        help="refuse to emit fewer cameras than this (a truncated upstream day must not reach installs)",
    )
    args = parser.parse_args(argv)

    # Minute precision so a same-day data fix still reads as newer to
    # installed apps (comparison is lexicographic; content_hash, which gates
    # publishing, excludes last_seen so this never causes spurious releases).
    today = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M")
    all_cameras: list[Camera] = []
    all_unresolved: list[Unresolved] = []
    stats: Counter = Counter()

    stale_sources: list[tuple[str, str]] = []
    for dataset_id, parse_fn, source in DATASETS:
        print(f"dataset {dataset_id}:")
        try:
            texts = _fetch_texts(dataset_id, args.cache)
        except FetchError as e:
            print(f"  FETCH FAILED: {e}")
            previous = load_previous_snapshot(args.fallback_db, source)
            if previous is None:
                raise
            print(f"  KEEPING PREVIOUS SNAPSHOT: {len(previous)} cameras (last seen {previous[0].last_seen})")
            all_cameras.extend(previous)
            stale_sources.append((source, previous[0].last_seen))
            continue
        for text in texts:
            cameras, unresolved, source_stats = parse_fn(text, today)
            print(f"  parsed {len(cameras)} cameras, {len(unresolved)} unresolved")
            all_cameras.extend(cameras)
            all_unresolved.extend(unresolved)
            stats.update(source_stats)

    all_cameras, same_device = collapse_id_duplicates(all_cameras)
    if same_device:
        print(f"\nsame-device rows collapsed: {sum(same_device.values())} ({dict(same_device)})")
    all_cameras, freeway_dropped, freeway_report = check_freeway_markers(all_cameras)
    if freeway_dropped or freeway_report:
        print(f"freeway marker check: dropped {sum(freeway_dropped.values())} ({dict(freeway_dropped)})")
        for line in freeway_report:
            print(f"  {line}")
    deduped, dropped = dedupe(all_cameras)
    print(f"dedupe: kept {len(deduped)}, dropped {sum(dropped.values())} ({dict(dropped)})")

    sections, section_cameras = load_sections(args.sections, today)
    if sections:
        print(f"sections: {len(sections)} curated average-speed sections")
        deduped, hint_dropped = suppress_section_hint_points(deduped, section_cameras, SOURCE_7320)
        if hint_dropped:
            print(f"7320 A至B rows suppressed along curated sections: {sum(hint_dropped.values())}")
            for key in sorted(hint_dropped):
                print(f"  {key.split(':', 1)[1]}")
    deduped = deduped + section_cameras

    if len(deduped) < args.min_count:
        print(
            f"\nERROR: only {len(deduped)} cameras built, below --min-count {args.min_count}; "
            "refusing to emit — a truncated upstream day must not auto-update every install."
        )
        return 1

    db_path = args.out / "cameras.db"
    manifest_path = args.out / "manifest.json"
    write_sqlite(deduped, db_path, today, sections)
    manifest = write_manifest(deduped, db_path, manifest_path, today)
    write_geojson(deduped, args.geojson)
    write_unresolved(all_unresolved, args.out / "unresolved.csv")
    if args.assets_db:
        args.assets_db.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(db_path, args.assets_db)

    print("\n--- report ---")
    by_type = Counter(cam.type for cam in deduped)
    by_city = Counter(cam.city for cam in deduped)
    with_bearing = sum(1 for cam in deduped if cam.bearing is not None)
    with_limit = sum(1 for cam in deduped if cam.speed_limit is not None)
    print(f"total cameras: {len(deduped)}")
    print(f"by type: {dict(by_type)}")
    print(f"with bearing: {with_bearing}, with speed limit: {with_limit}")
    print(f"top cities: {by_city.most_common(10)}")
    print(f"unresolved rows: {len(all_unresolved)} (see {args.out / 'unresolved.csv'})")
    if stale_sources:
        print("STALE SOURCES (host unreachable, previous snapshot kept):")
        now = datetime.now(timezone.utc)
        for source, last_seen in stale_sources:
            try:
                age_days = (now - datetime.strptime(last_seen, "%Y-%m-%dT%H:%M").replace(tzinfo=timezone.utc)).days
                age = f"{age_days} days old"
            except ValueError:
                age = "age unknown"
            print(f"  {source}: last refreshed {last_seen} ({age})")
    notable = {k: v for k, v in stats.items() if not k.startswith("bearing_both")}
    print(f"source stats: {notable}")
    print(f"data_version: {manifest['data_version']}, content_hash: {manifest['content_hash'][:12]}…")
    print(f"db: {db_path} sha256={manifest['sha256'][:12]}…")
    return 0


if __name__ == "__main__":
    sys.exit(main())
