"""The shipped Tainan geocode table must stay internally sound: unique places,
coordinates inside 臺南市, and every entry carrying its provenance."""

from pathlib import Path

from twsp_pipeline.districts import Districts
from twsp_pipeline.parse import GEOCODES_53645, load_geocodes


def test_tainan_geocode_table_is_sound():
    table = load_geocodes(GEOCODES_53645)
    assert len(table) >= 170
    districts = Districts.load()
    tainan = districts.by_county["臺南市"]
    for place, (lat, lon) in table.items():
        la, lo = float(lat), float(lon)
        here = districts.locate(la, lo)
        assert here is not None and here.county == "臺南市", (place, lat, lon, here)


def test_tainan_geocode_entries_name_their_method():
    import yaml

    payload = yaml.safe_load(Path(GEOCODES_53645).read_text(encoding="utf-8"))
    methods = {entry["method"] for entry in payload["geocodes"]}
    assert methods <= {"osm-junction", "7320-twin", "manual"}
    assert all(entry.get("note") for entry in payload["geocodes"])
