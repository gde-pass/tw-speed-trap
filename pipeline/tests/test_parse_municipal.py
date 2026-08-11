"""Municipal parser tests. Fixture rows are copied verbatim from the live
downloads so each dataset's real quirks stay exercised: double BOM, swapped
經度/緯度, literal 區間測速 in coordinate fields, multi-line quoted fields,
non-numeric speed limits, and whitespace-packed single 座標 columns."""

import pytest
from twsp_pipeline.parse import (
    SchemaError,
    parse_130111,
    parse_135957,
    parse_170673,
    parse_176549,
    parse_176555,
    parse_176558,
    parse_176560,
    parse_176561,
    parse_177827,
    parse_25935,
)

# Header carries a DOUBLE BOM; 經度 holds latitude (~24.x) and 緯度 longitude;
# section rows have the literal text 區間測速 in both coordinate fields.
CSV_170673 = (
    "﻿﻿編號,科技執法種類,設置地點,取締項目,經度,緯度\n"
    '"1","區間平均速率執法","龍井區向上路6段與中興路口至沙鹿區向上路6段與自立路口","超速、未依規定行駛車道，偵測長度1,601.16公尺","24.202772","120.568183"\n'
    '"2","區間平均速率執法","台61線快速道路157.8K至151.6K(南向北)","超速，偵測長度6,169.1公尺，速限90公里","區間測速","區間測速"\n'
    '"6","路口多功能執法","西區臺灣大道與忠明南路口西區臺灣大道與忠明路口","西區臺灣大道與忠明南路口西區臺灣大道與忠明路口","24.157244","120.659803"\n'
    '"4","違規停車取締","烏日高鐵站站區一路(1樓乘客上客區、2樓乘客下客區)","違規(臨時)停車、違規上、下客、違規攬客","24.111647","120.616023"\n'
)

CSV_130111 = """編號,功能,設置路段,設置地點,緯度,經度,轄區,拍攝方向,速限-速度限制,縣市,縣市代碼
1,測速,承德路3段,敦煌路口,25.07450558,121.5199333,大同,南北雙向,50,臺北市,63000
3,測速(兼闖紅燈),台北橋機車道下橋,近民權西路與延平北路口,25.06287864,121.5108324,大同,西向東,40,臺北市,63000
4,闖紅燈,承德路2段,錦西街口,25.06042103,121.5182331,大同,北向南,50,臺北市,63000
6,闖紅燈及路口淨空,民生西路,承德路2段口,25.0569504,121.5181914,大同,南向北,\\,臺北市,63000
2,測速,環河北路2段,昌吉街口,25.06589275,121.5088674,大同,南北雙向,"50(往北)
60(往南)",臺北市,63000
142,區間測速及跨越雙白線,自強隧道,,25.09060141,121.5492556,中山、士林,南北雙向,50,臺北市,63000
"""

# 座標-X is longitude, 座標-Y latitude; 啟用日期 spans multiple lines.
CSV_135957 = """編號,名稱,取締路段,座標-X,座標-Y,啟用日期,取締項目
1,區間平均速率,自強隧道,121.549309,25.090898,"108年9月1日
109年4月停用
111年2月21日重啟",超速、跨越雙白線
5,路口多功能,鄭州路與西寧北路口,121.508087,25.050537,111年1月3日,闖紅燈、不停讓行人、不依規定轉彎、不依標誌標線號誌指示行駛
10,違規停車,重慶北路三段278號前,121.513304,25.072913,110年7月1日,違規停車
"""

CSV_25935 = """設備編號,型式,縣市,行政區,設置區域描述,設置地點_路口或路段,取締項目,座標緯度,座標經度,拍攝方向,速限,管轄單位,備註
1,測速暨闖紅燈照相執法,桃園市,桃園區,,成功路三段235號前,超速,25.00729,121.32475,往桃園市區方向,40,桃園分局,
15,測速暨闖紅燈照相執法,桃園市,中壢區,,中華路普忠路口,闖紅燈,24.965984,121.24136,往中壢方向,x,中壢分局,
"""

CSV_176549 = """Seq,編號,型式,測照地點,測照方向,速限,行政區,測照型式,座標緯度,座標經度
1,1,非線圈數位-雷射,民族一路與十全一路口,北向南,60,三民,闖紅燈,22.644858,120.314341
16,16,非線圈數位-雷射(雷達),大中一路與鼎中路口東向西右側,東向西,50,三民,闖紅燈兼超速,22.676705,120.323591
7,7,線圈數位,中山高西側便道與九如一路口,北向南,違左,三民,違左,22.63776,120.336788
76,76,非線圈數位-雷射,小港區沿海一路與康莊路口,北向南,60/40,小港,闖紅燈,22.568001,120.350672
"""

# The five same-shape Kaohsiung 科技執法 datasets differ only in whether the
# location column is 地點 or 設置位置 (176561 adds a stray remarks Column1).
CSV_176558 = """Seq,編號,地點,測照行向,取締項目,座標緯度,座標經度
5,5,大寮區市道188號/鳳林二路口(西向),東向西,路口各項違規(闖紅燈、不依標誌標線號誌行駛等),22.586728,120.40514
"""

CSV_176561 = """Seq,編號,設置位置,取締項目,測照行向,座標緯度,座標經度,Column1
18,18,小港區中山四路/平和東路,路口各項違規(闖紅燈、超速、不依標誌標線號誌行駛等),北向南,22.570253,120.33743,有取締超速功能
"""

CSV_176555 = """Seq,編號,設置位置,取締項目,測照行向,座標緯度,座標經度
1,1,三民區大昌二路/義華路,車不停讓行人,北側,22.643492,120.332100
"""


def test_170673_double_bom_swapped_coords_and_section_exclusion():
    cameras, unresolved, stats = parse_170673(CSV_170673, "2026-08-11")
    assert stats["170673_sections_excluded"] == 2  # both 區間平均速率執法 rows
    assert not unresolved  # 區間測速-in-coords rows never reach normalize_coords
    assert len(cameras) == 2
    assert all(cam.type == "tech" for cam in cameras)
    assert all(24.0 < cam.lat < 24.5 and 120.5 < cam.lon < 121.0 for cam in cameras)
    assert cameras[0].city == "臺中市"
    assert cameras[0].description == "西區臺灣大道與忠明南路口西區臺灣大道與忠明路口"


def test_130111_type_split_and_section_exclusion():
    cameras, unresolved, stats = parse_130111(CSV_130111, "2026-08-11")
    assert not unresolved
    assert stats["130111_sections_excluded"] == 1
    types = {cam.description: cam.type for cam in cameras}
    assert types["承德路3段 敦煌路口"] == "fixed"
    assert types["台北橋機車道下橋 近民權西路與延平北路口"] == "fixed"  # 測速(兼闖紅燈) stays fixed
    assert types["承德路2段 錦西街口"] == "red_light"
    assert types["民生西路 承德路2段口"] == "red_light"  # 闖紅燈及路口淨空
    by_desc = {cam.description: cam for cam in cameras}
    assert by_desc["承德路2段 錦西街口"].bearing == 180.0  # 北向南
    assert by_desc["民生西路 承德路2段口"].speed_limit is None  # literal backslash
    assert by_desc["環河北路2段 昌吉街口"].speed_limit is None  # multi-line per-direction limit


def test_135957_xy_columns_and_multiline_fields():
    cameras, unresolved, stats = parse_135957(CSV_135957, "2026-08-11")
    assert not unresolved
    assert stats["135957_sections_excluded"] == 1  # 區間平均速率 despite multi-line dates
    assert len(cameras) == 2
    intersection = next(cam for cam in cameras if cam.description == "鄭州路與西寧北路口")
    assert intersection.type == "red_light"
    assert 25.0 < intersection.lat < 25.1 and 121.5 < intersection.lon < 121.6
    parking = next(cam for cam in cameras if cam.description == "重慶北路三段278號前")
    assert parking.type == "tech"


def test_25935_types_and_stable_equipment_ids():
    cameras, unresolved, _ = parse_25935(CSV_25935, "2026-08-11")
    assert not unresolved
    speed = next(cam for cam in cameras if cam.id == "25935-1")
    assert speed.type == "fixed"
    assert speed.speed_limit == 40
    assert speed.city == "桃園市"
    red = next(cam for cam in cameras if cam.id == "25935-15")
    assert red.type == "red_light"
    assert red.speed_limit is None  # 速限 is the literal "x"


def test_176549_type_split_and_slashed_limits():
    cameras, unresolved, _ = parse_176549(CSV_176549, "2026-08-11")
    assert not unresolved
    types = {cam.id: cam for cam in cameras}
    red = next(cam for cam in cameras if "民族一路" in cam.description)
    assert red.type == "red_light"
    assert red.speed_limit == 60
    assert red.bearing == 180.0  # 北向南
    assert red.description == "三民 民族一路與十全一路口"  # 行政區 prepended
    dual = next(cam for cam in cameras if "大中一路" in cam.description)
    assert dual.type == "fixed"  # 闖紅燈兼超速 measures speed
    left_turn = next(cam for cam in cameras if "九如一路口" in cam.description)
    assert left_turn.type == "tech"  # 違左
    assert left_turn.speed_limit is None  # 速限 holds the literal 違左
    slashed = next(cam for cam in cameras if "沿海一路" in cam.description)
    assert slashed.speed_limit == 60  # 60/40 keeps the general limit
    assert len(types) == 4


def test_kaohsiung_tech_family_location_columns_and_types():
    cameras, unresolved, stats = parse_176558(CSV_176558, "2026-08-11")
    assert not unresolved
    assert cameras[0].type == "red_light"  # 闖紅燈 in 取締項目
    assert cameras[0].bearing == 270.0  # 東向西
    assert cameras[0].description == "大寮區市道188號/鳳林二路口(西向)"
    assert stats["176558_type:red_light"] == 1

    cameras, _, _ = parse_176561(CSV_176561, "2026-08-11")  # 設置位置 + stray Column1
    assert cameras[0].type == "red_light"
    assert cameras[0].description == "小港區中山四路/平和東路"

    cameras, _, _ = parse_176555(CSV_176555, "2026-08-11")
    assert cameras[0].type == "tech"  # 車不停讓行人, no 闖紅燈
    assert cameras[0].bearing is None  # 北側 is a device side, not a direction


def test_municipal_missing_columns_fail_loudly():
    parsers = (
        parse_130111,
        parse_135957,
        parse_170673,
        parse_25935,
        parse_176549,
        parse_176555,
        parse_176558,
        parse_176560,
        parse_176561,
        parse_177827,
    )
    for parse in parsers:
        with pytest.raises(SchemaError):
            parse("foo,bar\n1,2\n", "2026-08-11")
