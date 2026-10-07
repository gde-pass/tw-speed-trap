"""The in-app attribution must credit every dataset the pipeline ingests
(Open Government Data License v1.0 requires source attribution) — the parsed
sources in cli.DATASETS and every data.gov.tw dataset the curated sections
cite as their source."""

import re
from pathlib import Path

from twsp_pipeline.cli import DATASETS
from twsp_pipeline.fetch import DirectDataset

ROOT = Path(__file__).resolve().parents[2]
STRINGS = ROOT / "app/src/main/res/values/strings.xml"
SECTIONS = ROOT / "pipeline/data/sections.yaml"


def test_about_screen_lists_every_ingested_dataset():
    xml = STRINGS.read_text(encoding="utf-8")
    match = re.search(r'name="about_attribution_datasets"[^>]*>([^<]*)<', xml)
    assert match, "about_attribution_datasets string missing"
    credited = {int(x) for x in re.findall(r"\d+", match.group(1))}
    ingested = {dataset_id for dataset_id, _, _ in DATASETS if isinstance(dataset_id, int)}
    sections_yaml = SECTIONS.read_text(encoding="utf-8")
    cited = {int(x) for x in re.findall(r'^\s+source: "https://data\.gov\.tw/dataset/(\d+)"', sections_yaml, re.M)}
    expected = ingested | cited
    assert credited == expected, f"missing={expected - credited} extra={credited - expected}"


def test_about_screen_lists_every_direct_portal():
    """County portals that data.gov.tw does not index (宜蘭) are credited by host."""
    xml = STRINGS.read_text(encoding="utf-8")
    match = re.search(r'name="about_attribution_portals"[^>]*>([^<]*)<', xml)
    assert match, "about_attribution_portals string missing"
    credited = {part.strip() for part in match.group(1).split(",") if part.strip()}
    portals = {dataset.portal for dataset, _, _ in DATASETS if isinstance(dataset, DirectDataset)}
    assert credited == portals, f"missing={portals - credited} extra={credited - portals}"
