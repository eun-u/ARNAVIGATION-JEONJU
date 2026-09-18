from __future__ import annotations

from datetime import datetime, timedelta, timezone
from pathlib import Path

from fastapi.testclient import TestClient

from app.main import PROJECT_ROOT, create_app

# These are the legacy Anyang review/persistence fixtures, not Jeonju field evidence.
PROCESSED_GRAPH_PATH = PROJECT_ROOT / "data/processed/anyang_accessibility_graph.geojson"


def demo_payload(client: TestClient) -> tuple[dict, dict]:
    graph = client.get("/graph").json()
    demo = graph["metadata"]["demo"]
    nodes = {feature["properties"]["node_id"]: feature for feature in graph["features"] if feature["properties"]["feature_type"] == "node"}
    def coordinate(node_id: str) -> dict:
        lon, lat = nodes[node_id]["geometry"]["coordinates"]
        return {"lat": lat, "lon": lon}
    return {"origin": coordinate(demo["origin_node"]), "destination": coordinate(demo["destination_node"]), "profile": "wheelchair"}, demo


def test_processed_osm_graph_reproduces_three_route_states(tmp_path):
    app = create_app(PROCESSED_GRAPH_PATH, tmp_path / "osm.db")
    with TestClient(app) as client:
        payload, demo = demo_payload(client)
        before = client.post("/route/compare", json=payload).json()
        assert before["standard"]["distance_m"] == demo["expected"]["standard"]["distance_m"]
        assert before["accessible"]["distance_m"] == demo["expected"]["accessible_before"]["distance_m"]
        changed = client.patch(f"/edges/{demo['block_edge']}/status", json={"blocked": True, "reason": "construction", "session_id": before["session_id"]})
        assert changed.status_code == 200
        assert changed.json()["recalculated_route"]["distance_m"] == demo["expected"]["accessible_after"]["distance_m"]


def test_sessions_are_distinct_and_recalculated_without_global_last_route(tmp_path):
    app = create_app(PROCESSED_GRAPH_PATH, tmp_path / "sessions.db")
    with TestClient(app) as client:
        payload, demo = demo_payload(client)
        first = client.post("/route/compare", json=payload).json()
        second = client.post("/route/compare", json=payload).json()
        assert first["session_id"] != second["session_id"]
        result = client.patch(f"/edges/{demo['block_edge']}/status", json={"blocked": True, "reason": "construction", "session_id": first["session_id"]}).json()
        assert result["affected_session_count"] == 2
        assert result["previous_route"]["session_id"] == first["session_id"]
        second_session = client.get(f"/route/sessions/{second['session_id']}").json()
        assert second_session["route"]["distance_m"] == demo["expected"]["accessible_after"]["distance_m"]


def test_candidate_requires_human_review_and_tactile_candidate_does_not_block_graph(tmp_path):
    app = create_app(PROCESSED_GRAPH_PATH, tmp_path / "review.db")
    with TestClient(app) as client:
        candidates = client.get("/observations/candidates?status=pending").json()
        assert len(candidates) == 5
        tactile = next(item for item in candidates if item["type"] == "tactile_absent_candidate")
        before = client.get(f"/edges/{tactile['edge_id']}").json()
        reviewed = client.post(f"/observations/candidates/{tactile['candidate_id']}/review", json={
            "decision": "approved", "result": "verified_block", "reviewer": "field-reviewer",
            "observed_at": "2026-09-16T10:00:00+09:00", "reason": "점자블록 부재를 현장에서 확인", "measurements": {},
        }).json()
        after = client.get(f"/edges/{tactile['edge_id']}").json()
        assert reviewed["graph_updated"] is False
        assert before["blocked"] == after["blocked"]


def test_approved_construction_persists_with_history_across_restart(tmp_path):
    db_path = tmp_path / "persist.db"
    app = create_app(PROCESSED_GRAPH_PATH, db_path)
    with TestClient(app) as client:
        construction = next(item for item in client.get("/observations/candidates?status=pending").json() if item["type"] == "construction_block_candidate")
        response = client.post(f"/observations/candidates/{construction['candidate_id']}/review", json={
            "decision": "approved", "result": "verified_block", "reviewer": "anyang-field-01",
            "observed_at": "2026-09-16T10:20:00+09:00", "reason": "공사 가림막으로 통행 불가 확인", "measurements": {},
        })
        assert response.status_code == 200
        assert response.json()["graph_updated"] is True
        edge_id = construction["edge_id"]
        assert client.get(f"/edges/{edge_id}").json()["blocked"] is True
        assert len(client.get(f"/edges/{edge_id}/history").json()) == 1
    restarted = create_app(PROCESSED_GRAPH_PATH, db_path)
    with TestClient(restarted) as client:
        edge = client.get(f"/edges/{edge_id}").json()
        assert edge["blocked"] is True
        assert edge["verified"] is True
        assert edge["status_source"] == "approved_observation"


def test_expired_session_is_rejected(tmp_path):
    app = create_app(PROCESSED_GRAPH_PATH, tmp_path / "expiry.db")
    with TestClient(app) as client:
        payload, _ = demo_payload(client)
        session_id = client.post("/route/compare", json=payload).json()["session_id"]
        expired = (datetime.now(timezone.utc) - timedelta(minutes=1)).isoformat()
        with app.state.database.connection:
            app.state.database.connection.execute("UPDATE route_sessions SET expires_at=? WHERE session_id=?", (expired, session_id))
        response = client.get(f"/route/sessions/{session_id}")
        assert response.status_code == 404
        assert response.json()["status"] == "route_session_not_found"


def test_verified_direct_update_requires_named_actor(tmp_path):
    app = create_app(PROCESSED_GRAPH_PATH, tmp_path / "actor.db")
    with TestClient(app) as client:
        _, demo = demo_payload(client)
        response = client.patch(f"/edges/{demo['block_edge']}/status", json={"blocked": True, "reason": "construction", "verified": True})
        assert response.status_code == 422
