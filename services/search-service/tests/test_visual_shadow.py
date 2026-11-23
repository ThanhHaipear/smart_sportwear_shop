import json

import httpx

from scripts import monitor_visual_cohort, verify_visual_shadow


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


def test_cohort_monitor_reads_new_windows_launcher_logs(tmp_path, monkeypatch):
    log = tmp_path / "gateway.log"
    base = {"event": "gateway_request", "method": "POST", "path": verify_visual_shadow.PUBLIC_PATH}
    previous = {**base, "visualCohortMember": True, "status": 503, "durationMs": 99}
    log.write_text(json.dumps(previous) + "\n", encoding="utf-16")
    clock = iter((0, 2))
    monkeypatch.setattr(monitor_visual_cohort, "monotonic", lambda: next(clock))

    def broker_sample(*_):
        with log.open("a", encoding="utf-16-le") as stream:
            for row in ({**base, "visualCohortMember": True, "status": 200, "durationMs": 420},
                        {**base, "visualCohortMember": False, "status": 403, "durationMs": 1}):
                stream.write(json.dumps(row) + "\n")
        return {"queues": {verify_visual_shadow.QUEUES[0]: {"consumers": 1},
                           verify_visual_shadow.QUEUES[-1]: {"messages": 0}}}

    monkeypatch.setattr(monitor_visual_cohort, "rabbit_snapshot", broker_sample)
    report = monitor_visual_cohort.observe(log, 1, 1)
    assert report["decision"] == "PASS_OPERATIONAL_WINDOW"
    assert report["memberRequests"] == report["blockedRequests"] == 1
    assert report["memberErrors"] == report["peakDlqDepth"] == 0
    assert report["gatewayP95Ms"] == 420
