import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
import requests
from twsp_pipeline import fetch


@pytest.fixture(autouse=True)
def _clean_host_cache(monkeypatch):
    monkeypatch.setattr(fetch, "_timed_out_hosts", set())


def test_connect_timeout_fails_fast_for_the_whole_host(monkeypatch):
    calls = []

    def timing_out_get(url, **kwargs):
        calls.append(url)
        raise requests.exceptions.ConnectTimeout(f"connect to {url} timed out")

    monkeypatch.setattr(fetch._session, "get", timing_out_get)

    with pytest.raises(fetch.FetchError):
        fetch._get("https://data.kcg.gov.tw/File/DirectDownload/aaa")
    assert len(calls) == 1  # no retry loop after a 60 s connect timeout

    with pytest.raises(fetch.FetchError, match="already timed out"):
        fetch._get("https://data.kcg.gov.tw/File/DirectDownload/bbb")
    assert len(calls) == 1  # second dataset on the dead host never hits the network

    # Other hosts are unaffected by the kcg timeout.
    with pytest.raises(fetch.FetchError):
        fetch._get("https://data.gov.tw/api/v2/rest/dataset/7320")
    assert len(calls) == 2


class _Handler(BaseHTTPRequestHandler):
    """/fast answers at once; /drip promises 100 kB and sends a byte at a time."""

    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path == "/fast":
            body = b"id,name\n1,ok\n"
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(200)
        self.send_header("Content-Length", "100000")
        self.end_headers()
        try:
            for _ in range(100000):
                self.wfile.write(b"x")
                self.wfile.flush()
                time.sleep(0.02)
        except (BrokenPipeError, ConnectionResetError):
            pass


@pytest.fixture
def local_server():
    server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{server.server_port}"
    server.shutdown()


def test_ssl_context_bundles_the_twca_intermediate():
    subjects = [dict(rdn[0] for rdn in cert["subject"]) for cert in fetch._ssl_context().get_ca_certs()]
    names = {s.get("commonName") for s in subjects}
    assert "TWCA Secure SSL Certification Authority" in names  # bundled: ws.kinmen.gov.tw omits it
    assert "TWCA Global Root CA" in names  # its issuer, from certifi — the chain still ends at a public root


def test_fast_response_returns_body(local_server):
    assert fetch._get(f"{local_server}/fast") == b"id,name\n1,ok\n"


def test_drip_feed_hits_wall_clock_deadline_and_writes_host_off(local_server, monkeypatch):
    monkeypatch.setattr(fetch, "DEADLINE_S", 0.3)
    started = time.monotonic()
    with pytest.raises(fetch.FetchError, match="wall clock"):
        fetch._get(f"{local_server}/drip")
    assert time.monotonic() - started < 5  # one attempt, not RETRIES x 2 identities
    with pytest.raises(fetch.FetchError, match="already timed out"):
        fetch._get(f"{local_server}/drip-again")


def test_size_cap_stops_the_download_early(local_server, monkeypatch):
    monkeypatch.setattr(fetch, "MAX_DOWNLOAD_BYTES", 8)
    with pytest.raises(fetch.FetchError, match="larger than 8 bytes"):
        fetch._get(f"{local_server}/fast")
