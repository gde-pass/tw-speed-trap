from pathlib import Path

import pytest

from twsp_pipeline.curated import SOURCE_CURATED_POINTS, CuratedPointsError, load_curated_points


def test_curated_points_become_cameras_with_bearing_and_limit(tmp_path: Path):
    path = tmp_path / "points.yaml"
    path.write_text(
        """points:
  - place: 花蓮市府前路與北興路口
    city: 花蓮縣
    type: red_light
    direction: 北向南
    speed_limit: 60
    lat: 23.99
    lon: 121.60
    source: "https://data.gov.tw/dataset/177297"
  - place: 臺9線179.2K
    city: 花蓮縣
    type: fixed
    speed_limit: 70
    lat: 23.90
    lon: 121.55
    source: "https://data.gov.tw/dataset/177297"
""",
        encoding="utf-8",
    )
    cameras = load_curated_points(path, "2026-10-08T00:00")
    assert [(c.type, c.bearing, c.speed_limit, c.city) for c in cameras] == [
        ("red_light", 180.0, 60, "花蓮縣"),  # 北向南 = southbound
        ("fixed", None, 70, "花蓮縣"),
    ]
    assert {c.source for c in cameras} == {SOURCE_CURATED_POINTS}
    assert cameras[0].description == "花蓮市府前路與北興路口"


def test_missing_file_is_empty_and_bad_entries_fail_loudly(tmp_path: Path):
    assert load_curated_points(tmp_path / "none.yaml", "t") == []
    bad = tmp_path / "bad.yaml"
    bad.write_text("points:\n  - place: x\n    city: 花蓮縣\n    type: mobile\n    lat: 23.9\n    lon: 121.5\n", encoding="utf-8")
    with pytest.raises(CuratedPointsError):
        load_curated_points(bad, "t")
