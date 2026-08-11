"""Curated average-speed section loader.

Sections come from a hand-maintained YAML file (see data/sections.yaml for
the schema) because entry/exit pairing does not exist in machine-readable
government data. Each section becomes two `section`-type camera rows (entry
and exit) plus one row in the sections table.
"""

from dataclasses import dataclass
from pathlib import Path

import yaml

from .model import Camera
from .projection import CoordinateError, normalize_coords


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
