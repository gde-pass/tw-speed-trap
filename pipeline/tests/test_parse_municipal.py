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
    parse_156415,
    parse_172905,
    parse_172940,
    parse_178085,
    parse_178086,
    parse_178159,
    parse_25935,
    parse_178168,
    parse_172174,
    parse_178144,
    parse_159972,
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

# County-standard template since 2026-08: full-width parentheses on the
# location column, 縣市 present, no 拍攝方向/速限, 區間 rows flagged in 科技執法種類.
CSV_135957 = """編號,縣市,縣市代碼,行政區,科技執法種類,取締項目,設置地點（路口或路段）,座標緯度,座標經度,啟用時間,啟用民國日期,啟用日期,備註
1,臺北市,63000,大同區,路口多功能執法,闖紅燈、不停讓行人、不依規定轉彎、不依標誌標線號誌指示行駛,鄭州路與西寧北路口,25.05049695,121.5081673,111年1月3日,111-01-03,2022-01-03,
13,臺北市,63000,大同區,違規停車科技執法,違規停車,重慶北路三段278號前　,25.072913,121.513304,110年7月1日,110-07-01,2021-07-01,
92,臺北市,63000,士林區、中山區,區間平均速率科技執法,區間測速及跨越雙白線 ,自強隧道,25.090937,121.549277,108年9月1日,108-09-01,2019-09-01,109年4月停用；111年2月21日重啟
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


def test_135957_county_template_and_section_exclusion():
    cameras, unresolved, stats = parse_135957(CSV_135957, "2026-09-03")
    assert not unresolved
    assert stats["135957_sections_excluded"] == 1  # 區間平均速率科技執法 tunnel row
    assert len(cameras) == 2
    intersection = next(cam for cam in cameras if cam.description == "鄭州路與西寧北路口")
    assert intersection.type == "red_light"
    assert intersection.city == "臺北市"
    assert intersection.bearing is None and intersection.speed_limit is None  # no such columns
    assert 25.0 < intersection.lat < 25.1 and 121.5 < intersection.lon < 121.6
    parking = next(cam for cam in cameras if cam.description == "重慶北路三段278號前")  # U+3000 stripped
    assert parking.type == "tech"


def test_135957_accepts_ascii_parentheses_on_location_column():
    ascii_header = CSV_135957.replace("設置地點（路口或路段）", "設置地點(路口或路段)", 1)
    cameras, _, _ = parse_135957(ascii_header, "2026-09-03")
    assert {cam.description for cam in cameras} == {"鄭州路與西寧北路口", "重慶北路三段278號前"}


def test_135957_legacy_xy_shape_fails_loudly():
    legacy = "編號,名稱,取締路段,座標-X,座標-Y,啟用日期,取締項目\n5,路口多功能,鄭州路與西寧北路口,121.508087,25.050537,111年1月3日,闖紅燈\n"
    with pytest.raises(SchemaError):
        parse_135957(legacy, "2026-09-03")


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
        parse_178168,
        parse_172174,
        parse_178144,
        parse_159972,
    )
    for parse in parsers:
        with pytest.raises(SchemaError):
            parse("foo,bar\n1,2\n", "2026-08-11")


CSV_172905 = """設備編號,縣市,行政區,科技執法種類,取締項目,設置地點,座標緯度,座標經度,拍攝方向,速限,轄區分局,備註
3,彰化縣,彰化市,路口多功能執法,超速、闖紅燈,金馬路2段與彰新路1段路口,24.092823,120.538186,北向南,70,彰化分局,
5,彰化縣,彰化市,路口多功能執法,闖紅燈、不停讓行人,光復路與和平路口,24.080356,120.540402,南北雙向,50,彰化分局,
"""

# Keelung uses the suffixed column names and lists one gantry per direction.
CSV_178159 = """設備編號,縣市,行政區,科技執法種類,"取締項目(以""、""分隔)",設置地點(路口或路段),座標緯度,座標經度,拍攝方向,速限,轄區分局,備註
1,基隆市,仁愛區,路口多功能執法,禁行車種,基隆東岸高架橋,25.1308105,121.7430319,北往南,-,交通隊,
2,基隆市,仁愛區,路口多功能執法,禁行車種,基隆東岸高架橋,25.1308105,121.7430319,南往北,-,交通隊,
"""

# Yunlin's English header is a veneer: the first data row repeats the real
# header in Chinese, and Remark holds the police branch.
CSV_178085 = """project,location,direction,speed,ban,Latitude,Longitude,Remark
A00,設置地點,拍攝方向,速限,取締項目,經緯度,經緯度,備註
A01,雲林縣北港鎮台19線公路與145縣道路口,北向南,50,超速、闖紅燈,23.583764,120.299399,北港分局
A02,雲林縣元長鄉160縣道15k+650m處,東西雙向,60,超速,23.655033,120.288958,虎尾分局
A12,雲林縣虎尾鎮林森路一段與公安路口,南北雙向,50,闖紅燈 、未禮讓行人,23.70914,120.433403,虎尾分局
"""


def test_county_standard_parser_variants():
    cameras, unresolved, _ = parse_172905(CSV_172905, "2026-08-11")
    assert not unresolved
    dual = next(cam for cam in cameras if "金馬路" in cam.description)
    assert dual.type == "fixed"  # 超速、闖紅燈 measures speed -> dedupes vs 7320
    assert dual.speed_limit == 70
    assert dual.city == "彰化縣"
    assert dual.description == "彰化市 金馬路2段與彰新路1段路口"
    red = next(cam for cam in cameras if "光復路" in cam.description)
    assert red.type == "red_light"
    assert red.bearing is None  # 南北雙向

    cameras, unresolved, _ = parse_178159(CSV_178159, "2026-08-11")
    assert not unresolved
    assert [cam.type for cam in cameras] == ["tech", "tech"]  # 禁行車種
    assert cameras[0].speed_limit is None  # 速限 is "-"
    assert {cameras[0].bearing, cameras[1].bearing} == {180.0, 0.0}
    assert cameras[0].id != cameras[1].id  # per-direction gantries stay distinct


# 澎湖 172940 renamed its columns in 2026-08: 設置地點(路口或路段) became plain
# 設置地點 (and likewise for 取締項目), matching the 彰化 shape. Header copied
# from the live download that broke the 2026-08-12 data-update run.
CSV_172940_RENAMED = """設備編號,型式,縣市,行政區,科技執法種類,取締項目,設置地點,座標緯度,座標經度,拍攝方向,速限,轄區分局
1,雷達,澎湖縣,馬公市,路口多功能執法,超速、闖紅燈,民族路與中正路口,23.565897,119.566417,東西雙向,50,馬公分局
"""


def test_county_parser_accepts_renamed_columns():
    cameras, unresolved, _ = parse_172940(CSV_172940_RENAMED, "2026-08-12")
    assert not unresolved
    assert len(cameras) == 1
    assert cameras[0].type == "fixed"  # 超速 present
    assert cameras[0].description == "馬公市 民族路與中正路口"
    assert cameras[0].speed_limit == 50
    # the old suffixed header keeps parsing too
    old, unresolved, _ = parse_178159(CSV_178159, "2026-08-11")
    assert not unresolved and len(old) == 2


def test_yunlin_skips_embedded_chinese_header():
    cameras, unresolved, stats = parse_178085(CSV_178085, "2026-08-11")
    assert not unresolved
    assert stats["178085_skipped_zh_header"] == 1
    assert len(cameras) == 3
    by_desc = {cam.description: cam for cam in cameras}
    assert by_desc["雲林縣北港鎮台19線公路與145縣道路口"].type == "fixed"
    assert by_desc["雲林縣元長鄉160縣道15k+650m處"].speed_limit == 60
    assert by_desc["雲林縣虎尾鎮林森路一段與公安路口"].type == "red_light"
    assert all(cam.city == "雲林縣" for cam in cameras)


def test_county_missing_columns_fail_loudly():
    for parse in (parse_172905, parse_178159, parse_156415, parse_172940, parse_178085, parse_178086):
        with pytest.raises(SchemaError):
            parse("foo,bar\n1,2\n", "2026-08-11")


# Taoyuan: county-standard columns, underscore-suffixed location, 速限 '-' and
# 拍攝方向 雙向 on most rows.
CSV_178168 = """設備編號,型式,縣市,行政區,科技執法種類,取締項目,設置區域描述,設置地點_路口或路段,座標緯度,座標經度,拍攝方向,速限,管轄單位,備註
1,科技執法設備,桃園市,桃園區,租賃式多功能科技執法,闖紅燈、未依標誌標線號誌行駛,,三民路一段與自強路口,25.001185,121.317046,雙向,-,桃園分局,
57,科技執法設備,桃園市,蘆竹區,路口多功能測速執法,超速、闖紅燈、未依標誌標線號誌行駛,,蘆竹區濱海路一段與海湖北路,25.11712,121.26382,雙向,70,蘆竹分局,
3,科技執法設備,桃園市,桃園區,租賃式多功能科技執法,未停讓行人,,三民路三段與中正路口,24.998759,121.308498,雙向,-,桃園分局,
7,科技執法設備,桃園市,桃園區,路口多功能測速執法,超速、闖紅燈、未依標誌標線號誌行駛,,春日路與民光東路口,25.006225,121.312167,往北,50,桃園分局,
"""

# Miaoli: Keelung-style suffixed headers plus trailing unnamed columns; rows
# are shorter than the header. 80（40） is a car/scooter split limit.
CSV_172174 = """設備編號,型式,縣市,行政區,設置區域描述,設置地點(路口或路段),"取締項目(以""、""分隔)",座標緯度,座標經度,拍攝方向,速限,管轄單位,備註,,,,,
1,雷達,苗栗縣,苗栗市,,新川里柑園5號前處,測速,24.578798,120.803745,東向西,60,苗栗分局,,
9,非線圈數位-雷達,苗栗縣,銅鑼鄉,,縣道128線與苗27線口處,闖紅燈,24.4988,120.80876,北向南,60,苗栗分局,,
5,非線圈數位-雷達,苗栗縣,苗栗市,,英才路與文發路口,闖紅燈、測速,24.590336,120.815263,北向南,50,苗栗分局,,
24,非線圈數位-雷達,苗栗縣,竹南鎮,,台61線91公里快（側）車道與博愛街口處,闖紅燈,24.693142,120.859948,南向北,80（40）,竹南分局,,
"""

# Hsinchu City: three columns, the location header carries an embedded space.
CSV_178144 = """地 點,經度,緯度
新竹市北區經國路二段、中正路口,120.96447,24.811973
新竹市東區經國路一段、公道五路口,120.98523,24.815077
"""

# Pingtung: 7320's English headers glossed in parentheses; 區間測速 rows are
# flagged in direct and describe a km span rather than a point.
CSV_159972 = """CityName(設置縣市),RegionName(設置市區鄉鎮),Address(設置地址),DeptNm(管轄警局),BranchNm(管轄分局),Longitude(經度),Latitude(緯度),direct(拍攝方向),limit(速限)
屏東縣,屏東市,屏東市逢甲路與復興路口,屏東縣政府縣警察局,屏東分局,120.489191,22.671134,單向,50
屏東縣,里港鄉,里港鄉台3線與鐵店路口,屏東縣政府縣警察局,里港分局,120.497438,22.777618,單向,50
屏東縣,佳冬鄉,台1線戰備道429.5-433K,屏東縣政府縣警察局,枋寮分局,120.563046,22.442275,南北雙向(區間測速),70
"""


def test_178168_types_bearings_and_area_prefix():
    cameras, unresolved, stats = parse_178168(CSV_178168, "2026-09-04")
    assert not unresolved
    by_desc = {cam.description: cam for cam in cameras}
    red = by_desc["桃園區 三民路一段與自強路口"]  # 行政區 prefixed when absent from the place
    assert red.type == "red_light" and red.bearing is None and red.speed_limit is None  # 雙向, '-'
    assert red.city == "桃園市"
    speed = by_desc["蘆竹區濱海路一段與海湖北路"]  # place already names the district
    assert speed.type == "fixed" and speed.speed_limit == 70  # 超速 keeps fixed for 25935/7320 twins
    assert by_desc["桃園區 三民路三段與中正路口"].type == "tech"  # 未停讓行人
    north = by_desc["桃園區 春日路與民光東路口"]
    assert north.bearing == 0.0 and north.speed_limit == 50  # 往北
    assert stats["178168_type:fixed"] == 2


def test_172174_suffixed_headers_and_split_limit():
    cameras, unresolved, stats = parse_172174(CSV_172174, "2026-09-04")
    assert not unresolved
    assert len(cameras) == 4
    by_desc = {cam.description: cam for cam in cameras}
    radar = by_desc["苗栗市 新川里柑園5號前處"]
    assert radar.type == "fixed" and radar.bearing == 270.0 and radar.speed_limit == 60  # 東向西
    assert radar.city == "苗栗縣"
    assert by_desc["銅鑼鄉 縣道128線與苗27線口處"].type == "red_light"
    assert by_desc["苗栗市 英才路與文發路口"].type == "fixed"  # 闖紅燈、測速 measures speed
    split = by_desc["竹南鎮 台61線91公里快（側）車道與博愛街口處"]
    assert split.speed_limit is None and split.bearing == 0.0  # 80（40）, 南向北
    assert stats["172174_type:fixed"] == 2 and stats["172174_type:red_light"] == 2


def test_178144_three_column_shape():
    cameras, unresolved, stats = parse_178144(CSV_178144, "2026-09-04")
    assert not unresolved
    assert [cam.description for cam in cameras] == ["新竹市北區經國路二段、中正路口", "新竹市東區經國路一段、公道五路口"]
    assert all(cam.type == "tech" and cam.city == "新竹市" for cam in cameras)
    assert all(cam.bearing is None and cam.speed_limit is None for cam in cameras)
    assert 24.8 < cameras[0].lat < 24.9 and 120.9 < cameras[0].lon < 121.0
    assert stats["178144_type:tech"] == 2
    # the header may lose its embedded space upstream
    cameras, _, _ = parse_178144(CSV_178144.replace("地 點", "地點", 1), "2026-09-04")
    assert len(cameras) == 2


def test_159972_glossed_headers_and_section_exclusion():
    cameras, unresolved, stats = parse_159972(CSV_159972, "2026-09-04")
    assert not unresolved
    assert stats["159972_sections_excluded"] == 1  # 台1線戰備道 區間測速 span
    assert [cam.description for cam in cameras] == ["屏東市逢甲路與復興路口", "里港鄉台3線與鐵店路口"]
    assert all(cam.type == "tech" and cam.city == "屏東縣" for cam in cameras)
    assert all(cam.bearing is None for cam in cameras)  # 單向 names no compass direction
    assert cameras[0].speed_limit == 50
    assert 22.6 < cameras[0].lat < 22.7 and 120.4 < cameras[0].lon < 120.5
