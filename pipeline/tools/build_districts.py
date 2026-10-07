"""One-off converter: the MOI township boundary shapefile (data.gov.tw 7441,
鄉鎮市區界線(TWD97經緯度), ~13 MB zip from tgos.tw) → pipeline/data/districts.json,
a compact even-odd ring set per township that the district sanity check reads
(twsp_pipeline/districts.py). Pure Python on purpose: no GDAL/pyshp dependency
for a file regenerated once a year.

    uv run --project pipeline python pipeline/tools/build_districts.py <zip> [tolerance_deg]

Rings are Douglas-Peucker simplified (default 0.0005° ≈ 50 m); the check
tolerates that with a 1 km margin, so borders need not be exact. Coordinates
are rounded to 5 decimals (~1 m)."""

import json
import struct
import sys
import zipfile
from pathlib import Path


def read_dbf(data: bytes) -> list[dict[str, str]]:
    nrec, hlen, rlen = struct.unpack("<IHH", data[4:12])
    fields = []
    off = 32
    while data[off] != 0x0D:
        name = data[off : off + 11].split(b"\0")[0].decode("ascii")
        fields.append((name, data[off + 16]))
        off += 32
    records = []
    for i in range(nrec):
        rec = data[hlen + i * rlen : hlen + (i + 1) * rlen]
        pos = 1
        row = {}
        for name, flen in fields:
            row[name] = rec[pos : pos + flen].decode("utf-8", "replace").strip()
            pos += flen
        records.append(row)
    return records


def read_shp_polygons(data: bytes) -> list[list[list[tuple[float, float]]]]:
    """Rings per record (shape type 5 Polygon; ring orientation is ignored —
    the consumer uses the even-odd rule across all rings of a record)."""
    shapes = []
    off = 100
    while off < len(data):
        _, clen = struct.unpack(">ii", data[off : off + 8])
        body = data[off + 8 : off + 8 + clen * 2]
        (shape_type,) = struct.unpack("<i", body[:4])
        rings: list[list[tuple[float, float]]] = []
        if shape_type == 5:
            nparts, npts = struct.unpack("<ii", body[36:44])
            parts = struct.unpack(f"<{nparts}i", body[44 : 44 + 4 * nparts])
            pts_off = 44 + 4 * nparts
            coords = struct.unpack(f"<{2 * npts}d", body[pts_off : pts_off + 16 * npts])
            points = [(coords[2 * i], coords[2 * i + 1]) for i in range(npts)]
            bounds = list(parts) + [npts]
            rings = [points[bounds[i] : bounds[i + 1]] for i in range(nparts)]
        shapes.append(rings)
        off += 8 + clen * 2
    return shapes


def simplify(ring: list[tuple[float, float]], tolerance: float) -> list[tuple[float, float]]:
    """Iterative Douglas-Peucker (rings here run to 100k points)."""
    if len(ring) < 5:
        return ring
    keep = [False] * len(ring)
    keep[0] = keep[-1] = True
    stack = [(0, len(ring) - 1)]
    while stack:
        start, end = stack.pop()
        if end <= start + 1:
            continue
        (x1, y1), (x2, y2) = ring[start], ring[end]
        dx, dy = x2 - x1, y2 - y1
        norm = (dx * dx + dy * dy) ** 0.5
        best, best_d = -1, 0.0
        for i in range(start + 1, end):
            x, y = ring[i]
            d = abs(dy * x - dx * y + x2 * y1 - y2 * x1) / norm if norm else ((x - x1) ** 2 + (y - y1) ** 2) ** 0.5
            if d > best_d:
                best, best_d = i, d
        if best_d > tolerance:
            keep[best] = True
            stack.append((start, best))
            stack.append((best, end))
    out = [p for p, k in zip(ring, keep) if k]
    return out if len(out) >= 4 else ring[:: max(1, len(ring) // 4)] + [ring[-1]]


def main() -> int:
    zip_path = Path(sys.argv[1])
    tolerance = float(sys.argv[2]) if len(sys.argv) > 2 else 0.0005
    out_path = Path(__file__).resolve().parents[1] / "data" / "districts.json"
    with zipfile.ZipFile(zip_path) as zf:
        stem = next(n[:-4] for n in zf.namelist() if n.startswith("TOWN_MOI") and n.endswith(".shp"))
        attrs = read_dbf(zf.read(stem + ".dbf"))
        shapes = read_shp_polygons(zf.read(stem + ".shp"))
    assert len(attrs) == len(shapes), (len(attrs), len(shapes))
    features = []
    before = after = 0
    for row, rings in zip(attrs, shapes):
        simplified = []
        for ring in rings:
            before += len(ring)
            s = simplify(ring, tolerance)
            after += len(s)
            simplified.append([[round(x, 5), round(y, 5)] for x, y in s])
        features.append({"county": row["COUNTYNAME"], "town": row["TOWNNAME"], "code": row["TOWNCODE"], "rings": simplified})
    payload = {
        "source": "data.gov.tw/dataset/7441 鄉鎮市區界線(TWD97經緯度), 內政部國土測繪中心, edition " + stem.rsplit("_", 1)[-1],
        "license": "政府資料開放授權條款-第1版",
        "tolerance_deg": tolerance,
        "note": "rings are lon/lat; apply the even-odd rule across all rings of a feature",
        "features": features,
    }
    out_path.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"{len(features)} townships, {before} → {after} vertices, {out_path.stat().st_size / 1e6:.2f} MB → {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
