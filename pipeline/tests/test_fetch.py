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
