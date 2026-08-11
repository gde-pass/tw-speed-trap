"""Monthly upstream watch: reports new enforcement datasets on data.gov.tw,
used datasets that stopped resolving, and skipped datasets that gained
coordinate columns. The dataset-watch workflow opens a GitHub issue whenever
the report contains findings.

Known blind spot: the catalog export omits datasets harvested from municipal
platforms (data.taipei, data.tainan.gov.tw, …) — roughly a third of the
catalog. Sources we already use are covered by the per-id existence check
instead; genuinely new municipal-platform datasets stay invisible until
data.gov.tw lists them in the export.
"""

import argparse
import csv
import io
import re
import sys
from pathlib import Path

import yaml

from .decode import decode_bytes
from .fetch import DATASET_API, FetchError, _get, download, extract_csv_payloads, resolve_csv_url

CATALOG_EXPORT_URL = "https://data.gov.tw/datasets/export/csv?type=dataset"
COORD_COLUMNS = re.compile(r"經度|緯度|座標|lat|lon", re.IGNORECASE)

FINDINGS_SENTINEL = "DATASET-WATCH: FINDINGS"
OK_SENTINEL = "DATASET-WATCH: OK"


def catalog_matches(catalog_csv: str, keywords: re.Pattern) -> dict[str, str]:
    """id -> title for every catalog row whose title matches the keywords."""
    reader = csv.DictReader(io.StringIO(catalog_csv.lstrip("\ufeff")))
    found = set(reader.fieldnames or [])
    if not {"資料集識別碼", "資料集名稱"} <= found:
        # Fail loudly with the actual columns: the workflow must go red, not
        # quietly report "nothing new upstream".
        raise SystemExit(f"catalog export columns changed: {sorted(found)[:12]}")
    return {
        row["資料集識別碼"]: (row["資料集名稱"] or "").strip()
        for row in reader
        if keywords.search(row["資料集名稱"] or "")
    }


def new_datasets(matches: dict[str, str], baseline: dict) -> dict[str, str]:
    known = (
        {str(i) for i in baseline.get("used", [])}
        | {str(i) for i in baseline.get("seen", {})}
        | {str(i) for i in baseline.get("waiting_for_coordinates", {})}
    )
    def sort_key(kv: tuple[str, str]) -> int:
        return int(kv[0]) if kv[0].isdigit() else 10**12  # non-numeric ids sort last, never crash

    return {i: title for i, title in sorted(matches.items(), key=sort_key) if i not in known}


def dataset_listed(dataset_id: int) -> bool:
    try:
        return bool(_get(DATASET_API.format(dataset_id=dataset_id)).json().get("success"))
    except FetchError:
        return False


def coordinate_header(dataset_id: int) -> bool | None:
    """True if the dataset's CSV header now names coordinates; None when the
    dataset could not be fetched."""
    try:
        payloads = extract_csv_payloads(download(resolve_csv_url(dataset_id)))
        header = decode_bytes(payloads[0]).lstrip("\ufeff").splitlines()[0]
    except (FetchError, IndexError):
        return None
    return bool(COORD_COLUMNS.search(header))


def build_report(
    fresh: dict[str, str],
    delisted: dict[int, str],
    gained_coords: dict[int, str],
) -> tuple[str, bool]:
    lines = ["# Dataset watch report", ""]
    if fresh:
        lines += ["## New keyword-matching datasets (evaluate, then add to the baseline)", ""]
        lines += [f"- [{i}](https://data.gov.tw/dataset/{i}) {title}" for i, title in fresh.items()]
        lines.append("")
    if delisted:
        lines += ["## Used datasets that no longer resolve (weekly build is riding its snapshot fallback)", ""]
        lines += [f"- {i} {title}" for i, title in delisted.items()]
        lines.append("")
    if gained_coords:
        lines += ["## Skipped datasets that now publish coordinates (re-evaluate for import)", ""]
        lines += [f"- [{i}](https://data.gov.tw/dataset/{i}) {title}" for i, title in gained_coords.items()]
        lines.append("")
    findings = bool(fresh or delisted or gained_coords)
    if not findings:
        lines += ["Nothing new upstream.", ""]
    lines.append(FINDINGS_SENTINEL if findings else OK_SENTINEL)
    return "\n".join(lines), findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Watch data.gov.tw for upstream dataset changes")
    parser.add_argument(
        "--baseline", type=Path, default=Path("pipeline/data/dataset_watch.yaml"), help="watch baseline YAML"
    )
    parser.add_argument(
        "--catalog", type=Path, default=None, help="local catalog export CSV (skips the ~45 MB download)"
    )
    args = parser.parse_args(argv)

    baseline = yaml.safe_load(args.baseline.read_text(encoding="utf-8"))
    keywords = re.compile(baseline["keywords"])

    if args.catalog:
        catalog_csv = args.catalog.read_text(encoding="utf-8")
    else:
        # decode_bytes, not a hardcoded utf-8-sig: an encoding change upstream
        # must not crash the monthly watch.
        catalog_csv = decode_bytes(_get(CATALOG_EXPORT_URL).content)
    matches = catalog_matches(catalog_csv, keywords)
    print(f"catalog: {len(matches)} keyword-matching datasets", file=sys.stderr)

    fresh = new_datasets(matches, baseline)
    delisted = {i: title for i, title in baseline.get("used", {}).items() if not dataset_listed(i)}
    gained_coords = {
        i: title
        for i, title in baseline.get("waiting_for_coordinates", {}).items()
        if coordinate_header(i) is True
    }

    report, _ = build_report(fresh, delisted, gained_coords)
    print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
