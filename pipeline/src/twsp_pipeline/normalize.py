"""Field normalization: free-text 拍攝方向 → bearing, 速限 → int, stable ids."""

import hashlib
import re

# Bearing of the direction of travel being enforced. 「北向南」/「往南」/「南向」
# all mean southbound traffic is photographed → alert users heading ~180°.
_DIR_BEARING = {
    "北": 0.0,
    "東北": 45.0,
    "東": 90.0,
    "東南": 135.0,
    "南": 180.0,
    "西南": 225.0,
    "西": 270.0,
    "西北": 315.0,
}

_BOTH_MARKERS = ("雙", "双")

_FROM_TO = re.compile(r"([東西南北]{1,2})[向往]([東西南北]{1,2})")
_TOWARDS = re.compile(r"[往向]([東西南北]{1,2})")
_BARE = re.compile(r"([東西南北]{1,2})[向行]?")


def parse_bearing(text: str | None) -> float | None:
    """None means 'alert in both directions' — the fail-safe default."""
    if not text:
        return None
    t = re.sub(r"[\s()（）,，、/]", "", text)
    if not t:
        return None
    if any(marker in t for marker in _BOTH_MARKERS):
        return None
    m = _FROM_TO.fullmatch(t)
    if m:
        return _DIR_BEARING.get(m.group(2))
    m = _TOWARDS.fullmatch(t)
    if m:
        return _DIR_BEARING.get(m.group(1))
    m = _BARE.fullmatch(t)
    if m:
        return _DIR_BEARING.get(m.group(1))
    return None


def parse_limit(text: str | None) -> int | None:
    if not text:
        return None
    t = str(text).strip()
    if not t.isdigit():
        return None
    value = int(t)
    return value if 20 <= value <= 130 else None


def make_id(source: str, lat: float, lon: float, bearing: float | None) -> str:
    """Stable id from source + position (~1 m resolution) + bearing. Survives
    reruns; changes only if the camera actually moves."""
    key = f"{source}|{lat:.5f}|{lon:.5f}|{'any' if bearing is None else int(bearing)}"
    return hashlib.sha1(key.encode()).hexdigest()[:12]
