"""Resolve and download dataset resources from data.gov.tw.

Resource URLs churn (dataset 7320 moved to opdadm.moi.gov.tw at some point),
so we never hardcode them: each run asks the dataset API where the CSV
currently lives.
"""

import io
import ssl
import time
import urllib.parse
import zipfile

import certifi
import requests
import requests.adapters

DATASET_API = "https://data.gov.tw/api/v2/rest/dataset/{dataset_id}"
USER_AGENT = "tw-speed-trap-pipeline/0.1 (+https://github.com/gde-pass/tw-speed-trap)"
TIMEOUT_S = 60
RETRIES = 3
MAX_DOWNLOAD_BYTES = 200 * 1024 * 1024
MAX_ZIP_MEMBER_BYTES = 100 * 1024 * 1024


class FetchError(RuntimeError):
    pass


class _RelaxedStrictnessAdapter(requests.adapters.HTTPAdapter):
    """Taiwanese government certificates (GRCA-issued, e.g. tgos.tw) often
    lack the Subject Key Identifier extension, which Python 3.13's default
    VERIFY_X509_STRICT rejects. Keep chain-of-trust verification (against
    the same certifi bundle requests normally uses); drop only the
    format-strictness flag. Resource hosts churn between runs, so this is
    mounted for the whole session rather than a hardcoded host list."""

    def init_poolmanager(self, *args, **kwargs):
        ctx = ssl.create_default_context(cafile=certifi.where())
        ctx.verify_flags &= ~ssl.VERIFY_X509_STRICT
        kwargs["ssl_context"] = ctx
        return super().init_poolmanager(*args, **kwargs)


_session = requests.Session()
_session.mount("https://", _RelaxedStrictnessAdapter())


# Some .gov.tw hosts (tgos.tw in particular) reject unfamiliar user agents /
# cloud-runner traffic with 403; a browser-like identity gets through.
BROWSER_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)


# Hosts that hit a connect timeout this run. Some municipal file hosts
# (data.kcg.gov.tw) silently drop GitHub-runner traffic; without this, six
# datasets on one dead host would each burn RETRIES × 2 × TIMEOUT_S before
# their snapshot fallback kicks in.
_timed_out_hosts: set[str] = set()

# One transient timeout against the dataset API itself must never write off
# every remaining dataset (and, in dataset-watch, must never make all used
# datasets read as delisted).
_NEVER_BLACKLIST = {"data.gov.tw", "www.data.gov.tw"}


def _get(url: str) -> requests.Response:
    host = urllib.parse.urlsplit(url).hostname or ""
    if host in _timed_out_hosts:
        raise FetchError(f"skipping {url}: {host} already timed out this run")
    last_error: Exception | None = None
    for attempt in range(RETRIES):
        for headers in (
            {"User-Agent": USER_AGENT},
            {"User-Agent": BROWSER_UA, "Referer": "https://data.gov.tw/"},
        ):
            try:
                resp = _session.get(url, headers=headers, timeout=TIMEOUT_S)
                resp.raise_for_status()
                return resp
            except requests.exceptions.ConnectTimeout as e:
                if host not in _NEVER_BLACKLIST:
                    _timed_out_hosts.add(host)
                raise FetchError(f"failed to fetch {url}: {e}") from e
            except Exception as e:  # noqa: BLE001 - retry on any transport error
                last_error = e
        if attempt < RETRIES - 1:
            time.sleep(2**attempt)
    raise FetchError(f"failed to fetch {url}: {last_error}")


def resolve_csv_url(dataset_id: int) -> str:
    """Ask the data.gov.tw v2 API for the current CSV resource URL."""
    data = _get(DATASET_API.format(dataset_id=dataset_id)).json()
    if not data.get("success"):
        raise FetchError(f"dataset API returned success=false for {dataset_id}")
    try:
        distributions = data["result"]["distribution"]
    except (KeyError, TypeError) as e:
        # A shape change must stay a FetchError so the snapshot fallback applies.
        raise FetchError(f"dataset API response shape changed for {dataset_id}: {e!r}") from e
    for dist in distributions:
        if dist.get("resourceFormat", "").upper() == "CSV" and dist.get("resourceDownloadUrl"):
            return dist["resourceDownloadUrl"]
    raise FetchError(f"no CSV resource found for dataset {dataset_id}")


def download(url: str) -> bytes:
    content = _get(url).content
    if len(content) > MAX_DOWNLOAD_BYTES:
        raise FetchError(f"{url}: response larger than {MAX_DOWNLOAD_BYTES} bytes")
    return content


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
        if info.file_size > MAX_ZIP_MEMBER_BYTES:
            raise FetchError(f"ZIP member {name} decompresses to {info.file_size} bytes; refusing")
        payloads.append(zf.read(info))
    if not payloads:
        raise FetchError("ZIP archive contained no usable CSV member")
    return payloads
