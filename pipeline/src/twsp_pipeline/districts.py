"""District sanity check: a camera whose point lies outside the county it is
filed under, or outside the township its own description names, is a
geocoding error upstream (7320 placed five 霧峰/大里/烏日 cameras 28 km west in
彰化 and the sea; a 北屯 junction landed in the 太平 hills). Such a row alerts
riders where there is no camera and protects nobody where there is one, so it
is dropped — counted, reported and written to unresolved.csv, never silent.

Boundaries come from pipeline/data/districts.json (MOI township polygons,
simplified to ~50 m by tools/build_districts.py); the check tolerates a 1 km
county / 2 km township margin so simplification and border cameras never trip
it."""

from __future__ import annotations

import json
import math
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

from .model import Camera, Unresolved

DEFAULT_PATH = Path(__file__).resolve().parents[2] / "data" / "districts.json"
# A row filed under the wrong county is wrong by tens of kilometres; a township
# token can legitimately sit a little across a border (台78's 13K is 1.6 km
# into 臺西鄉 while its row says 東勢鄉), so the township margin is wider.
COUNTY_TOLERANCE_M = 1000.0
TOWNSHIP_TOLERANCE_M = 2000.0
# Leading township token of a description: 「霧峰區中正路…」, 「臺中市西屯區…」,
# 「頭城鎮烏石港路…」. Only tokens that name a real township of the camera's
# county are acted on, so road names like 園區路 or 市道182線 never match.
_TOKEN = re.compile(r"^(?:[一-鿿]{2}[市縣])?([一-鿿]{1,3}[區鄉鎮市])")


@dataclass(frozen=True)
class District:
    county: str
    town: str
    rings: tuple[tuple[tuple[float, float], ...], ...]  # lon, lat
    bbox: tuple[float, float, float, float]  # min lon, min lat, max lon, max lat

    def contains(self, lat: float, lon: float) -> bool:
        if not (self.bbox[0] <= lon <= self.bbox[2] and self.bbox[1] <= lat <= self.bbox[3]):
            return False
        inside = False
        for ring in self.rings:
            x1, y1 = ring[-1]
            for x2, y2 in ring:
                if (y1 > lat) != (y2 > lat):
                    x_at = x1 + (lat - y1) * (x2 - x1) / (y2 - y1)
                    if lon < x_at:
                        inside = not inside
                x1, y1 = x2, y2
        return inside

    def distance_m(self, lat: float, lon: float) -> float:
        """Metres from the point to the nearest ring segment (0 when inside)."""
        if self.contains(lat, lon):
            return 0.0
        kx = 111320.0 * math.cos(math.radians(lat))
        ky = 111320.0
        best = math.inf
        for ring in self.rings:
            x1, y1 = ring[-1]
            for x2, y2 in ring:
                ax, ay = (x1 - lon) * kx, (y1 - lat) * ky
                bx, by = (x2 - lon) * kx, (y2 - lat) * ky
                dx, dy = bx - ax, by - ay
                seg = dx * dx + dy * dy
                t = 0.0 if seg == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / seg))
                px, py = ax + t * dx, ay + t * dy
                best = min(best, math.hypot(px, py))
                x1, y1 = x2, y2
        return best


def _norm(name: str) -> str:
    return name.replace("台", "臺")


class Districts:
    def __init__(self, districts: list[District]):
        self.districts = districts
        self.by_county: dict[str, list[District]] = {}
        for d in districts:
            self.by_county.setdefault(d.county, []).append(d)

    @classmethod
    def load(cls, path: Path = DEFAULT_PATH) -> Districts:
        payload = json.loads(path.read_text(encoding="utf-8"))
        out = []
        for f in payload["features"]:
            rings = tuple(tuple((float(x), float(y)) for x, y in ring) for ring in f["rings"])
            xs = [x for ring in rings for x, _ in ring]
            ys = [y for ring in rings for _, y in ring]
            out.append(District(f["county"], f["town"], rings, (min(xs), min(ys), max(xs), max(ys))))
        return cls(out)

    def locate(self, lat: float, lon: float) -> District | None:
        for d in self.districts:
            if d.contains(lat, lon):
                return d
        return None

    def town_token(self, county: str, description: str) -> District | None:
        m = _TOKEN.match(description)
        if not m:
            return None
        token = _norm(m.group(1))
        return next((d for d in self.by_county.get(county, ()) if d.town == token), None)

    def nearest_m(self, candidates: list[District], lat: float, lon: float) -> float:
        return min((d.distance_m(lat, lon) for d in candidates), default=math.inf)


def check_districts(
    cameras: list[Camera], districts: Districts
) -> tuple[list[Camera], list[Unresolved], Counter, list[str]]:
    """Drops cameras placed >1 km outside their county, or >2 km outside the
    township their description leads with. Cameras filed under a non-county
    city (國道) are passed through."""
    kept: list[Camera] = []
    unresolved: list[Unresolved] = []
    dropped: Counter = Counter()
    report: list[str] = []
    for cam in cameras:
        county = _norm(cam.city)
        county_polys = districts.by_county.get(county)
        if not county_polys:
            kept.append(cam)
            continue
        here = districts.locate(cam.lat, cam.lon)
        reason = None
        if here is None or here.county != county:
            far = districts.nearest_m(county_polys, cam.lat, cam.lon)
            if far > COUNTY_TOLERANCE_M:
                where = f"{here.county}{here.town}" if here else "no township (sea)"
                reason = f"filed under {county} but {far / 1000:.1f} km outside it, in {where}"
        if reason is None:
            named = districts.town_token(county, cam.description)
            if named is not None and (here is None or here.town != named.town):
                far = named.distance_m(cam.lat, cam.lon)
                if far > TOWNSHIP_TOLERANCE_M:
                    where = here.town if here else "no township"
                    reason = f"description names {named.town} but the point is {far / 1000:.1f} km away, in {where}"
        if reason is None:
            kept.append(cam)
            continue
        dropped[cam.source] += 1
        report.append(f"{cam.source} {cam.description!r} ({cam.lat:.5f}, {cam.lon:.5f}): {reason}")
        unresolved.append(Unresolved(cam.source, f"district check: {reason}", {"description": cam.description, "lat": cam.lat, "lon": cam.lon}))
    return kept, unresolved, dropped, report
