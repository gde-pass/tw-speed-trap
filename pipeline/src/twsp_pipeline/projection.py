"""Coordinate handling: detect TWD97 (EPSG:3826) by magnitude, reproject to
WGS84, and sanity-check that the result lands in Taiwan."""

from pyproj import Transformer

# always_xy: transform(easting, northing) -> (lon, lat)
_TWD97_TO_WGS84 = Transformer.from_crs("EPSG:3826", "EPSG:4326", always_xy=True)

# Bounding box including Kinmen (~118.2E) and Matsu (~26.2N).
LAT_RANGE = (21.5, 26.5)
LON_RANGE = (117.5, 122.5)


class CoordinateError(ValueError):
    pass


def _in_taiwan(lat: float, lon: float) -> bool:
    return LAT_RANGE[0] <= lat <= LAT_RANGE[1] and LON_RANGE[0] <= lon <= LON_RANGE[1]


def normalize_coords(lat_raw: str | float, lon_raw: str | float) -> tuple[float, float]:
    """Return (lat, lon) in WGS84. Raises CoordinateError if unusable."""
    try:
        lat = float(str(lat_raw).strip())
        lon = float(str(lon_raw).strip())
    except (TypeError, ValueError) as e:
        raise CoordinateError(f"non-numeric coordinates: {lat_raw!r}, {lon_raw!r}") from e

    # TWD97 TM2 coordinates are metres: easting ~40k-460k, northing ~2.3M-2.9M.
    # WGS84 degrees never exceed 180, so magnitude alone is a reliable test.
    if abs(lat) > 1000 or abs(lon) > 1000:
        easting, northing = sorted((lat, lon))
        lon, lat = _TWD97_TO_WGS84.transform(easting, northing)

    if not _in_taiwan(lat, lon):
        if _in_taiwan(lon, lat):  # lat/lon swapped at the source
            lat, lon = lon, lat
        else:
            raise CoordinateError(f"coordinates outside Taiwan: {lat_raw!r}, {lon_raw!r}")
    return lat, lon
