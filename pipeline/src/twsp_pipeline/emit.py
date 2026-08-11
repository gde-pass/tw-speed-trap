"""Artifact writers: GeoJSON (committed, human-readable), SQLite (shipped to
the app), manifest.json (drives the app's self-update), unresolved.csv.

Outputs are deterministic for identical input data: features and rows are
sorted by id, and the content hash excludes last_seen so that a run that
changes nothing produces the same hash as the previous run.
"""

import csv
import hashlib
import json
import sqlite3
from pathlib import Path

from .model import Camera, Unresolved

SCHEMA_VERSION = 1
DB_DOWNLOAD_URL = "https://github.com/gde-pass/tw-speed-trap/releases/download/data/cameras.db"


def content_hash(cameras: list[Camera]) -> str:
    """Hash of the actual camera data, independent of run date."""
    canonical = [
        {k: v for k, v in cam.to_dict().items() if k != "last_seen"}
        for cam in sorted(cameras, key=lambda c: c.id)
    ]
    payload = json.dumps(canonical, ensure_ascii=False, sort_keys=True).encode()
    return hashlib.sha256(payload).hexdigest()


def file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def write_geojson(cameras: list[Camera], path: Path) -> None:
    features = []
    for cam in sorted(cameras, key=lambda c: c.id):
        props = {k: v for k, v in cam.to_dict().items() if k not in ("lat", "lon")}
        features.append(
            {
                "type": "Feature",
                "geometry": {"type": "Point", "coordinates": [round(cam.lon, 6), round(cam.lat, 6)]},
                "properties": props,
            }
        )
    collection = {"type": "FeatureCollection", "features": features}
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(collection, f, ensure_ascii=False, indent=1, sort_keys=True)
        f.write("\n")


def write_sqlite(cameras: list[Camera], path: Path, data_version: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.unlink(missing_ok=True)
    conn = sqlite3.connect(path)
    try:
        conn.executescript(
            """
            CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE cameras (
                id TEXT PRIMARY KEY,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                type TEXT NOT NULL,
                speed_limit INTEGER,
                bearing REAL,
                city TEXT NOT NULL,
                description TEXT NOT NULL,
                source TEXT NOT NULL,
                last_seen TEXT NOT NULL,
                section_id TEXT,
                section_role TEXT
            );
            CREATE TABLE sections (
                id TEXT PRIMARY KEY,
                speed_limit INTEGER NOT NULL,
                length_m REAL NOT NULL
            );
            CREATE INDEX idx_cameras_latlon ON cameras(lat, lon);
            """
        )
        conn.executemany(
            """
            INSERT INTO cameras (id, lat, lon, type, speed_limit, bearing, city,
                                 description, source, last_seen, section_id, section_role)
            VALUES (:id, :lat, :lon, :type, :speed_limit, :bearing, :city,
                    :description, :source, :last_seen, :section_id, :section_role)
            """,
            [cam.to_dict() for cam in sorted(cameras, key=lambda c: c.id)],
        )
        meta = {
            "schema_version": str(SCHEMA_VERSION),
            "data_version": data_version,
            "content_hash": content_hash(cameras),
            "count": str(len(cameras)),
        }
        conn.executemany("INSERT INTO meta (key, value) VALUES (?, ?)", sorted(meta.items()))
        conn.commit()
        conn.execute("VACUUM")
    finally:
        conn.close()


def write_manifest(cameras: list[Camera], db_path: Path, manifest_path: Path, data_version: str) -> dict:
    manifest = {
        "schema_version": SCHEMA_VERSION,
        "data_version": data_version,
        "count": len(cameras),
        "sha256": file_sha256(db_path),
        "content_hash": content_hash(cameras),
        "url": DB_DOWNLOAD_URL,
    }
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    return manifest


def write_unresolved(unresolved: list[Unresolved], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["source", "reason", "raw"])
        for item in unresolved:
            writer.writerow([item.source, item.reason, json.dumps(item.raw, ensure_ascii=False)])
