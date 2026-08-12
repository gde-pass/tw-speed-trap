from twsp_pipeline.freeway_check import check_freeway_markers, parse_marker
from twsp_pipeline.model import Camera


def cam(id_, lat, lon, description, bearing=180.0, source="gov.tw:13940", limit=110):
    return Camera(
        id=id_,
        lat=lat,
        lon=lon,
        type="fixed",
        speed_limit=limit,
        bearing=bearing,
        city="國道",
        description=description,
        source=source,
        last_seen="2026-08-12",
    )


DEG_PER_KM_LAT = 1 / 110.54


def km_south(km):
    """A straight north→south freeway: km marker k sits k km south of 25°N."""
    return 25.0 - km * DEG_PER_KM_LAT


def test_parse_marker_formats():
    assert parse_marker("國道一號南向279公里") == ("1", "南", 279.0)
    assert parse_marker("國道十號東向21.4公里") == ("10", "東", 21.4)
    assert parse_marker("國道3甲西向2.5公里") == ("3甲", "西", 2.5)
    assert parse_marker("國道三號南向262.01公里") == ("3", "南", 262.01)
    # provincial highways and non-marker descriptions stay out
    assert parse_marker("臺2己線北向1.1公里") is None
    assert parse_marker("建國高架道長春路入口") is None
    # a freeway mentioned mid-description is not a marker row
    assert parse_marker("中山路國道一號涵洞前") is None
    # marker without a kilometre reading cannot be validated
    assert parse_marker("國道一號泰安服務區") is None
    # ramp chainage is not a mainline kilometre — this row is a correctly
    # placed camera at 彰化系統 (N3 ~km 197) that "km 5" would falsely damn
    assert parse_marker("國道三號彰化系統交流道南北向出口交會處（匝道5K+514）公里") is None
    assert parse_marker("國道三號彰化系統交流道南北向出口交會處（匝道 5K+514）") is None
    # bidirectional text yields no single direction
    assert parse_marker("國道八號東西向0公里") == ("8", None, 0.0)
    # elevated-section rows keep the mainline freeway key
    assert parse_marker("國道一號汐五高架南向16.1公里") == ("1", "南", 16.1)
    assert parse_marker("國道三號甲線西向3.4公里") == ("3甲", "西", 3.4)


def test_mislocated_row_dropped():
    honest = [
        cam("a", km_south(10), 121.0, "國道一號南向10公里"),
        cam("b", km_south(50), 121.0, "國道一號南向50公里"),
        cam("c", km_south(90), 121.0, "國道一號南向90公里"),
    ]
    # claims km 60 but sits ~170 km from where the others prove km 60 is
    ghost = cam("g", km_south(230), 121.0, "國道一號南向60公里", source="gov.tw:7320")
    kept, dropped, report = check_freeway_markers(honest + [ghost])
    assert [c.id for c in kept] == ["a", "b", "c"]
    assert dropped["freeway_mislocated:gov.tw:7320"] == 1
    assert any("mislocated" in line for line in report)


def test_isolated_pair_conflict_keeps_both():
    # Two rows, one of them wrong — geometry alone cannot say which.
    a = cam("a", km_south(10), 121.0, "國道一號南向10公里")
    b = cam("b", km_south(200), 121.0, "國道一號南向20公里", source="gov.tw:7320")
    kept, dropped, report = check_freeway_markers([a, b])
    assert len(kept) == 2
    assert not dropped
    assert sum("pair conflict" in line for line in report) == 2


def test_different_freeways_never_conflict():
    a = cam("a", km_south(10), 121.0, "國道一號南向10公里")
    b = cam("b", km_south(300), 120.3, "國道三號南向10公里")
    c = cam("c", km_south(320), 120.3, "國道三號南向30公里")
    kept, dropped, _ = check_freeway_markers([a, b, c])
    assert len(kept) == 3
    assert not dropped


def test_same_marker_cross_source_merges_within_1km():
    # 13940 and 7320 geocode one device ~330 m apart — beyond dedupe's 45 m
    # radius, but the marker says same pole. First source wins.
    a = cam("a", km_south(313.7), 120.4, "國道三號南向313.7公里", limit=None)
    b = cam("b", km_south(313.7) + 3 * DEG_PER_KM_LAT / 1000 * 110, 120.4,
            "國道三號南向313.7公里", source="gov.tw:7320", limit=110)
    kept, dropped, _ = check_freeway_markers([a, b])
    assert [c.id for c in kept] == ["a"]
    assert dropped["freeway_marker_dup:gov.tw:7320->gov.tw:13940"] == 1
    assert kept[0].speed_limit == 110  # donated by the dropped duplicate


def test_same_marker_far_apart_not_merged():
    # Same marker but 3 km apart and neither provably mislocated: keep both.
    a = cam("a", km_south(10), 121.0, "國道一號南向10公里")
    b = cam("b", km_south(13), 121.0, "國道一號南向10公里", source="gov.tw:7320")
    kept, dropped, _ = check_freeway_markers([a, b])
    assert len(kept) == 2
    assert not any(k.startswith("freeway_marker_dup") for k in dropped)


def test_opposite_directions_never_merge():
    a = cam("a", km_south(10), 121.0, "國道一號南向10公里", bearing=180.0)
    b = cam("b", km_south(10), 121.0, "國道一號北向10公里",
            bearing=0.0, source="gov.tw:7320")
    kept, _, _ = check_freeway_markers([a, b])
    assert len(kept) == 2


def test_known_279_ghost_regression():
    # The real rows behind the 建國高架 ghost alert (docs/roadmap.md):
    # 7320 placed 國道一號南向279公里 in central Taipei, ~200 km from km 279.
    rows = [
        cam("good279", 23.373209, 120.350715, "國道一號南向279公里"),
        cam("km2", 25.106857, 121.724738, "國道一號南向2公里", limit=100),
        cam("km223", 23.842691, 120.48407, "國道一號南向223.5公里", source="gov.tw:7320"),
        cam("km233", 23.75602, 120.4677, "國道一號北向233.4公里",
            bearing=0.0, source="gov.tw:7320"),
        cam("ghost", 25.042383, 121.53301, "國道一號南向279公里", source="gov.tw:7320"),
    ]
    kept, dropped, _ = check_freeway_markers(rows)
    assert "ghost" not in [c.id for c in kept]
    assert "good279" in [c.id for c in kept]
    assert dropped["freeway_mislocated:gov.tw:7320"] == 1
