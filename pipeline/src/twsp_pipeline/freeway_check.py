"""Freeway kilometre-marker validation and cross-source marker dedupe.

Both national datasets describe freeway cameras as 國道N號<方向>向K公里.
Straight-line distance between two markers on the same freeway can never
exceed their kilometre difference — the road is at least as long as the
chord — so a row that breaks the bound against same-freeway rows is
mislocated (7320 has shipped 國道一號南向279公里 in central Taipei,
~200 km from km 279, firing ghost alerts on 建國高架). The slack absorbs
geocode jitter, carriageway separation and the 汐五 elevated section's
independent mileage datum; the corruption being caught is tens of km.

A row is dropped only when it conflicts with two or more rows: the
corrupt row collects violations while each honest neighbour sees one.
An isolated pair is geometrically undecidable, so both rows are kept
(fail-safe) and reported.

After validation, rows from different sources carrying the same marker
(same freeway, direction, km within 0.2) that sit within 1 km are one
device geocoded twice — beyond dedupe's 45 m cross-source radius but the
marker says same pole. The earlier source wins, mirroring dedupe
priority, and (like dedupe) the dropped row donates its speed limit.
Rows that name a freeway but carry no kilometre (interchange-ramp
cameras in 100856) get a corridor test instead: they must sit within
CORRIDOR_KM of some marker row on the same freeway. A freeway with no
marker rows leaves its ramp rows unchecked (fail-safe).

All drops are counted and reported, never silent.
"""

import math
import re
from collections import Counter, defaultdict

from .dedupe import _bearings_compatible
from .model import Camera

SLACK_KM = 15.0
CORRIDOR_KM = 15.0
MIN_CONFLICTS = 2
MARKER_KM_TOL = 0.2
DUP_MAX_KM = 1.0

_NUMERALS = {"一": "1", "二": "2", "三": "3", "四": "4", "五": "5",
             "六": "6", "七": "7", "八": "8", "九": "9", "十": "10"}
_HEAD = re.compile(r"^國道\s*([0-9]+|[一二三四五六七八九十]+)\s*號?\s*([甲乙])?")
# Lookbehind rejects bidirectional text (東西向/南北向) — no single direction.
_DIRECTION = re.compile(r"(?<![東西南北])([東西南北])向")
# 公里 must follow the number directly: a bare K would also match ramp
# chainage (彰化系統交流道 匝道5K+514) and misread a correct row as km 5.
_KM = re.compile(r"(\d+(?:\.\d+)?)\s*公里")

_EARTH_RADIUS_KM = 6371.0


def freeway_of(description: str | None) -> str | None:
    """Freeway key ("1", "3甲") when the description starts with 國道…, else None."""
    if not description:
        return None
    m = _HEAD.match(description)
    if not m:
        return None
    return _NUMERALS.get(m.group(1), m.group(1)) + (m.group(2) or "")


def parse_marker(description: str | None) -> tuple[str, str | None, float] | None:
    """(freeway, direction, km) from a 國道…公里 description, else None.

    Anchored at the start of the string so municipal descriptions that
    merely mention a freeway mid-text stay out of the freeway groups.
    """
    if not description:
        return None
    freeway = freeway_of(description)
    if freeway is None:
        return None
    km = _KM.search(description)
    if not km:
        return None
    direction = _DIRECTION.search(description)
    return freeway, direction.group(1) if direction else None, float(km.group(1))


def _distance_km(a: Camera, b: Camera) -> float:
    lat1, lon1, lat2, lon2 = map(math.radians, (a.lat, a.lon, b.lat, b.lon))
    h = (math.sin((lat2 - lat1) / 2) ** 2
         + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2)
    return 2 * _EARTH_RADIUS_KM * math.asin(math.sqrt(h))


def _validate(cameras: list[Camera]) -> tuple[list[Camera], Counter, list[str]]:
    dropped: Counter = Counter()
    report: list[str] = []
    removed: set[int] = set()

    groups: dict[str, list[int]] = defaultdict(list)
    markers: dict[int, tuple[str, str | None, float]] = {}
    for i, cam in enumerate(cameras):
        marker = parse_marker(cam.description)
        if marker:
            groups[marker[0]].append(i)
            markers[i] = marker

    for idxs in groups.values():
        active = list(idxs)
        while len(active) >= 2:
            conflicts = {i: 0 for i in active}
            excess = {i: 0.0 for i in active}
            for pos, a in enumerate(active):
                for b in active[pos + 1:]:
                    over = _distance_km(cameras[a], cameras[b]) - (
                        abs(markers[a][2] - markers[b][2]) + SLACK_KM)
                    if over > 0:
                        conflicts[a] += 1
                        conflicts[b] += 1
                        excess[a] += over
                        excess[b] += over
            worst = max(active, key=lambda i: (conflicts[i], excess[i]))
            if conflicts[worst] < MIN_CONFLICTS:
                for i in active:
                    if conflicts[i]:
                        report.append(
                            f"kept (pair conflict, geometry undecidable): "
                            f"{cameras[i].source} {cameras[i].description} "
                            f"({cameras[i].lat}, {cameras[i].lon})")
                break
            active.remove(worst)
            removed.add(worst)
            cam = cameras[worst]
            dropped[f"freeway_mislocated:{cam.source}"] += 1
            report.append(
                f"mislocated: {cam.source} {cam.description} "
                f"({cam.lat}, {cam.lon}) conflicts with {conflicts[worst]} "
                f"same-freeway rows")

    kept = [cam for i, cam in enumerate(cameras) if i not in removed]
    return kept, dropped, report


def _validate_unmarked(cameras: list[Camera]) -> tuple[list[Camera], Counter, list[str]]:
    marked: dict[str, list[Camera]] = defaultdict(list)
    for cam in cameras:
        marker = parse_marker(cam.description)
        if marker:
            marked[marker[0]].append(cam)

    dropped: Counter = Counter()
    report: list[str] = []
    kept: list[Camera] = []
    for cam in cameras:
        freeway = freeway_of(cam.description)
        if freeway is None or parse_marker(cam.description) is not None or not marked.get(freeway):
            kept.append(cam)
            continue
        nearest = min(_distance_km(cam, other) for other in marked[freeway])
        if nearest <= CORRIDOR_KM:
            kept.append(cam)
            continue
        dropped[f"freeway_off_corridor:{cam.source}"] += 1
        report.append(
            f"off corridor: {cam.source} {cam.description} ({cam.lat}, {cam.lon}) "
            f"is {nearest:.0f} km from the nearest 國道{freeway} marker row")
    return kept, dropped, report


def _merge_marker_duplicates(cameras: list[Camera]) -> tuple[list[Camera], Counter, list[str]]:
    dropped: Counter = Counter()
    report: list[str] = []
    drop: set[int] = set()

    by_key: dict[tuple[str, str], list[int]] = defaultdict(list)
    markers: dict[int, tuple[str, str | None, float]] = {}
    for i, cam in enumerate(cameras):
        marker = parse_marker(cam.description)
        if marker and marker[1] is not None:
            by_key[(marker[0], marker[1])].append(i)
            markers[i] = marker

    for idxs in by_key.values():
        idxs.sort(key=lambda i: markers[i][2])
        for pos, a in enumerate(idxs):
            if a in drop:
                continue
            for b in idxs[pos + 1:]:
                if b in drop:
                    continue
                if markers[b][2] - markers[a][2] > MARKER_KM_TOL:
                    break
                if (cameras[a].source == cameras[b].source
                        or cameras[a].type != cameras[b].type
                        or _distance_km(cameras[a], cameras[b]) > DUP_MAX_KM
                        or not _bearings_compatible(cameras[a].bearing, cameras[b].bearing)):
                    continue
                # Input order is DATASETS priority, same convention as dedupe.
                winner, loser = (a, b) if a < b else (b, a)
                if cameras[winner].speed_limit is None and cameras[loser].speed_limit is not None:
                    cameras[winner].speed_limit = cameras[loser].speed_limit
                drop.add(loser)
                dropped[f"freeway_marker_dup:{cameras[loser].source}->{cameras[winner].source}"] += 1
                report.append(
                    f"marker dup: {cameras[loser].source} {cameras[loser].description} "
                    f"-> {cameras[winner].source} {cameras[winner].description} "
                    f"({_distance_km(cameras[a], cameras[b]) * 1000:.0f} m apart)")

    kept = [cam for i, cam in enumerate(cameras) if i not in drop]
    return kept, dropped, report


def check_freeway_markers(cameras: list[Camera]) -> tuple[list[Camera], Counter, list[str]]:
    """Validate marker geometry, corridor-test unmarked freeway rows against
    the surviving markers, then merge same-marker cross-source rows."""
    kept, dropped, report = _validate(cameras)
    kept, corridor_dropped, corridor_report = _validate_unmarked(kept)
    dropped.update(corridor_dropped)
    kept, dup_dropped, dup_report = _merge_marker_duplicates(kept)
    dropped.update(dup_dropped)
    return kept, dropped, report + corridor_report + dup_report
