"""Per-source CSV parsers. Each returns (cameras, unresolved, stats).

Parsers fail loudly if expected columns disappear — a renamed column must
break the weekly build, not silently produce an empty database.
"""

import csv
import io
from collections import Counter

from .model import Camera, Unresolved
from .normalize import make_id, parse_bearing, parse_limit
from .projection import CoordinateError, normalize_coords

SOURCE_7320 = "gov.tw:7320"
SOURCE_13940 = "gov.tw:13940"


class SchemaError(RuntimeError):
    pass


def _require_columns(fieldnames: list[str] | None, required: set[str], source: str) -> None:
    found = set(fieldnames or [])
    missing = required - found
    if missing:
        raise SchemaError(f"{source}: missing columns {sorted(missing)}; found {sorted(found)}")


def parse_7320(text: str, today: str) -> tuple[list[Camera], list[Unresolved], Counter]:
    """National speed-enforcement points (fixed cameras), English headers.
    Quirk: the first data row repeats the header in Chinese — skip it."""
    reader = csv.DictReader(io.StringIO(text))
    _require_columns(
        reader.fieldnames, {"CityName", "Address", "Longitude", "Latitude", "direct", "limit"}, SOURCE_7320
    )
    cameras: list[Camera] = []
    unresolved: list[Unresolved] = []
    stats: Counter = Counter()
    for row in reader:
        if (row.get("Longitude") or "").strip() == "經度":
            stats["skipped_zh_header"] += 1
            continue
        bearing = parse_bearing(row.get("direct"))
        if bearing is None and (row.get("direct") or "").strip():
            stats[f"bearing_both_or_unparsed:{row['direct'].strip()}"] += 1
        try:
            lat, lon = normalize_coords(row.get("Latitude"), row.get("Longitude"))
        except CoordinateError as e:
            unresolved.append(Unresolved(SOURCE_7320, str(e), dict(row)))
            continue
        cameras.append(
            Camera(
                id=make_id(SOURCE_7320, lat, lon, bearing),
                lat=lat,
                lon=lon,
                type="fixed",
                speed_limit=parse_limit(row.get("limit")),
                bearing=bearing,
                city=(row.get("CityName") or "").strip(),
                description=(row.get("Address") or "").strip(),
                source=SOURCE_7320,
                last_seen=today,
            )
        )
    return cameras, unresolved, stats


def parse_13940(text: str, today: str) -> tuple[list[Camera], list[Unresolved], Counter]:
    """Freeway fixed speed cameras, Chinese headers, stable equipment ids."""
    reader = csv.DictReader(io.StringIO(text))
    _require_columns(
        reader.fieldnames, {"設備編號", "設置地點", "座標緯度", "座標經度", "拍攝方向", "速限"}, SOURCE_13940
    )
    cameras: list[Camera] = []
    unresolved: list[Unresolved] = []
    stats: Counter = Counter()
    for row in reader:
        try:
            lat, lon = normalize_coords(row.get("座標緯度"), row.get("座標經度"))
        except CoordinateError as e:
            unresolved.append(Unresolved(SOURCE_13940, str(e), dict(row)))
            continue
        description = (row.get("設置地點") or "").strip()
        area = (row.get("設置區域描述") or "").strip()
        if area and area not in description:
            description = f"{area} {description}".strip()
        stats[f"enforcement:{(row.get('取締項目') or '?').strip()}"] += 1
        cameras.append(
            Camera(
                id=f"13940-{(row.get('設備編號') or '').strip()}",
                lat=lat,
                lon=lon,
                type="fixed",
                speed_limit=parse_limit(row.get("速限")),
                bearing=parse_bearing(row.get("拍攝方向")),
                city=(row.get("縣市") or "").strip() or "國道",
                description=description,
                source=SOURCE_13940,
                last_seen=today,
            )
        )
    return cameras, unresolved, stats
