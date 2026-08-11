import pytest
from twsp_pipeline.normalize import parse_bearing

# Real values observed in dataset 7320 (拍攝方向 / direct column).
CASES = [
    ("雙向", None),
    ("南北雙向", None),
    ("東西雙向", None),
    ("北向南", 180.0),
    ("南向北", 0.0),
    ("西向東", 90.0),
    ("東向西", 270.0),
    ("往南", 180.0),
    ("往北", 0.0),
    ("南向", 180.0),
    ("北向", 0.0),
    ("北往南", 180.0),
    ("南往北", 0.0),
    ("西南向東北", 45.0),
    ("東北向西南", 225.0),
    ("東南向西北", 315.0),
    ("", None),
    (None, None),
    ("不詳", None),  # unparseable → both directions (fail-safe)
    ("南北向", None),  # both directions without 雙 marker
    (" 往北 ", 0.0),
]


@pytest.mark.parametrize(("text", "expected"), CASES)
def test_parse_bearing(text, expected):
    assert parse_bearing(text) == expected
