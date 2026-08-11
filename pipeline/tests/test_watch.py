import re

from twsp_pipeline.watch import FINDINGS_SENTINEL, OK_SENTINEL, build_report, catalog_matches, new_datasets

CATALOG = """﻿"資料集識別碼","資料集名稱","提供機關"
"7320","測速執法設置點","警政署"
"99001","某某市科技執法設備設置地點","某某市政府警察局"
"99002","垃圾清運路線","某某市政府環保局"
"""

KEYWORDS = re.compile(r"科技執法|測速照相|測速執法|闖紅燈")


def test_catalog_matches_filters_by_title_and_strips_bom():
    matches = catalog_matches(CATALOG, KEYWORDS)
    assert matches == {"7320": "測速執法設置點", "99001": "某某市科技執法設備設置地點"}


def test_new_datasets_ignores_everything_in_baseline():
    matches = catalog_matches(CATALOG, KEYWORDS)
    baseline = {"used": {7320: "測速執法設置點"}, "seen": {}, "waiting_for_coordinates": {}}
    assert new_datasets(matches, baseline) == {"99001": "某某市科技執法設備設置地點"}
    baseline["seen"] = {99001: "某某市科技執法設備設置地點"}
    assert new_datasets(matches, baseline) == {}


def test_build_report_sentinels():
    report, findings = build_report({}, {}, {})
    assert not findings
    assert report.endswith(OK_SENTINEL)
    report, findings = build_report({"99001": "新資料集"}, {13940: "國道"}, {139129: "臺南"})
    assert findings
    assert report.endswith(FINDINGS_SENTINEL)
    assert "99001" in report and "13940" in report and "139129" in report
