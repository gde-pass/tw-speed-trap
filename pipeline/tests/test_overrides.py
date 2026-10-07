from pathlib import Path

import pytest

from twsp_pipeline.model import Camera
from twsp_pipeline.overrides import apply_overrides, load_overrides


def cam(source: str, description: str) -> Camera:
    return Camera("id", 24.0, 120.6, "fixed", None, None, "臺中市", description, source, "2026-10-08T00:00")


def test_drop_matches_exact_source_and_description_and_reports_unmatched():
    overrides = [
        {"source": "gov.tw:7320", "description": "北屯區北屯路與松竹路口(往市區方向)", "keep": "gov.tw:83881"},
        {"source": "gov.tw:83881", "description": "vanished row", "keep": "gov.tw:7320"},
    ]
    cameras = [
        cam("gov.tw:7320", "北屯區北屯路與松竹路口(往市區方向)"),
        cam("gov.tw:83881", "北屯區北屯路與松竹路口(往市區方向)"),  # the kept copy: other source
        cam("gov.tw:7320", "unrelated"),
    ]
    kept, dropped, unmatched = apply_overrides(cameras, overrides)
    assert [c.source for c in kept] == ["gov.tw:83881", "gov.tw:7320"]
    assert dropped == {"gov.tw:7320": 1}
    assert [o["description"] for o in unmatched] == ["vanished row"]


def test_shipped_overrides_file_is_well_formed_and_keeps_name_another_source():
    overrides = load_overrides(Path(__file__).resolve().parents[2] / "pipeline/data/overrides.yaml")
    assert overrides, "overrides.yaml should list the curated drops"
    for entry in overrides:
        assert entry["keep"] != entry["source"], entry
        assert entry["evidence"], entry


def test_missing_file_means_no_overrides(tmp_path: Path):
    assert load_overrides(tmp_path / "none.yaml") == []


def test_entry_without_description_is_rejected(tmp_path: Path):
    path = tmp_path / "bad.yaml"
    path.write_text("drop:\n  - source: gov.tw:7320\n", encoding="utf-8")
    with pytest.raises(ValueError):
        load_overrides(path)
