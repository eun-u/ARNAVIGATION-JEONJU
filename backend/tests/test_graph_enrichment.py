import hashlib
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.graph_enrichment import GraphEnrichmentBundleError
from app.main import (
    DEFAULT_GRAPH_ENRICHMENT_PATH,
    PROJECT_ROOT,
    create_app,
)


SAMPLE_GRAPH_PATH = PROJECT_ROOT / "data" / "sample" / "navi_accessibility_graph.geojson"
PROCESSED_GRAPH_PATH = PROJECT_ROOT / "data/processed/anyang_accessibility_graph.geojson"
DEMO_PAYLOAD = {
    "origin": {"lat": 37.4019, "lon": 126.9205},
    "destination": {"lat": 37.4001, "lon": 126.9240},
    "profile": "wheelchair",
}


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _candidate(
    candidate_id: str,
    edge_id: str,
    candidate_type: str,
    proposed_changes: dict,
) -> dict:
    return {
        "candidate_id": candidate_id,
        "edge_id": edge_id,
        "type": candidate_type,
        "source": "test_spatial_evaluation",
        "confidence": None,
        "status": "pending",
        "verified": False,
        "graph_update_allowed": False,
        "requires_human_review": True,
        "priority": "high" if proposed_changes else "low",
        "routing_impact": (
            "wheelchair_edge_exclusion_after_approval"
            if proposed_changes
            else "none_before_review"
        ),
        "mapping_status": "unique",
        "mapping_quality": "single_source_unique_match",
        "candidate_class": "routing_attribute" if proposed_changes else "evidence_only",
        "simulation_allowed": bool(proposed_changes),
        "approval_eligible": True,
        "quality_flags": [],
        "proposed_changes": proposed_changes,
        "current_values": {key: False for key in proposed_changes},
        "evidence_count": 1,
        "source_types": ["test_source"],
        "evidence": [{"source_type": "test_source", "mapping_status": "unique"}],
        "visual_evidence_refs": [],
        "lat": 37.401,
        "lon": 126.922,
        "created_at": "2026-09-18T10:00:00+09:00",
    }


def _write_sample_bundle(tmp_path: Path) -> Path:
    path = tmp_path / "candidate_bundle.json"
    path.write_text(
        json.dumps(
            {
                "schema_version": "1.0",
                "created_at": "2026-09-18T10:00:00+09:00",
                "baseline_graph_sha256": _sha256(SAMPLE_GRAPH_PATH),
                "status": "pending",
                "derived": True,
                "verified": False,
                "graph_update_allowed": False,
                "candidates": [
                    _candidate(
                        "GEC-TEST-ROUTE-E006",
                        "E006",
                        "stairs_attribute_candidate",
                        {"stairs": True},
                    ),
                    _candidate(
                        "GEC-TEST-EVIDENCE-E005",
                        "E005",
                        "pedestrian_area_evidence",
                        {},
                    ),
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    return path


def test_candidate_catalog_and_simulation_are_read_only(tmp_path):
    bundle_path = _write_sample_bundle(tmp_path)
    app = create_app(SAMPLE_GRAPH_PATH, tmp_path / "candidate-api.db", bundle_path)

    with TestClient(app) as client:
        summary = client.get("/graph-enrichment/summary")
        assert summary.status_code == 200
        assert summary.json()["candidate_count"] == 2
        assert summary.json()["route_affecting_candidate_count"] == 1
        assert summary.json()["all_pending"] is True
        assert summary.json()["all_unverified"] is True
        assert summary.json()["graph_update_allowed"] is False

        filtered = client.get(
            "/graph-enrichment/candidates?type=stairs_attribute_candidate&route_affecting=true"
        )
        assert filtered.status_code == 200
        assert filtered.json()["total"] == 1
        assert filtered.json()["candidates"][0]["candidate_id"] == "GEC-TEST-ROUTE-E006"

        before_health = client.get("/health").json()
        before_edge = client.get("/edges/E006").json()
        response = client.post(
            "/graph-enrichment/simulate",
            json={**DEMO_PAYLOAD, "candidate_ids": ["GEC-TEST-ROUTE-E006"]},
        )

        assert response.status_code == 200
        body = response.json()
        assert body["status"] == "ok"
        assert body["baseline"]["distance_m"] == 405.7
        assert body["simulated"]["distance_m"] == 535.3
        assert body["baseline_candidate_edge_ids"] == ["E006"]
        assert body["route_changed"] is True
        assert body["difference_m"] == 129.6
        assert body["graph_mutated"] is False
        assert body["database_mutated"] is False

        after_health = client.get("/health").json()
        assert client.get("/edges/E006").json() == before_edge
        assert after_health["database"] == before_health["database"]
        assert after_health["observations"] == before_health["observations"]


def test_evidence_only_candidate_cannot_be_used_as_route_fact(tmp_path):
    app = create_app(
        SAMPLE_GRAPH_PATH,
        tmp_path / "evidence-only.db",
        _write_sample_bundle(tmp_path),
    )
    with TestClient(app) as client:
        response = client.post(
            "/graph-enrichment/simulate",
            json={**DEMO_PAYLOAD, "candidate_ids": ["GEC-TEST-EVIDENCE-E005"]},
        )

    assert response.status_code == 422
    assert response.json()["status"] == "candidate_not_simulatable"


@pytest.mark.skipif(not DEFAULT_GRAPH_ENRICHMENT_PATH.is_file(), reason="Optional local Anyang spatial bundle is not distributed in this checkout")
def test_real_candidate_bundle_reproduces_route_impact_without_mutation(tmp_path):
    graph_sha_before = _sha256(PROCESSED_GRAPH_PATH)
    app = create_app(
        PROCESSED_GRAPH_PATH,
        tmp_path / "real-candidates.db",
        DEFAULT_GRAPH_ENRICHMENT_PATH,
    )
    candidate_id = "GEC-61063192388228C2"
    edge_id = "OSM_E_379904073_9581b7b371"
    edge_before = app.state.graph_store.get_edge(edge_id)
    origin_node = app.state.graph_store.get_node(edge_before["from_node"])
    destination_node = app.state.graph_store.get_node(edge_before["to_node"])

    with TestClient(app) as client:
        summary = client.get("/graph-enrichment/summary").json()
        assert summary["candidate_count"] == 247
        assert summary["candidate_edge_count"] == 196
        assert summary["route_affecting_candidate_count"] == 17
        assert summary["evidence_only_candidate_count"] == 230
        assert summary["diagnostic_candidate_count"] == 12
        assert summary["approval_eligible_candidate_count"] == 235
        assert summary["orthophoto_referenced_candidate_count"] == 247

        before_health = client.get("/health").json()
        response = client.post(
            "/graph-enrichment/simulate",
            json={
                "origin": {"lat": origin_node["lat"], "lon": origin_node["lon"]},
                "destination": {
                    "lat": destination_node["lat"],
                    "lon": destination_node["lon"],
                },
                "profile": "wheelchair",
                "candidate_ids": [candidate_id],
            },
        )

        assert response.status_code == 200
        body = response.json()
        assert body["baseline"]["distance_m"] == 98.1
        assert body["simulated"]["distance_m"] == 212.2
        assert body["difference_m"] == 114.1
        assert edge_id in body["baseline"]["edge_ids"]
        assert edge_id not in body["simulated"]["edge_ids"]
        assert body["route_changed"] is True
        assert body["graph_mutated"] is False
        assert body["database_mutated"] is False
        assert client.get("/health").json()["database"] == before_health["database"]

    assert app.state.graph_store.get_edge(edge_id) == edge_before
    assert _sha256(PROCESSED_GRAPH_PATH) == graph_sha_before


@pytest.mark.skipif(not DEFAULT_GRAPH_ENRICHMENT_PATH.is_file(), reason="Optional local Anyang spatial bundle is not distributed in this checkout")
def test_dem_slope_candidate_is_diagnostic_and_simulatable_without_mutation(tmp_path):
    graph_sha_before = _sha256(PROCESSED_GRAPH_PATH)
    app = create_app(
        PROCESSED_GRAPH_PATH,
        tmp_path / "dem-diagnostic.db",
        DEFAULT_GRAPH_ENRICHMENT_PATH,
    )
    candidate_id = "GEC-CE1DCD49F1BE0C70"
    edge_id = "OSM_E_484348850_d12ea5bb0b"
    edge_before = app.state.graph_store.get_edge(edge_id)
    origin_node = app.state.graph_store.get_node(edge_before["from_node"])
    destination_node = app.state.graph_store.get_node(edge_before["to_node"])

    with TestClient(app) as client:
        diagnostics = client.get(
            "/graph-enrichment/candidates?candidate_class=diagnostic_sensitivity&limit=250"
        )
        assert diagnostics.status_code == 200
        assert diagnostics.json()["total"] == 12
        candidate = next(
            item
            for item in diagnostics.json()["candidates"]
            if item["candidate_id"] == candidate_id
        )
        assert candidate["proposed_changes"]["slope"] == 9.281558
        assert candidate["approval_eligible"] is False
        assert candidate["simulation_allowed"] is True
        assert candidate["quality_flags"] == [
            "coarse_90m_dem",
            "not_hard_constraint_eligible",
        ]
        assert candidate["visual_evidence_refs"]
        assert all(
            ref["geometry_correction_allowed"] is False
            and ref["graph_update_allowed"] is False
            and ref["verified"] is False
            for ref in candidate["visual_evidence_refs"]
        )

        response = client.post(
            "/graph-enrichment/simulate",
            json={
                "origin": {"lat": origin_node["lat"], "lon": origin_node["lon"]},
                "destination": {
                    "lat": destination_node["lat"],
                    "lon": destination_node["lon"],
                },
                "profile": "wheelchair",
                "candidate_ids": [candidate_id],
            },
        )

        assert response.status_code == 200
        body = response.json()
        assert body["baseline"]["distance_m"] == 171.3
        assert body["simulated"]["distance_m"] == 361.2
        assert body["difference_m"] == 189.9
        assert body["graph_mutated"] is False
        assert body["database_mutated"] is False
        assert any("90m" in warning for warning in body["warnings"])

    assert app.state.graph_store.get_edge(edge_id) == edge_before
    assert _sha256(PROCESSED_GRAPH_PATH) == graph_sha_before


def test_candidate_bundle_must_match_selected_graph_sha(tmp_path):
    bundle_path = _write_sample_bundle(tmp_path)
    payload = json.loads(bundle_path.read_text(encoding="utf-8"))
    payload["baseline_graph_sha256"] = "0" * 64
    bundle_path.write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(GraphEnrichmentBundleError, match="baseline SHA-256"):
        create_app(SAMPLE_GRAPH_PATH, tmp_path / "mismatch.db", bundle_path)
