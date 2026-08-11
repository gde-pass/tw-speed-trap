import json
import sqlite3

from twsp_pipeline.emit import content_hash, file_sha256, write_geojson, write_manifest, write_sqlite
from twsp_pipeline.model import Camera


def cams(last_seen="2026-08-11"):
    return [
        Camera("b" * 12, 24.1, 120.6, "fixed", 50, 0.0, "臺中市", "測試路", "gov.tw:7320", last_seen),
        Camera("a" * 12, 25.0, 121.5, "fixed", None, None, "臺北市", "測試街", "gov.tw:7320", last_seen),
    ]


def test_sqlite_roundtrip(tmp_path):
    db = tmp_path / "cameras.db"
    write_sqlite(cams(), db, "2026-08-11")
    conn = sqlite3.connect(db)
    rows = conn.execute("SELECT id, city, speed_limit, bearing FROM cameras ORDER BY id").fetchall()
    meta = dict(conn.execute("SELECT key, value FROM meta").fetchall())
    conn.close()
    assert len(rows) == 2
    assert rows[0][0] == "a" * 12  # sorted by id
    assert rows[0][2] is None and rows[0][3] is None  # nullables survive
    assert meta["schema_version"] == "1"
    assert meta["count"] == "2"


def test_manifest_sha_matches_file(tmp_path):
    db = tmp_path / "cameras.db"
    write_sqlite(cams(), db, "2026-08-11")
    manifest = write_manifest(cams(), db, tmp_path / "manifest.json", "2026-08-11")
    assert manifest["sha256"] == file_sha256(db)
    assert manifest["count"] == 2
    on_disk = json.loads((tmp_path / "manifest.json").read_text())
    assert on_disk == manifest


def test_content_hash_ignores_last_seen():
    assert content_hash(cams("2026-08-11")) == content_hash(cams("2026-12-25"))


def test_content_hash_changes_with_data():
    changed = cams()
    changed[0].speed_limit = 60
    assert content_hash(cams()) != content_hash(changed)


def test_geojson_deterministic_and_readable(tmp_path):
    p1, p2 = tmp_path / "a.geojson", tmp_path / "b.geojson"
    write_geojson(cams(), p1)
    write_geojson(list(reversed(cams())), p2)  # input order must not matter
    assert p1.read_bytes() == p2.read_bytes()
    fc = json.loads(p1.read_text())
    assert fc["type"] == "FeatureCollection"
    assert len(fc["features"]) == 2
    assert fc["features"][0]["properties"]["city"] == "臺北市"
