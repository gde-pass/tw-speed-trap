import sqlite3

import pytest
from twsp_pipeline.model import Camera
from twsp_pipeline.emit import write_sqlite
from twsp_pipeline.sections import suppress_section_hint_points, SectionConfigError, load_sections

VALID = """
sections:
  - id: test-nb
    name: 測試區間 北向
    speed_limit_kmh: 90
    length_m: 12900
    entry: { lat: 24.10, lon: 120.65, bearing_deg: 0 }
    exit:  { lat: 24.20, lon: 120.65, bearing_deg: 0 }
"""


def test_load_valid_sections(tmp_path):
    path = tmp_path / "sections.yaml"
    path.write_text(VALID, encoding="utf-8")
    sections, cameras = load_sections(path, "2026-08-11")
    assert len(sections) == 1
    assert sections[0].length_m == 12900
    assert [c.id for c in cameras] == ["sec-test-nb-entry", "sec-test-nb-exit"]
    assert all(c.type == "section" for c in cameras)
    assert cameras[0].section_role == "start"
    assert cameras[1].section_role == "end"


def test_missing_file_is_empty(tmp_path):
    sections, cameras = load_sections(tmp_path / "absent.yaml", "2026-08-11")
    assert sections == [] and cameras == []


def test_empty_sections_key(tmp_path):
    path = tmp_path / "sections.yaml"
    path.write_text("sections: []\n", encoding="utf-8")
    assert load_sections(path, "2026-08-11") == ([], [])


def test_invalid_section_fails_loudly(tmp_path):
    path = tmp_path / "sections.yaml"
    path.write_text("sections:\n  - id: broken\n", encoding="utf-8")
    with pytest.raises(SectionConfigError):
        load_sections(path, "2026-08-11")


def test_sections_written_to_sqlite(tmp_path):
    path = tmp_path / "sections.yaml"
    path.write_text(VALID, encoding="utf-8")
    sections, cameras = load_sections(path, "2026-08-11")
    db = tmp_path / "cameras.db"
    write_sqlite(cameras, db, "2026-08-11", sections)
    conn = sqlite3.connect(db)
    rows = conn.execute("SELECT id, speed_limit, length_m FROM sections").fetchall()
    endpoint_roles = conn.execute("SELECT section_id, section_role FROM cameras ORDER BY id").fetchall()
    conn.close()
    assert rows == [("test-nb", 90, 12900.0)]
    assert endpoint_roles == [("test-nb", "start"), ("test-nb", "end")]


def test_suppress_section_hint_points_uses_corridor_not_endpoints():
    entry = Camera(id="sec-x-entry", lat=24.20000, lon=120.56000, type="section",
                   speed_limit=50, bearing=None, city="臺中市", description="向上路",
                   source="curated:sections.yaml", last_seen="2026-08-11",
                   section_id="x", section_role="start")
    exit_ = Camera(id="sec-x-exit", lat=24.20000, lon=120.59000, type="section",
                   speed_limit=50, bearing=None, city="臺中市", description="向上路",
                   source="curated:sections.yaml", last_seen="2026-08-11",
                   section_id="x", section_role="end")
    def cam(i, lat, lon, desc, source="gov.tw:7320"):
        return Camera(id=i, lat=lat, lon=lon, type="fixed", speed_limit=50,
                      bearing=None, city="臺中市", description=desc,
                      source=source, last_seen="2026-08-11")
    midway_hint = cam("a", 24.20100, 120.57500, "向上路6段與中興路口至自立路口")  # ~110 m off mid-corridor
    far_hint = cam("b", 24.25000, 120.57500, "甲路至乙路")  # ~5.5 km away
    near_plain = cam("c", 24.20050, 120.57500, "向上路六段99號前")  # no 至 -> stays
    other_source = cam("d", 24.20100, 120.57500, "某路至某路", source="gov.tw:170673")
    kept, dropped = suppress_section_hint_points(
        [midway_hint, far_hint, near_plain, other_source], [entry, exit_], "gov.tw:7320"
    )
    assert [c.id for c in kept] == ["b", "c", "d"]
    assert sum(dropped.values()) == 1
    assert any("中興路口" in k for k in dropped)
