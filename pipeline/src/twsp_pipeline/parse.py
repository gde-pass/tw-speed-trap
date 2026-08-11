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
SOURCE_176549 = "gov.tw:176549"
SOURCE_176555 = "gov.tw:176555"
SOURCE_176558 = "gov.tw:176558"
SOURCE_176560 = "gov.tw:176560"
SOURCE_176561 = "gov.tw:176561"
SOURCE_177827 = "gov.tw:177827"
SOURCE_172905 = "gov.tw:172905"
SOURCE_178085 = "gov.tw:178085"
SOURCE_178086 = "gov.tw:178086"
SOURCE_178159 = "gov.tw:178159"
SOURCE_156415 = "gov.tw:156415"
SOURCE_172940 = "gov.tw:172940"


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


def parse_176549(text: str, today: str) -> tuple[list[Camera], list[Unresolved], Counter]:
    """Kaohsiung fixed enforcement cameras (高雄市115年固定式違規照相科技執法
    設備設置地點一覽表). 測照型式 mixes 闖紅燈/超速 combinations: anything
    measuring speed stays `fixed` (dedupes against national twins, keeps the
    speed limit); red-light-only devices become `red_light`."""
    reader = csv.DictReader(io.StringIO(_strip_bom(text)))
    _require_columns(
        reader.fieldnames, {"編號", "測照地點", "測照方向", "速限", "測照型式", "座標緯度", "座標經度"}, SOURCE_176549
    )
    cameras: list[Camera] = []
    unresolved: list[Unresolved] = []
    stats: Counter = Counter()
    for row in reader:
        kind = (row.get("測照型式") or "").strip()
        if _is_section(kind):
            stats["176549_sections_excluded"] += 1
            continue
        if "超速" in kind:
            cam_type = "fixed"
        elif "闖紅燈" in kind:
            cam_type = "red_light"
        else:
            cam_type = "tech"
        try:
            lat, lon = normalize_coords(row.get("座標緯度"), row.get("座標經度"))
        except CoordinateError as e:
            unresolved.append(Unresolved(SOURCE_176549, str(e), dict(row)))
            continue
        description = (row.get("測照地點") or "").strip()
        area = (row.get("行政區") or "").strip()
        if area and area not in description:
            description = f"{area} {description}".strip()
        bearing = parse_bearing(row.get("測照方向"))
        # 速限 like 50/40 lists car/truck limits — keep the general (first) one.
        limit = parse_limit((row.get("速限") or "").split("/")[0])
        stats[f"176549_type:{cam_type}"] += 1
        cameras.append(
            Camera(
                id=make_id(SOURCE_176549, lat, lon, bearing),
                lat=lat,
                lon=lon,
                type=cam_type,
                speed_limit=limit,
                bearing=bearing,
                city="高雄市",
                description=description,
                source=SOURCE_176549,
                last_seen=today,
            )
        )
    return cameras, unresolved, stats


def _parse_kaohsiung_tech(
    text: str, today: str, source: str, place_col: str
) -> tuple[list[Camera], list[Unresolved], Counter]:
    """Shared shape of Kaohsiung's 科技執法 datasets: 編號/測照行向/取締項目 +
    座標緯度/座標經度, with the location under 地點 or 設置位置."""
    reader = csv.DictReader(io.StringIO(_strip_bom(text)))
    _require_columns(reader.fieldnames, {"編號", place_col, "測照行向", "取締項目", "座標緯度", "座標經度"}, source)
    dataset_id = source.rsplit(":", 1)[-1]
    cameras: list[Camera] = []
    unresolved: list[Unresolved] = []
    stats: Counter = Counter()
    for row in reader:
        items = (row.get("取締項目") or "").strip()
        if _is_section(items):
            stats[f"{dataset_id}_sections_excluded"] += 1
            continue
        cam_type = "red_light" if "闖紅燈" in items else "tech"
        try:
            lat, lon = normalize_coords(row.get("座標緯度"), row.get("座標經度"))
        except CoordinateError as e:
            unresolved.append(Unresolved(source, str(e), dict(row)))
            continue
        bearing = parse_bearing(row.get("測照行向"))
        stats[f"{dataset_id}_type:{cam_type}"] += 1
        cameras.append(
            Camera(
                id=make_id(source, lat, lon, bearing),
                lat=lat,
                lon=lon,
                type=cam_type,
                speed_limit=None,
                bearing=bearing,
                city="高雄市",
                description=(row.get(place_col) or "").strip(),
                source=source,
                last_seen=today,
            )
        )
    return cameras, unresolved, stats


def parse_176555(text: str, today: str) -> tuple[list[Camera], list[Unresolved], Counter]:
    """Kaohsiung 不停讓行人 enforcement (高雄市115年不停讓行人科技執法監測系統)."""
    return _parse_kaohsiung_tech(text, today, SOURCE_176555, "設置位置")


def parse_176558(text: str, today: str) -> tuple[list[Camera], list[Unresolved], Counter]:
    """Kaohsiung traffic-bureau tech enforcement (高雄市115年交通局建置科技執法
    設備設置地點) — current successor of the retired 111年 dataset 160171."""
    return _parse_kaohsiung_tech(text, today, SOURCE_176558, "地點")


def parse_176560(text: str, today: str) -> tuple[list[Camera], list[Unresolved], Counter]:
    """Kaohsiung light-rail corridor enforcement (高雄市115年捷運局輕軌沿線建置
    科技執法設備設置地點)."""
    return _parse_kaohsiung_tech(text, today, SOURCE_176560, "地點")


def parse_176561(text: str, today: str) -> tuple[list[Camera], list[Unresolved], Counter]:
    """Kaohsiung intersection enforcement (高雄市115年路口科技執法監測系統設置
    地點); carries a stray remarks column (Column1) we ignore."""
    return _parse_kaohsiung_tech(text, today, SOURCE_176561, "設置位置")


def parse_177827(text: str, today: str) -> tuple[list[Camera], list[Unresolved], Counter]:
    """Kaohsiung rental-vehicle pedestrian-yield enforcement (高雄市115年租賃式
    車不停讓行人科技執法地點)."""
    return _parse_kaohsiung_tech(text, today, SOURCE_177827, "設置位置")


def _parse_county_standard(
    text: str, today: str, source: str, place_col: str, items_col: str
) -> tuple[list[Camera], list[Unresolved], Counter]:
    """Shared county open-data shape (彰化/基隆/澎湖): 13940-family Chinese
    columns, with per-county suffix variations on the location and items
    column names. Speed-measuring rows stay `fixed` so they dedupe against
    their national 7320 twins (彰化's list is byte-identical positions)."""
    reader = csv.DictReader(io.StringIO(_strip_bom(text)))
    _require_columns(
        reader.fieldnames, {"縣市", "行政區", place_col, items_col, "座標緯度", "座標經度", "拍攝方向", "速限"}, source
    )
    dataset_id = source.rsplit(":", 1)[-1]
    cameras: list[Camera] = []
    unresolved: list[Unresolved] = []
    stats: Counter = Counter()
    for row in reader:
        items = (row.get(items_col) or "").strip()
        kind = (row.get("科技執法種類") or "").strip()
        if _is_section(items) or _is_section(kind):
            stats[f"{dataset_id}_sections_excluded"] += 1
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
            unresolved.append(Unresolved(source, str(e), dict(row)))
            continue
        description = (row.get(place_col) or "").strip()
        area = (row.get("行政區") or "").strip()
        if area and area not in description:
            description = f"{area} {description}".strip()
        bearing = parse_bearing(row.get("拍攝方向"))
        stats[f"{dataset_id}_type:{cam_type}"] += 1
        cameras.append(
            Camera(
                id=make_id(source, lat, lon, bearing),
                lat=lat,
                lon=lon,
                type=cam_type,
                speed_limit=parse_limit(row.get("速限")),
                bearing=bearing,
                city=(row.get("縣市") or "").strip(),
                description=description,
                source=source,
                last_seen=today,
            )
        )
    return cameras, unresolved, stats


def parse_172905(text: str, today: str) -> tuple[list[Camera], list[Unresolved], Counter]:
    """Changhua tech enforcement (彰化縣警察局固定式科技執法設備設置地點)."""
    return _parse_county_standard(text, today, SOURCE_172905, "設置地點", "取締項目")


def parse_178159(text: str, today: str) -> tuple[list[Camera], list[Unresolved], Counter]:
    """Keelung tech enforcement (基隆市科技執法取締地點)."""
    return _parse_county_standard(text, today, SOURCE_178159, "設置地點(路口或路段)", '取締項目(以"、"分隔)')


def parse_156415(text: str, today: str) -> tuple[list[Camera], list[Unresolved], Counter]:
    """Penghu fixed cameras (澎湖縣固定測速照相設置地點表)."""
    return _parse_county_standard(text, today, SOURCE_156415, "設置地點(路口或路段)", '取締項目(以"、"分隔)')


def parse_172940(text: str, today: str) -> tuple[list[Camera], list[Unresolved], Counter]:
    """Penghu tech enforcement (澎湖縣科技執法地點); overlaps 156415 at shared
    intersections — both classify speed rows `fixed`, so dedupe collapses them."""
    return _parse_county_standard(text, today, SOURCE_172940, "設置地點(路口或路段)", '取締項目(以"、"分隔)')


def _parse_yunlin(text: str, today: str, source: str) -> tuple[list[Camera], list[Unresolved], Counter]:
    """Yunlin lists (1150715 雲林縣警察局…一覽表). The English header row is a
    veneer: the first data row repeats the real header in Chinese (skipped,
    like 7320), Latitude/Longitude are genuine, and Remark holds the police
    branch."""
    reader = csv.DictReader(io.StringIO(_strip_bom(text)))
    _require_columns(reader.fieldnames, {"project", "location", "direction", "speed", "ban", "Latitude", "Longitude"}, source)
    dataset_id = source.rsplit(":", 1)[-1]
    cameras: list[Camera] = []
    unresolved: list[Unresolved] = []
    stats: Counter = Counter()
    for row in reader:
        if (row.get("location") or "").strip() == "設置地點":
            stats[f"{dataset_id}_skipped_zh_header"] += 1
            continue
        items = (row.get("ban") or "").strip()
        if _is_section(items):
            stats[f"{dataset_id}_sections_excluded"] += 1
            continue
        if "超速" in items or "測速" in items:
            cam_type = "fixed"
        elif "闖紅燈" in items:
            cam_type = "red_light"
        else:
            cam_type = "tech"
        try:
            lat, lon = normalize_coords(row.get("Latitude"), row.get("Longitude"))
        except CoordinateError as e:
            unresolved.append(Unresolved(source, str(e), dict(row)))
            continue
        bearing = parse_bearing(row.get("direction"))
        stats[f"{dataset_id}_type:{cam_type}"] += 1
        cameras.append(
            Camera(
                id=make_id(source, lat, lon, bearing),
                lat=lat,
                lon=lon,
                type=cam_type,
                speed_limit=parse_limit(row.get("speed")),
                bearing=bearing,
                city="雲林縣",
                description=(row.get("location") or "").strip(),
                source=source,
                last_seen=today,
            )
        )
    return cameras, unresolved, stats


def parse_178085(text: str, today: str) -> tuple[list[Camera], list[Unresolved], Counter]:
    """Yunlin fixed speed cameras (1150715 雲林縣警察局固定式測速照相設備)."""
    return _parse_yunlin(text, today, SOURCE_178085)


def parse_178086(text: str, today: str) -> tuple[list[Camera], list[Unresolved], Counter]:
    """Yunlin tech enforcement (1150715 雲林縣警察局科技執法設備)."""
    return _parse_yunlin(text, today, SOURCE_178086)
