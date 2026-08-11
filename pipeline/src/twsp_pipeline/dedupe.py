"""Proximity deduplication across sources.

The same physical camera can appear in the national set and a
source-specific set — sometimes even under a different type (a 7320 speed
camera that a municipal dataset lists as red-light or tech enforcement).
The earliest source in the input list wins, so callers order sources by
priority (richest data first). Rules, all requiring compatible bearings:

- same type, same source: duplicates within RADIUS_M (one agency listing
  one pole twice with GPS jitter);
- same type, different sources: duplicates within CROSS_SOURCE_RADIUS_M —
  two agencies geocoding the same device disagree by more than one agency's
  own jitter;
- different types (fixed/red_light/tech only), different sources: duplicates
  within CROSS_TYPE_RADIUS_M — tight, because nearby distinct devices of
  different types genuinely exist at intersections.

A dropped duplicate donates its speed limit when the winner has none.
Bearings are never merged: a null bearing means "alert both directions"
(fail-safe) and must not be narrowed by another source's claim.
"""

import math
from collections import Counter, defaultdict

from .model import Camera

RADIUS_M = 30.0
CROSS_SOURCE_RADIUS_M = 45.0
CROSS_TYPE_RADIUS_M = 15.0
_CELL_DEG = 0.0005  # ~50 m; guarantees a 45 m neighbour is within the 3x3 block

_M_PER_DEG_LAT = 110_540.0
_M_PER_DEG_LON_EQ = 111_320.0

_CROSS_TYPE_MERGEABLE = {"fixed", "red_light", "tech"}


def _distance_m(a: Camera, b: Camera) -> float:
    # Equirectangular approximation — plenty at 30 m scales.
    dy = (a.lat - b.lat) * _M_PER_DEG_LAT
    dx = (a.lon - b.lon) * _M_PER_DEG_LON_EQ * math.cos(math.radians(a.lat))
    return math.hypot(dx, dy)


def _bearings_compatible(a: float | None, b: float | None) -> bool:
    if a is None or b is None:
        return True
    diff = abs(a - b) % 360.0
    return min(diff, 360.0 - diff) <= 45.0


def _duplicate_kind(cam: Camera, kept: Camera) -> str | None:
    """Why `cam` duplicates already-kept `kept`, or None if it does not."""
    if not _bearings_compatible(cam.bearing, kept.bearing):
        return None
    distance = _distance_m(cam, kept)
    if cam.type == kept.type:
        limit = RADIUS_M if cam.source == kept.source else CROSS_SOURCE_RADIUS_M
        return "same_type" if distance <= limit else None
    if (
        cam.source != kept.source
        and cam.type in _CROSS_TYPE_MERGEABLE
        and kept.type in _CROSS_TYPE_MERGEABLE
        and distance <= CROSS_TYPE_RADIUS_M
    ):
        return "cross_type"
    return None


# When one source lists the same pole several times (基隆 emits one row per
# violation category at identical position+bearing), the rows share a make_id.
# One physical device gets one alert: keep the most informative type.
_TYPE_PRIORITY = {"fixed": 0, "red_light": 1, "mobile": 2, "tech": 3, "other": 4}


def collapse_id_duplicates(cameras: list[Camera]) -> tuple[list[Camera], Counter]:
    kept: dict[str, Camera] = {}
    dropped: Counter = Counter()
    for cam in cameras:
        other = kept.get(cam.id)
        if other is None:
            kept[cam.id] = cam
        else:
            loser = max(other, cam, key=lambda c: _TYPE_PRIORITY.get(c.type, 9))
            kept[cam.id] = other if loser is cam else cam
            dropped[f"same_device:{loser.source}:{loser.type}"] += 1
    return list(kept.values()), dropped


def dedupe(cameras: list[Camera]) -> tuple[list[Camera], Counter]:
    kept: list[Camera] = []
    dropped: Counter = Counter()
    grid: dict[tuple[int, int], list[Camera]] = defaultdict(list)

    for cam in cameras:
        cell = (int(cam.lat // _CELL_DEG), int(cam.lon // _CELL_DEG))
        duplicate_of = None
        kind = None
        for dr in (-1, 0, 1):
            for dc in (-1, 0, 1):
                for other in grid.get((cell[0] + dr, cell[1] + dc), ()):
                    kind = _duplicate_kind(cam, other)
                    if kind:
                        duplicate_of = other
                        break
                if duplicate_of:
                    break
            if duplicate_of:
                break
        if duplicate_of:
            if kind == "cross_type":
                dropped[f"cross_type:{cam.source}:{cam.type}->{duplicate_of.source}:{duplicate_of.type}"] += 1
            else:
                dropped[f"{cam.source}->{duplicate_of.source}"] += 1
            if duplicate_of.speed_limit is None and cam.speed_limit is not None:
                duplicate_of.speed_limit = cam.speed_limit
        else:
            grid[cell].append(cam)
            kept.append(cam)
    return kept, dropped
