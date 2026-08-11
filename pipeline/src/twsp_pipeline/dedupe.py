"""Proximity deduplication across sources.

The same physical camera can appear in the national set and a
source-specific set. Cameras closer than RADIUS_M with the same type (and
compatible bearing) are considered duplicates; the earliest source in the
input list wins, so callers order sources by priority (richest data first).
"""

import math
from collections import Counter, defaultdict

from .model import Camera

RADIUS_M = 30.0
_CELL_DEG = 0.0005  # ~50 m; guarantees a 30 m neighbour is within the 3x3 block

_M_PER_DEG_LAT = 110_540.0
_M_PER_DEG_LON_EQ = 111_320.0


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


def dedupe(cameras: list[Camera], radius_m: float = RADIUS_M) -> tuple[list[Camera], Counter]:
    kept: list[Camera] = []
    dropped: Counter = Counter()
    grid: dict[tuple[int, int], list[Camera]] = defaultdict(list)

    for cam in cameras:
        cell = (int(cam.lat // _CELL_DEG), int(cam.lon // _CELL_DEG))
        duplicate_of = None
        for dr in (-1, 0, 1):
            for dc in (-1, 0, 1):
                for other in grid.get((cell[0] + dr, cell[1] + dc), ()):
                    if (
                        other.type == cam.type
                        and _distance_m(cam, other) <= radius_m
                        and _bearings_compatible(cam.bearing, other.bearing)
                    ):
                        duplicate_of = other
                        break
                if duplicate_of:
                    break
            if duplicate_of:
                break
        if duplicate_of:
            dropped[f"{cam.source}->{duplicate_of.source}"] += 1
        else:
            grid[cell].append(cam)
            kept.append(cam)
    return kept, dropped
