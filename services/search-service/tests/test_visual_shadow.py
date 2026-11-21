import httpx

from scripts import verify_visual_shadow


def test_rabbit_snapshot_waits_for_first_statistics_sample(monkeypatch):
    monkeypatch.setenv("HAI_SHADOW_RABBIT_URL", "http://127.0.0.1:15672")
    monkeypatch.setenv("HAI_SHADOW_RABBIT_USER", "monitor")
    monkeypatch.setenv("HAI_SHADOW_RABBIT_PASSWORD", "fixture")
    waits = []
    monkeypatch.setattr(verify_visual_shadow, "sleep", waits.append)

    requests = []
    counters = {"messages": 0, "messages_ready": 0, "messages_unacknowledged": 0, "consumers": 1}

    def management_response(request):
        assert request.method == "GET"
        assert request.url.raw_path.startswith(b"/api/queues/%2F/")
        assert request.headers["Authorization"].startswith("Basic ")
        requests.append(request.url.path.split("/")[-1])
        return httpx.Response(200, json={"state": "running"} if len(requests) == 1 else counters)

    with httpx.Client(transport=httpx.MockTransport(management_response)) as client:
        snapshot = verify_visual_shadow.rabbit_snapshot(client, "staging")

    assert waits == [1]
    assert requests == ["search.indexing", *verify_visual_shadow.QUEUES]
    assert snapshot == {"observed": True, "queues": dict.fromkeys(verify_visual_shadow.QUEUES, counters)}
