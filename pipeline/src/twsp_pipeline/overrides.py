"""Curated per-row drops (pipeline/data/overrides.yaml): the escape hatch for a
source row whose coordinate is wrong by road-level evidence while the same
camera exists, correctly placed, in another source. Matching is exact on
(source, description) so an upstream fix or rename surfaces as an unmatched
override in the build report instead of silently continuing to drop."""

from collections import Counter
from pathlib import Path

import yaml

from .model import Camera

DEFAULT_PATH = Path("pipeline/data/overrides.yaml")


def load_overrides(path: Path) -> list[dict]:
    if not path.exists():
        return []
    payload = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    drops = payload.get("drop") or []
    for entry in drops:
        if not entry.get("source") or not entry.get("description"):
            raise ValueError(f"override needs source and description: {entry!r}")
    return drops


def apply_overrides(cameras: list[Camera], overrides: list[dict]) -> tuple[list[Camera], Counter, list[dict]]:
    """Returns (kept cameras, dropped per source, overrides that matched no row)."""
    index = {(o["source"], str(o["description"]).strip()): o for o in overrides}
    kept: list[Camera] = []
    dropped: Counter = Counter()
    matched: set[tuple[str, str]] = set()
    for cam in cameras:
        key = (cam.source, cam.description.strip())
        if key in index:
            dropped[cam.source] += 1
            matched.add(key)
            continue
        kept.append(cam)
    unmatched = [o for key, o in index.items() if key not in matched]
    return kept, dropped, unmatched
