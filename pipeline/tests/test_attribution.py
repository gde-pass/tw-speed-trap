"""The in-app attribution must credit every dataset the pipeline ingests
(Open Government Data License v1.0 requires source attribution)."""

import re
from pathlib import Path

from twsp_pipeline.cli import DATASETS

STRINGS = Path(__file__).resolve().parents[2] / "app/src/main/res/values/strings.xml"


def test_about_screen_lists_every_ingested_dataset():
    xml = STRINGS.read_text(encoding="utf-8")
    match = re.search(r'name="about_attribution_datasets"[^>]*>([^<]*)<', xml)
    assert match, "about_attribution_datasets string missing"
    credited = {int(x) for x in re.findall(r"\d+", match.group(1))}
    ingested = {dataset_id for dataset_id, _, _ in DATASETS}
    assert credited == ingested, f"missing={ingested - credited} extra={credited - ingested}"
