"""Monthly upstream watch: reports new enforcement datasets on data.gov.tw,
used datasets that stopped resolving, and skipped datasets that gained
coordinate columns. The dataset-watch workflow opens a GitHub issue whenever
the report contains findings.

The catalog export omits datasets harvested from municipal platforms
(data.taipei, data.tainan.gov.tw, …) — roughly a third of the catalog — so a
second channel asks the portal's live title search (the dropdown behind the
search box, `/api/front/dataset/dropdown`) for every keyword in TITLE_TERMS.
That is how 臺中's 83881 and 宜蘭's 128438 were found in 2026-10 after a year
of being invisible to the export. Sources we already use are covered by the
per-id existence check.
"""

import argparse
import csv
import io
import re
import sys
from pathlib import Path

import yaml

from .decode import decode_bytes
from .fetch import DATASET_API, FetchError, _get, _get_json, download, extract_csv_payloads, resolve_csv_url

CATALOG_EXPORT_URL = "https://data.gov.tw/datasets/export/csv?type=dataset"
TITLE_SEARCH_URL = "https://data.gov.tw/api/front/dataset/dropdown?list_type=published&qs={term}"
# Title fragments the live search is asked for; the regex in the baseline then
# filters the hits like catalog rows (the search itself is a plain substring).
TITLE_TERMS = ("測速", "科學儀器", "照相", "科技執法", "闖紅燈", "區間平均", "違規照相", "執法設備", "取締地點")
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


def title_search_matches(keywords: re.Pattern) -> dict[str, str]:
    """id -> title from the portal's live title search, one query per term in
    TITLE_TERMS. A term whose request fails is skipped (the export still
    covers most of the catalog); a shape change raises so the workflow goes
    red instead of reporting "nothing new upstream"."""
    found: dict[str, str] = {}
    for term in TITLE_TERMS:
        try:
            payload = _get_json(TITLE_SEARCH_URL.format(term=term)).get("payload")
        except FetchError:
            continue
        if not isinstance(payload, list):
            raise SystemExit(f"title search response shape changed for {term!r}: {str(payload)[:120]}")
        for item in payload:
            title = str(item.get("title") or "").strip()
            nid = str(item.get("nid") or "")
            if nid.isdigit() and keywords.search(title):
                found[nid] = title
    return found


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
        return bool(_get_json(DATASET_API.format(dataset_id=dataset_id)).get("success"))
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
        "--catalog", type=Path, default=None, help="local catalog export CSV (skips the ~70 MB download)"
    )
    args = parser.parse_args(argv)

    baseline = yaml.safe_load(args.baseline.read_text(encoding="utf-8"))
    keywords = re.compile(baseline["keywords"])

    if args.catalog:
        catalog_csv = args.catalog.read_text(encoding="utf-8")
    else:
        # decode_bytes, not a hardcoded utf-8-sig: an encoding change upstream
        # must not crash the monthly watch.
        catalog_csv = decode_bytes(_get(CATALOG_EXPORT_URL))
    matches = catalog_matches(catalog_csv, keywords)
    matches.update(title_search_matches(keywords))
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
