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
SOURCE_130111 = "gov.tw:130111"
SOURCE_135957 = "gov.tw:135957"
SOURCE_170673 = "gov.tw:170673"
SOURCE_25935 = "gov.tw:25935"
SOURCE_160171 = "gov.tw:160171"


class SchemaError(RuntimeError):
    pass


def _require_columns(fieldnames: list[str] | None, required: set[str], source: str) -> None:
    found = set(fieldnames or [])
    missing = required - found
    if missing:
        raise SchemaError(f"{source}: missing columns {sorted(missing)}; found {sorted(found)}")


def _strip_bom(text: str) -> str:
    """decode_bytes strips one UTF-8 BOM; 170673 ships its header with two."""
    return text.lstrip("\ufeff")


def _is_section(text: str) -> bool:
    """區間測速 / 區間平均速率執法 rows are average-speed sections: they need
    curated entry/exit pairs (pipeline/data/sections.yaml), not point alerts."""
    return "區間" in text


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


def parse_130111(text: str, today: str) -> tuple[list[Camera], list[Unresolved], Counter]:
    """Taipei fixed enforcement cameras (臺北市固定測速照相地點表). 功能 mixes
    測速/闖紅燈/區間 combinations: anything that measures speed stays `fixed`
    so it dedupes against its national 7320 twin; red-light-only devices
    become `red_light` — the first such points in the database."""
    reader = csv.DictReader(io.StringIO(_strip_bom(text)))
    _require_columns(
        reader.fieldnames, {"功能", "設置路段", "設置地點", "緯度", "經度", "拍攝方向", "速限-速度限制"}, SOURCE_130111
    )
    cameras: list[Camera] = []
    unresolved: list[Unresolved] = []
    stats: Counter = Counter()
    for row in reader:
        function = (row.get("功能") or "").strip()
        if _is_section(function):
            stats["130111_sections_excluded"] += 1
            continue
        if "測速" in function:
            cam_type = "fixed"
        elif "闖紅燈" in function:
            cam_type = "red_light"
        else:
            cam_type = "tech"
        try:
            lat, lon = normalize_coords(row.get("緯度"), row.get("經度"))
        except CoordinateError as e:
            unresolved.append(Unresolved(SOURCE_130111, str(e), dict(row)))
            continue
        bearing = parse_bearing(row.get("拍攝方向"))
        road = (row.get("設置路段") or "").strip()
        spot = (row.get("設置地點") or "").strip()
        stats[f"130111_type:{cam_type}"] += 1
        cameras.append(
            Camera(
                id=make_id(SOURCE_130111, lat, lon, bearing),
                lat=lat,
                lon=lon,
                type=cam_type,
                speed_limit=parse_limit(row.get("速限-速度限制")),
                bearing=bearing,
                city=(row.get("縣市") or "").strip() or "臺北市",
                description=f"{road} {spot}".strip(),
                source=SOURCE_130111,
                last_seen=today,
            )
        )
    return cameras, unresolved, stats


def parse_135957(text: str, today: str) -> tuple[list[Camera], list[Unresolved], Counter]:
    """Taipei tech-enforcement devices (臺北市智慧管理科技執法設備資料表).
    座標-X is longitude, 座標-Y latitude; quoted fields span multiple lines."""
    reader = csv.DictReader(io.StringIO(_strip_bom(text)))
    _require_columns(reader.fieldnames, {"名稱", "取締路段", "座標-X", "座標-Y", "取締項目"}, SOURCE_135957)
    cameras: list[Camera] = []
    unresolved: list[Unresolved] = []
    stats: Counter = Counter()
    for row in reader:
        name = (row.get("名稱") or "").strip()
        items = (row.get("取締項目") or "").strip()
        if _is_section(name):
            stats["135957_sections_excluded"] += 1
            continue
        cam_type = "red_light" if "闖紅燈" in items else "tech"
        try:
            lat, lon = normalize_coords(row.get("座標-Y"), row.get("座標-X"))
        except CoordinateError as e:
            unresolved.append(Unresolved(SOURCE_135957, str(e), dict(row)))
            continue
        stats[f"135957_type:{cam_type}"] += 1
        cameras.append(
            Camera(
                id=make_id(SOURCE_135957, lat, lon, None),
                lat=lat,
                lon=lon,
                type=cam_type,
                speed_limit=None,
                bearing=None,
                city="臺北市",
                description=(row.get("取締路段") or "").strip(),
                source=SOURCE_135957,
                last_seen=today,
            )
        )
    return cameras, unresolved, stats


def parse_170673(text: str, today: str) -> tuple[list[Camera], list[Unresolved], Counter]:
    """Taichung tech enforcement (臺中市科技執法取締地點). Quirks: the header
    carries a double BOM, 經度/緯度 hold each other's values (normalize_coords
    un-swaps them), and section rows put the literal text 區間測速 in both
    coordinate fields."""
    reader = csv.DictReader(io.StringIO(_strip_bom(text)))
    _require_columns(
        reader.fieldnames, {"編號", "科技執法種類", "設置地點", "取締項目", "經度", "緯度"}, SOURCE_170673
    )
    cameras: list[Camera] = []
    unresolved: list[Unresolved] = []
    stats: Counter = Counter()
    for row in reader:
        kind = (row.get("科技執法種類") or "").strip()
        items = (row.get("取締項目") or "").strip()
        if _is_section(kind):
            stats["170673_sections_excluded"] += 1
            continue
        cam_type = "red_light" if "闖紅燈" in items or "闖紅燈" in kind else "tech"
        try:
            lat, lon = normalize_coords(row.get("緯度"), row.get("經度"))
        except CoordinateError as e:
            unresolved.append(Unresolved(SOURCE_170673, str(e), dict(row)))
            continue
        stats[f"170673_type:{cam_type}"] += 1
        cameras.append(
            Camera(
                id=make_id(SOURCE_170673, lat, lon, None),
                lat=lat,
                lon=lon,
                type=cam_type,
                speed_limit=None,
                bearing=None,
                city="臺中市",
                description=(row.get("設置地點") or "").strip(),
                source=SOURCE_170673,
                last_seen=today,
            )
        )
    return cameras, unresolved, stats


def parse_25935(text: str, today: str) -> tuple[list[Camera], list[Unresolved], Counter]:
    """Taoyuan speed cameras (桃園市測速照相設備地點); same shape as 13940 but
    with 設置地點_路口或路段 and a 取締項目 that decides the camera type."""
    reader = csv.DictReader(io.StringIO(_strip_bom(text)))
    _require_columns(
        reader.fieldnames,
        {"設備編號", "設置地點_路口或路段", "取締項目", "座標緯度", "座標經度", "拍攝方向", "速限"},
        SOURCE_25935,
    )
    cameras: list[Camera] = []
    unresolved: list[Unresolved] = []
    stats: Counter = Counter()
    for row in reader:
        items = (row.get("取締項目") or "").strip()
        if _is_section(items):
            stats["25935_sections_excluded"] += 1
            continue
        if "超速" in items or "測速" in items:
            cam_type = "fixed"
        elif "闖紅燈" in items:
            cam_type = "red_light"
        else:
            cam_type = "tech"
        try:
            lat, lon = normalize_coords(row.get("座標緯度"), row.get("座標經度"))
        except CoordinateError as e:
            unresolved.append(Unresolved(SOURCE_25935, str(e), dict(row)))
            continue
        description = (row.get("設置地點_路口或路段") or "").strip()
        area = (row.get("設置區域描述") or "").strip()
        if area and area not in description:
            description = f"{area} {description}".strip()
        equipment_id = (row.get("設備編號") or "").strip()
        bearing = parse_bearing(row.get("拍攝方向"))
        stats[f"25935_type:{cam_type}"] += 1
        cameras.append(
            Camera(
                id=f"25935-{equipment_id}" if equipment_id else make_id(SOURCE_25935, lat, lon, bearing),
                lat=lat,
                lon=lon,
                type=cam_type,
                speed_limit=parse_limit(row.get("速限")),
                bearing=bearing,
                city=(row.get("縣市") or "").strip() or "桃園市",
                description=description,
                source=SOURCE_25935,
                last_seen=today,
            )
        )
    return cameras, unresolved, stats


def parse_160171(text: str, today: str) -> tuple[list[Camera], list[Unresolved], Counter]:
    """Kaohsiung tech enforcement (高雄市111年交通局建置科技執法設備設置地點).
    A single 座標 column holds latitude and longitude separated by runs of
    spaces; 編號 is blank on the extra rows of multi-camera intersections."""
    reader = csv.DictReader(io.StringIO(_strip_bom(text)))
    _require_columns(reader.fieldnames, {"編號", "地點", "測照行向", "取締項目", "座標"}, SOURCE_160171)
    cameras: list[Camera] = []
    unresolved: list[Unresolved] = []
    stats: Counter = Counter()
    for row in reader:
        items = (row.get("取締項目") or "").strip()
        if _is_section(items):
            stats["160171_sections_excluded"] += 1
            continue
        cam_type = "red_light" if "闖紅燈" in items else "tech"
        parts = (row.get("座標") or "").split()
        if len(parts) != 2:
            unresolved.append(Unresolved(SOURCE_160171, f"unsplittable 座標: {row.get('座標')!r}", dict(row)))
            continue
        try:
            lat, lon = normalize_coords(parts[0], parts[1])
        except CoordinateError as e:
            unresolved.append(Unresolved(SOURCE_160171, str(e), dict(row)))
            continue
        bearing = parse_bearing(row.get("測照行向"))
        stats[f"160171_type:{cam_type}"] += 1
        cameras.append(
            Camera(
                id=make_id(SOURCE_160171, lat, lon, bearing),
                lat=lat,
                lon=lon,
                type=cam_type,
                speed_limit=None,
                bearing=bearing,
                city="高雄市",
                description=(row.get("地點") or "").strip(),
                source=SOURCE_160171,
                last_seen=today,
            )
        )
    return cameras, unresolved, stats
