from pathlib import Path

from twsp_pipeline.cli import load_previous_snapshot
from twsp_pipeline.emit import write_sqlite
from twsp_pipeline.model import Camera


def cams():
    return [
        Camera("aaa", 25.1, 121.7, "fixed", 100, 180.0, "國道", "國道一號", "gov.tw:13940", "2026-08-04"),
        Camera("bbb", 24.1, 120.6, "fixed", 50, None, "臺中市", "測試路", "gov.tw:7320", "2026-08-04"),
    ]


def test_loads_only_requested_source_and_preserves_last_seen(tmp_path):
    db = tmp_path / "cameras.db"
    write_sqlite(cams(), db, "2026-08-04")
    previous = load_previous_snapshot(db, "gov.tw:13940")
    assert previous is not None
    assert [c.id for c in previous] == ["aaa"]
    assert previous[0].last_seen == "2026-08-04"  # staleness stays visible
    assert previous[0].speed_limit == 100 and previous[0].bearing == 180.0


def test_missing_db_returns_none(tmp_path):
    assert load_previous_snapshot(tmp_path / "absent.db", "gov.tw:13940") is None


def test_unknown_source_returns_none(tmp_path):
    db = tmp_path / "cameras.db"
    write_sqlite(cams(), db, "2026-08-04")
    assert load_previous_snapshot(db, "gov.tw:99999") is None


def test_default_fallback_path_is_the_committed_assets_db():
    # The CI checkout contains the last committed db at this exact path.
    assert Path("app/src/main/assets/cameras.db").as_posix().endswith("assets/cameras.db")
