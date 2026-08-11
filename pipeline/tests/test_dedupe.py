from twsp_pipeline.dedupe import dedupe
from twsp_pipeline.model import Camera


def cam(id_, lat, lon, type_="fixed", bearing=None, source="gov.tw:7320"):
    return Camera(
        id=id_,
        lat=lat,
        lon=lon,
        type=type_,
        speed_limit=50,
        bearing=bearing,
        city="臺中市",
        description="test",
        source=source,
        last_seen="2026-08-11",
    )


BASE_LAT, BASE_LON = 24.147, 120.673
DEG_10M_LAT = 10 / 110_540  # ~10 m in latitude degrees


def test_nearby_same_type_deduped_first_wins():
    a = cam("a", BASE_LAT, BASE_LON, source="gov.tw:13940")
    b = cam("b", BASE_LAT + DEG_10M_LAT, BASE_LON)
    kept, dropped = dedupe([a, b])
    assert [c.id for c in kept] == ["a"]
    assert dropped["gov.tw:7320->gov.tw:13940"] == 1


def test_far_apart_kept():
    a = cam("a", BASE_LAT, BASE_LON)
    b = cam("b", BASE_LAT + 10 * DEG_10M_LAT, BASE_LON)  # ~100 m
    kept, _ = dedupe([a, b])
    assert len(kept) == 2


def test_different_type_kept():
    a = cam("a", BASE_LAT, BASE_LON, type_="fixed")
    b = cam("b", BASE_LAT, BASE_LON, type_="red_light")
    kept, _ = dedupe([a, b])
    assert len(kept) == 2


def test_opposite_bearings_kept():
    # Two cameras on the same pole facing opposite directions are distinct.
    a = cam("a", BASE_LAT, BASE_LON, bearing=0.0)
    b = cam("b", BASE_LAT, BASE_LON, bearing=180.0)
    kept, _ = dedupe([a, b])
    assert len(kept) == 2


def test_null_bearing_merges_with_any_bearing():
    a = cam("a", BASE_LAT, BASE_LON, bearing=None)
    b = cam("b", BASE_LAT, BASE_LON, bearing=90.0)
    kept, _ = dedupe([a, b])
    assert [c.id for c in kept] == ["a"]


def test_cell_boundary_still_deduped():
    # Points straddling a grid cell boundary must still find each other.
    lat_edge = 24.1475  # multiple of 0.0005 → cell boundary
    a = cam("a", lat_edge - DEG_10M_LAT / 10, BASE_LON)
    b = cam("b", lat_edge + DEG_10M_LAT / 10, BASE_LON)
    kept, _ = dedupe([a, b])
    assert len(kept) == 1
