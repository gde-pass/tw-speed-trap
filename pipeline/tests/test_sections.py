import sqlite3

import pytest
from twsp_pipeline.emit import write_sqlite
from twsp_pipeline.sections import SectionConfigError, load_sections

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
