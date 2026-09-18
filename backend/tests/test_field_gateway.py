"""Transport/access tests only; no field accuracy or AI acceptance evidence."""
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from fastapi.testclient import TestClient

from app.field_gateway import MAX_BODY_BYTES, create_gateway

TOKEN = "test-" + "a" * 40
HEADERS = {"Authorization": "Bearer " + TOKEN}


@pytest.fixture
def gateway():
    calls = []

    class Handler(BaseHTTPRequestHandler):
        def respond(self):
            body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            calls.append((self.command, self.path, body, self.headers.get("Authorization")))
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"transport_test":true}')

        do_GET = do_POST = respond

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with TestClient(create_gateway(token=TOKEN, upstream=f"http://127.0.0.1:{server.server_port}")) as client:
            yield client, calls
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


def test_denies_missing_wrong_and_non_ascii_credentials(gateway):
    client, calls = gateway
    for headers in ({}, {"Authorization": "Bearer wrong"}, {"Authorization": "Bearer " + "x" * 200}):
        assert client.get("/demo/jeonju", headers=headers).status_code == 401
    assert calls == []


@pytest.mark.parametrize("method,path", [
    ("GET", "/docs"), ("GET", "/openapi.json"), ("GET", "/graph"),
    ("POST", "/observations"), ("PATCH", "/edges/example/status"),
    ("POST", "/route/compare"), ("GET", "/demo/jeonju?token=anything"),
])
def test_only_poc_endpoints_are_public(gateway, method, path):
    client, calls = gateway
    assert client.request(method, path, headers=HEADERS).status_code == 404
    assert calls == []


def test_bootstrap_and_demo_route_forward_without_secret(gateway):
    client, calls = gateway
    assert client.get("/demo/jeonju", headers=HEADERS).json() == {"transport_test": True}
    body = {"profile": "demo_jeonju", "origin": {"lat": 35.8, "lon": 127.1}}
    assert client.post("/route", headers=HEADERS, json=body).status_code == 200
    assert calls[0] == ("GET", "/demo/jeonju", b"", None)
    assert json.loads(calls[1][2]) == body
    assert calls[1][3] is None


def test_rejects_legacy_routes_invalid_json_and_oversized_body(gateway):
    client, calls = gateway
    assert client.post("/route", headers=HEADERS, json={"profile": "wheelchair"}).status_code == 403
    assert client.post("/route", headers=HEADERS, content=b"{").status_code == 400
    assert client.post("/route", headers=HEADERS, json=[]).status_code == 400
    assert client.post("/route", headers=HEADERS, content=b"x" * (MAX_BODY_BYTES + 1)).status_code == 413
    assert client.post("/route/sessions/session-test/reroute", headers=HEADERS, json={"edge_id": "x"}).status_code == 403
    assert calls == []


def test_poc_reroute_is_forwarded(gateway):
    client, calls = gateway
    payload = {key: "contract-test" for key in ("current_position", "region_id", "dataset_revision", "scope_revision",
               "graph_sha256", "graph_revision", "expected_route_revision", "event_id")}
    assert client.post("/route/sessions/session-test/reroute", headers=HEADERS, json=payload).status_code == 200
    assert len(calls) == 1


def test_gateway_fails_closed_on_missing_secret_or_remote_upstream():
    with pytest.raises(RuntimeError):
        create_gateway(token="")
    with pytest.raises(RuntimeError):
        create_gateway(token=TOKEN, upstream="http://example.com")
