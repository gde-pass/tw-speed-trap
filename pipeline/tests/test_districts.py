"""District sanity check against the shipped township boundaries. The
coordinates are the real upstream rows the check was built on (2026-10)."""

from twsp_pipeline.districts import Districts, check_districts
from twsp_pipeline.model import Camera

D = Districts.load()


def cam(lat: float, lon: float, city: str, description: str, source: str = "gov.tw:7320") -> Camera:
    return Camera("id", lat, lon, "fixed", None, None, city, description, source, "2026-10-08T00:00")


def test_locate_returns_the_township():
    here = D.locate(24.04834, 120.69346)  # 83881's 霧峰區中正路569-9號前
    assert (here.county, here.town) == ("臺中市", "霧峰區")
    assert D.locate(24.0, 119.0) is None  # Taiwan Strait


def test_row_filed_under_one_county_but_placed_in_another_is_dropped():
    kept, unresolved, dropped, report = check_districts([cam(24.02899, 120.41613, "臺中市", "霧峰區中正路569-9號前")], D)
    assert kept == []
    assert dropped == {"gov.tw:7320": 1}
    assert "彰化縣" in report[0] and "16." in report[0]
    assert unresolved[0].reason.startswith("district check: filed under 臺中市")


def test_row_in_the_sea_is_dropped():
    kept, _, _, report = check_districts([cam(22.29069, 120.29270, "屏東縣", "187乙線17.33K")], D)
    assert kept == [] and "sea" in report[0]


def test_description_township_far_from_the_point_is_dropped():
    row = cam(24.14937, 120.71436, "臺中市", "潭子區中山路與環中東路口", "gov.tw:170673")
    kept, _, dropped, report = check_districts([row], D)
    assert kept == [] and dropped == {"gov.tw:170673": 1}
    assert "names 潭子區" in report[0] and "太平區" in report[0]


def test_border_and_expressway_rows_within_tolerance_are_kept():
    rows = [
        cam(24.14412, 120.71204, "臺中市", "東區環中東路四段近東義街口"),  # road runs along the 東區/太平區 border
        cam(23.68522, 120.20734, "雲林縣", "雲林縣東勢鄉台78線快速公路13k處", "gov.tw:178085"),  # 1.6 km into 臺西鄉
    ]
    kept, _, dropped, _ = check_districts(rows, D)
    assert len(kept) == 2 and not dropped


def test_rows_without_a_county_or_a_township_token_pass_through():
    rows = [
        cam(24.5, 120.9, "國道", "國道1號 150K"),
        cam(24.14296, 120.65723, "臺中市", "市道182線27.68K處"),  # 市道 is not a township
    ]
    kept, _, dropped, _ = check_districts(rows, D)
    assert len(kept) == 2 and not dropped
