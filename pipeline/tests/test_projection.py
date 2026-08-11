import pytest
from twsp_pipeline.projection import CoordinateError, normalize_coords


def test_wgs84_passthrough():
    lat, lon = normalize_coords("24.147", "120.673")
    assert lat == pytest.approx(24.147)
    assert lon == pytest.approx(120.673)


def test_twd97_central_meridian():
    # TWD97 TM2 false easting is 250 000 m on the 121°E meridian, so easting
    # exactly 250 000 must land on longitude 121 regardless of northing.
    lat, lon = normalize_coords("2655000", "250000")
    assert lon == pytest.approx(121.0, abs=1e-6)
    assert 23.5 < lat < 24.5


def test_twd97_axis_order_insensitive():
    # Some sources put northing in the longitude column and vice versa.
    a = normalize_coords("2655000", "250000")
    b = normalize_coords("250000", "2655000")
    assert a == pytest.approx(b)


def test_swapped_lat_lon_fixed():
    lat, lon = normalize_coords("120.673", "24.147")
    assert lat == pytest.approx(24.147)
    assert lon == pytest.approx(120.673)


def test_kinmen_in_range():
    lat, lon = normalize_coords("24.458", "118.431")
    assert lon == pytest.approx(118.431)


@pytest.mark.parametrize(("lat", "lon"), [("", ""), ("abc", "120.5"), ("51.5", "-0.1"), ("0", "0")])
def test_unusable_coordinates_raise(lat, lon):
    with pytest.raises(CoordinateError):
        normalize_coords(lat, lon)
