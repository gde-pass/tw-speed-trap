"""Resolve and download dataset resources from data.gov.tw.

Resource URLs churn (dataset 7320 moved to opdadm.moi.gov.tw at some point),
so we never hardcode them: each run asks the dataset API where the CSV
currently lives.
"""

import io
import ssl
import time
import zipfile

import requests
import requests.adapters

DATASET_API = "https://data.gov.tw/api/v2/rest/dataset/{dataset_id}"
USER_AGENT = "tw-speed-trap-pipeline/0.1 (+https://github.com/gde-pass/tw-speed-trap)"
TIMEOUT_S = 60
RETRIES = 3


class FetchError(RuntimeError):
    pass


class _RelaxedStrictnessAdapter(requests.adapters.HTTPAdapter):
    """Taiwanese government certificates (GRCA-issued, e.g. tgos.tw) often
    lack the Subject Key Identifier extension, which Python 3.13's default
    VERIFY_X509_STRICT rejects. Keep chain-of-trust verification; drop only
    the format-strictness flag."""

    def init_poolmanager(self, *args, **kwargs):
        ctx = ssl.create_default_context()
        ctx.verify_flags &= ~ssl.VERIFY_X509_STRICT
        kwargs["ssl_context"] = ctx
        return super().init_poolmanager(*args, **kwargs)


_session = requests.Session()
_session.mount("https://", _RelaxedStrictnessAdapter())


def _get(url: str) -> requests.Response:
    last_error: Exception | None = None
    for attempt in range(RETRIES):
        try:
            resp = _session.get(url, headers={"User-Agent": USER_AGENT}, timeout=TIMEOUT_S)
            resp.raise_for_status()
            return resp
        except Exception as e:  # noqa: BLE001 - retry on any transport error
            last_error = e
            time.sleep(2**attempt)
    raise FetchError(f"failed to fetch {url}: {last_error}")


def resolve_csv_url(dataset_id: int) -> str:
    """Ask the data.gov.tw v2 API for the current CSV resource URL."""
    data = _get(DATASET_API.format(dataset_id=dataset_id)).json()
    if not data.get("success"):
        raise FetchError(f"dataset API returned success=false for {dataset_id}")
    for dist in data["result"]["distribution"]:
        if dist.get("resourceFormat", "").upper() == "CSV" and dist.get("resourceDownloadUrl"):
            return dist["resourceDownloadUrl"]
    raise FetchError(f"no CSV resource found for dataset {dataset_id}")


def download(url: str) -> bytes:
    return _get(url).content


def extract_csv_payloads(data: bytes) -> list[bytes]:
    """Return raw CSV bytes. Transparently unwraps ZIP archives, tolerating
    Big5/CP950-encoded member filenames (seen on tgos.tw archives)."""
    if not data.startswith(b"PK\x03\x04"):
        return [data]
    payloads = []
    zf = zipfile.ZipFile(io.BytesIO(data), metadata_encoding="cp437")
    for info in zf.infolist():
        name = info.filename
        if info.flag_bits & 0x800 == 0:  # not UTF-8 flagged: try CP950
            try:
                name = info.filename.encode("cp437").decode("cp950")
            except (UnicodeEncodeError, UnicodeDecodeError):
                pass
        if not name.lower().endswith(".csv"):
            continue
        if name.lower().rsplit("/", 1)[-1] == "manifest.csv":  # tgos.tw metadata file
            continue
        payloads.append(zf.read(info))
    if not payloads:
        raise FetchError("ZIP archive contained no usable CSV member")
    return payloads
