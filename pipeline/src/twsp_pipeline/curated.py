"""Curated point cameras (pipeline/data/curated_points.yaml): cameras transcribed
from lists that are not machine-readable — 花蓮's PDF (data.gov.tw 177297)
names 43 poles and junctions with no coordinates. Each entry records where it
came from (dataset, edition), how it was placed (method) and what the list
says (items, limit, direction), so the weekly build stays deterministic and
the entry can be re-checked when the agency publishes a new edition."""

from pathlib import Path

import yaml

from .model import Camera
from .normalize import make_id, parse_bearing, parse_limit
from .projection import CoordinateError, normalize_coords

SOURCE_CURATED_POINTS = "curated:points.yaml"
DEFAULT_PATH = Path("pipeline/data/curated_points.yaml")


class CuratedPointsError(RuntimeError):
    pass


def load_curated_points(path: Path, today: str) -> list[Camera]:
    if not path.exists():
        return []
    payload = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    cameras: list[Camera] = []
    for entry in payload.get("points") or []:
        try:
            lat, lon = normalize_coords(entry["lat"], entry["lon"])
            cam_type = str(entry["type"])
            if cam_type not in ("fixed", "red_light", "tech"):
                raise ValueError(f"type {cam_type!r}")
            bearing = parse_bearing(entry.get("direction"))
            cameras.append(
                Camera(
                    id=make_id(SOURCE_CURATED_POINTS, lat, lon, bearing),
                    lat=lat,
                    lon=lon,
                    type=cam_type,
                    speed_limit=parse_limit(str(entry.get("speed_limit", "") or "")),
                    bearing=bearing,
                    city=str(entry["city"]),
                    description=str(entry["place"]),
                    source=SOURCE_CURATED_POINTS,
                    last_seen=today,
                )
            )
        except (KeyError, TypeError, ValueError, CoordinateError) as e:
            raise CuratedPointsError(f"invalid curated point {entry!r}: {e}") from e
    return cameras
