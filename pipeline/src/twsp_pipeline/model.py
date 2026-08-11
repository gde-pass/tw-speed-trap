"""Normalized camera model shared by every pipeline stage."""

from dataclasses import asdict, dataclass

# "section" cameras are average-speed (區間測速) endpoints; they carry a
# section_id and section_role linking the entry and exit points.
CAMERA_TYPES = ("fixed", "mobile", "red_light", "section", "tech", "other")


@dataclass
class Camera:
    id: str
    lat: float
    lon: float
    type: str
    speed_limit: int | None
    bearing: float | None
    city: str
    description: str
    source: str
    last_seen: str  # ISO date of the pipeline run that last saw this point
    section_id: str | None = None
    section_role: str | None = None  # "start" | "end"

    def to_dict(self) -> dict:
        return asdict(self)


@dataclass
class Unresolved:
    """A source row we could not turn into a camera. Never silently dropped:
    these are counted in the report and written to unresolved.csv."""

    source: str
    reason: str
    raw: dict
