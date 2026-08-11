from twsp_pipeline.decode import decode_bytes


def test_utf8_bom_stripped():
    assert decode_bytes("臺中市".encode("utf-8-sig")) == "臺中市"


def test_plain_utf8():
    assert decode_bytes("測速執法,速限".encode()) == "測速執法,速限"


def test_big5_detected():
    text = "縣市,設置地點\n臺中市,臺灣大道三段"
    assert decode_bytes(text.encode("big5")) == text


def test_cp950_extension_chars():
    # 「碁」 exists in CP950; exercises the cp950-before-big5 ordering.
    text = "碁盤式道路"
    assert decode_bytes(text.encode("cp950")) == text
