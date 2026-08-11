from twsp_pipeline.dedupe import collapse_id_duplicates, dedupe
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


def test_collapse_id_duplicates_keeps_most_informative_type():
    gantry = dict(lat=25.1308105, lon=121.7430319, speed_limit=None, bearing=180.0,
                  city="基隆市", description="八堵路127巷口", source="gov.tw:178159",
                  last_seen="2026-08-11")
    tech = Camera(id="d3a02fa6391b", type="tech", **gantry)
    red = Camera(id="d3a02fa6391b", type="red_light", **gantry)
    other = Camera(id="ffffffffffff", type="tech", **gantry)
    kept, dropped = collapse_id_duplicates([tech, red, other])
    assert [c.id for c in kept] == ["d3a02fa6391b", "ffffffffffff"]
    assert kept[0].type == "red_light"  # red_light beats tech for the same pole
    assert dropped["same_device:gov.tw:178159:tech"] == 1


DEG_40M_LAT = 40 / 110_540


def test_cross_source_same_type_merges_within_wide_radius():
    # Two agencies geocoding one device disagree by ~40 m (國道一號81.8公里
    # ships 31 m apart in 13940 and 7320).
    a = cam("a", BASE_LAT, BASE_LON, source="gov.tw:13940")
    b = cam("b", BASE_LAT + DEG_40M_LAT, BASE_LON, source="gov.tw:7320")
    kept, dropped = dedupe([a, b])
    assert [c.id for c in kept] == ["a"]
    assert dropped["gov.tw:7320->gov.tw:13940"] == 1


def test_same_source_same_type_kept_beyond_30m():
    # One agency's own listings 40 m apart are distinct devices.
    a = cam("a", BASE_LAT, BASE_LON)
    b = cam("b", BASE_LAT + DEG_40M_LAT, BASE_LON)
    kept, _ = dedupe([a, b])
    assert len(kept) == 2


def test_cross_type_cross_source_merges_when_colocated():
    # A 7320 speed camera that Kaohsiung lists as red-light enforcement at
    # the same pole: one device, one alert; the winner inherits the limit.
    a = cam("a", BASE_LAT, BASE_LON, type_="fixed", source="gov.tw:7320")
    a.speed_limit = None
    b = cam("b", BASE_LAT + DEG_10M_LAT, BASE_LON, type_="red_light", source="gov.tw:176561")
    kept, dropped = dedupe([a, b])
    assert [c.id for c in kept] == ["a"]
    assert kept[0].type == "fixed"
    assert kept[0].speed_limit == 50  # donated by the dropped municipal row
    assert dropped["cross_type:gov.tw:176561:red_light->gov.tw:7320:fixed"] == 1


def test_cross_type_kept_beyond_tight_radius():
    # 20 m apart with different types: plausibly two devices at one
    # intersection — keep both.
    a = cam("a", BASE_LAT, BASE_LON, type_="fixed", source="gov.tw:7320")
    b = cam("b", BASE_LAT + 2 * DEG_10M_LAT, BASE_LON, type_="tech", source="gov.tw:178159")
    kept, _ = dedupe([a, b])
    assert len(kept) == 2


def test_cross_type_never_merges_within_one_source():
    a = cam("a", BASE_LAT, BASE_LON, type_="red_light", source="gov.tw:178159")
    b = cam("b", BASE_LAT, BASE_LON, type_="tech", source="gov.tw:178159")
    kept, _ = dedupe([a, b])
    assert len(kept) == 2


def test_merge_never_narrows_a_null_bearing():
    # Null bearing = alert both directions (fail-safe); a dropped duplicate
    # must not narrow it.
    a = cam("a", BASE_LAT, BASE_LON, bearing=None, source="gov.tw:13940")
    b = cam("b", BASE_LAT, BASE_LON, bearing=90.0, source="gov.tw:7320")
    kept, _ = dedupe([a, b])
    assert kept[0].bearing is None


def test_section_endpoints_never_cross_type_merge():
    a = cam("a", BASE_LAT, BASE_LON, type_="fixed", source="gov.tw:7320")
    b = cam("b", BASE_LAT, BASE_LON, type_="section", source="curated:sections.yaml")
    kept, _ = dedupe([a, b])
    assert len(kept) == 2
