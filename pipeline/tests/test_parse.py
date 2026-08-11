import pytest
from twsp_pipeline.parse import SchemaError, parse_13940, parse_7320

CSV_7320 = """CityName,RegionName,Address,DeptNm,BranchNm,Longitude,Latitude,direct,limit
設置縣市,設置市區鄉鎮,設置地址,管轄警局,管轄分局,經度,緯度,拍攝方向,速限
臺中市,西屯區,臺灣大道三段99號前,臺中市政府警察局,第六分局,120.646,24.165,往北,50
金門縣,金湖鎮,黃海路陽明湖路段,金門縣警察局,金湖分局,118.43147,24.458809,南北雙向,60
臺北市,中正區,重慶南路一段,臺北市政府警察局,中正一分局,,25.04,雙向,40
"""

CSV_13940 = """設備編號,型式,縣市,行政區,設置區域描述,設置地點,取締項目,座標緯度,座標經度,拍攝方向,速限,管轄單位,備註
1,雷達,,,,國道一號南向2公里,超速,25.106857,121.724738,北往南,100,國道公路警一隊,
2,雷達,,,,國道一號北向13.7公里,超速,25.066725,121.624945,南往北,110,國道公路警一隊,
"""


def test_7320_parses_and_skips_zh_header():
    cameras, unresolved, stats = parse_7320(CSV_7320, "2026-08-11")
    assert stats["skipped_zh_header"] == 1
    assert len(cameras) == 2
    assert len(unresolved) == 1  # missing longitude row
    taichung = cameras[0]
    assert taichung.city == "臺中市"
    assert taichung.bearing == 0.0
    assert taichung.speed_limit == 50
    assert taichung.type == "fixed"
    assert taichung.description == "臺灣大道三段99號前"


def test_7320_ids_stable():
    a, _, _ = parse_7320(CSV_7320, "2026-08-11")
    b, _, _ = parse_7320(CSV_7320, "2026-09-01")
    assert [c.id for c in a] == [c.id for c in b]


def test_13940_parses():
    cameras, unresolved, _ = parse_13940(CSV_13940, "2026-08-11")
    assert not unresolved
    assert len(cameras) == 2
    first = cameras[0]
    assert first.id == "13940-1"
    assert first.city == "國道"
    assert first.bearing == 180.0
    assert first.speed_limit == 100
    assert "國道一號" in first.description


def test_missing_columns_fail_loudly():
    with pytest.raises(SchemaError):
        parse_7320("foo,bar\n1,2\n", "2026-08-11")
    with pytest.raises(SchemaError):
        parse_13940("foo,bar\n1,2\n", "2026-08-11")
