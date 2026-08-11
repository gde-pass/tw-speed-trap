"""Curated average-speed section loader.

Sections come from a hand-maintained YAML file (see data/sections.yaml for
the schema) because entry/exit pairing does not exist in machine-readable
government data. Each section becomes two `section`-type camera rows (entry
and exit) plus one row in the sections table.
"""

import math
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

import yaml

from .model import Camera
from .projection import CoordinateError, normalize_coords

# 7320 lists average-speed zones as point rows described 「A至B」, with the
# coordinate anywhere along the zone. Once a curated section covers that
# zone, the point row is a redundant second alert — suppress it when it lies
# within this distance of the entry→exit corridor.
HINT_CORRIDOR_M = 400.0

_M_PER_DEG_LAT = 110_540.0
_M_PER_DEG_LON_EQ = 111_320.0


@dataclass
class Section:
    id: str
    speed_limit_kmh: int
    length_m: float


class SectionConfigError(ValueError):
    pass


def load_sections(path: Path, today: str) -> tuple[list[Section], list[Camera]]:
    if not path.exists():
        return [], []
    raw = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    sections: list[Section] = []
    cameras: list[Camera] = []
    for entry in raw.get("sections") or []:
        try:
            section = Section(
                id=str(entry["id"]),
                speed_limit_kmh=int(entry["speed_limit_kmh"]),
                length_m=float(entry["length_m"]),
            )
            for role in ("entry", "exit"):
                point = entry[role]
                lat, lon = normalize_coords(point["lat"], point["lon"])
                cameras.append(
                    Camera(
                        id=f"sec-{section.id}-{role}",
                        lat=lat,
                        lon=lon,
                        type="section",
                        speed_limit=section.speed_limit_kmh,
                        bearing=float(point["bearing_deg"]) if point.get("bearing_deg") is not None else None,
                        city=str(entry.get("city", "")),
                        description=str(entry.get("name", section.id)),
                        source="curated:sections.yaml",
                        last_seen=today,
                        section_id=section.id,
                        section_role="start" if role == "entry" else "end",
                    )
                )
            sections.append(section)
        except (KeyError, TypeError, ValueError, CoordinateError) as e:
            raise SectionConfigError(f"invalid section entry {entry!r}: {e}") from e
    return sections, cameras


def _segment_distance_m(lat: float, lon: float, a: Camera, b: Camera) -> float:
    """Distance from a point to the a→b segment, in a local flat projection
    (fine at the few-km scale of a section)."""
    kx = _M_PER_DEG_LON_EQ * math.cos(math.radians(lat))
    px, py = lon * kx, lat * _M_PER_DEG_LAT
    ax, ay = a.lon * kx, a.lat * _M_PER_DEG_LAT
    bx, by = b.lon * kx, b.lat * _M_PER_DEG_LAT
    dx, dy = bx - ax, by - ay
    length_sq = dx * dx + dy * dy
    t = 0.0 if length_sq == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / length_sq))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def suppress_section_hint_points(
    cameras: list[Camera],
    section_cameras: list[Camera],
    source: str,
    radius_m: float = HINT_CORRIDOR_M,
) -> tuple[list[Camera], Counter]:
    """Drop `source` point rows described 「A至B」 that lie along a curated
    section's corridor. Dropped rows are counted, never silent."""
    corridors: dict[str, dict[str, Camera]] = {}
    for cam in section_cameras:
        if cam.section_id and cam.section_role:
            corridors.setdefault(cam.section_id, {})[cam.section_role] = cam
    pairs = [(ends["start"], ends["end"]) for ends in corridors.values() if "start" in ends and "end" in ends]

    kept: list[Camera] = []
    dropped: Counter = Counter()
    for cam in cameras:
        if (
            cam.source == source
            and "至" in cam.description
            and any(_segment_distance_m(cam.lat, cam.lon, a, b) <= radius_m for a, b in pairs)
        ):
            dropped[f"section_hint_suppressed:{cam.description}"] += 1
        else:
            kept.append(cam)
    return kept, dropped
