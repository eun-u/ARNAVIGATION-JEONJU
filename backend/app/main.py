from __future__ import annotations

import json
import hashlib
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Query, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from .database import Database
from .graph_enrichment import (
    GraphEnrichmentCandidateNotFoundError,
    GraphEnrichmentCatalog,
    GraphEnrichmentService,
    GraphEnrichmentSimulationError,
    GraphEnrichmentUnavailableError,
)
from .graph_store import EdgeNotFoundError, GraphStore
from .profiles import ProfileRegistry
from .poc_scope import PocContractError
from .routing import RouteEngine, RouteNotFoundError
from .schemas import (
    EdgeStatusResponse,
    EdgeStatusUpdate,
    GraphEnrichmentCandidate,
    GraphEnrichmentCandidateList,
    GraphEnrichmentSimulationRequest,
    GraphEnrichmentSimulationResponse,
    GraphEnrichmentSummary,
    ObservationCandidate,
    ObservationCandidateCreate,
    ObservationReviewRequest,
    ObservationReviewResponse,
    RouteComparison,
    RouteRequest,
    RouteResult,
    RouteSessionResponse,
    SessionRerouteRequest,
    SessionRerouteResponse,
)
from .services import ObservationCandidateNotFoundError, RouteService, RouteSessionNotFoundError


PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROCESSED_GRAPH_PATH = PROJECT_ROOT / "data" / "processed" / "jeonju_accessibility_graph.geojson"
SAMPLE_GRAPH_PATH = PROJECT_ROOT / "data" / "sample" / "navi_accessibility_graph.geojson"
DEFAULT_GRAPH_PATH = PROCESSED_GRAPH_PATH
DEFAULT_DB_PATH = PROJECT_ROOT / "data" / "runtime" / "jeonju_p0_20260918_5b006cb4.db"
DEFAULT_CANDIDATES_PATH = PROJECT_ROOT / "data" / "processed" / "review_candidates.json"
DEFAULT_GRAPH_ENRICHMENT_PATH = (
    PROJECT_ROOT
    / "data"
    / "processed"
    / "evaluation"
    / "graph_enrichment"
    / "candidate_bundle.json"
)
FRONTEND_DIR = PROJECT_ROOT / "frontend"


def create_app(
    graph_path: Path | None = None,
    db_path: Path | None = None,
    graph_enrichment_path: Path | None = None,
) -> FastAPI:
    configured_graph = os.getenv("NAVI_GRAPH_PATH", "").strip()
    configured_db = os.getenv("NAVI_DB_PATH", "").strip()
    configured_graph_enrichment = os.getenv("NAVI_GRAPH_ENRICHMENT_PATH", "").strip()
    selected_graph = graph_path or (Path(configured_graph) if configured_graph else DEFAULT_GRAPH_PATH)
    selected_db = db_path or (Path(configured_db) if configured_db else DEFAULT_DB_PATH)
    if graph_enrichment_path is not None:
        selected_graph_enrichment = graph_enrichment_path
    elif configured_graph_enrichment:
        selected_graph_enrichment = Path(configured_graph_enrichment)
    elif selected_graph.resolve() == PROCESSED_GRAPH_PATH.resolve():
        selected_graph_enrichment = DEFAULT_GRAPH_ENRICHMENT_PATH
    else:
        selected_graph_enrichment = None

    if not selected_graph.is_file():
        raise RuntimeError(f"dataset_missing: {selected_graph}")
    store = GraphStore(selected_graph)
    if store.metadata.get("candidate_only"):
        raise RuntimeError("candidate_graph_not_activated: review lineage and create a scope revision first")
    database = Database(selected_db)
    if store.metadata.get("osm_snapshot", "").startswith("data/raw/jeonju/"):
        from .poc_scope import graph_digest
        graph_hash = graph_digest(selected_graph)
        binding = database.connection.execute("SELECT value FROM app_meta WHERE key='graph_sha256'").fetchone()
        if binding and binding[0] != graph_hash:
            database.close()
            raise RuntimeError("database_graph_revision_mismatch: use a dedicated Jeonju database")
        with database.connection:
            database.connection.execute("INSERT OR IGNORE INTO app_meta VALUES('graph_sha256',?)",(graph_hash,))
    for overlay in database.edge_overlays():
        try:
            store.apply_edge_overlay(overlay["edge_id"], overlay["values"])
        except EdgeNotFoundError:
            pass
    if DEFAULT_CANDIDATES_PATH.exists() and not bool(
        store.metadata.get("field_test_only")
    ):
        database.seed_candidates(json.loads(DEFAULT_CANDIDATES_PATH.read_text(encoding="utf-8")))
    profiles = ProfileRegistry()
    engine = RouteEngine(store, profiles)
    service = RouteService(store, engine, database)
    graph_enrichment_catalog = GraphEnrichmentCatalog(
        selected_graph_enrichment,
        selected_graph,
        store,
    )
    graph_enrichment_service = GraphEnrichmentService(
        graph_enrichment_catalog,
        engine,
        database,
    )

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        yield
        database.close()

    app = FastAPI(title="NaVi Accessibility Routing PoC", version="0.2.0", description="OSM 보행망에서 일반 경로와 접근 가능 경로를 비교하고, 검수된 현장 상태 변화 후 세션별 경로를 재계산하는 PoC", lifespan=lifespan)
    app.state.graph_store, app.state.database, app.state.route_service = store, database, service
    app.state.graph_enrichment_catalog = graph_enrichment_catalog
    app.state.graph_enrichment_service = graph_enrichment_service

    @app.exception_handler(RouteNotFoundError)
    async def route_not_found_handler(_request: Request, exc: RouteNotFoundError) -> JSONResponse:
        return JSONResponse(status_code=404, content={"status": exc.status, "message": exc.message, "reason_code": exc.reason_code})

    @app.exception_handler(PocContractError)
    async def poc_error(_request: Request, exc: PocContractError) -> JSONResponse:
        body={"status":"rejected", "reason_code":exc.reason_code, "message":str(exc)}
        if exc.current_route_revision is not None:body['current_route_revision']=exc.current_route_revision
        if exc.current_graph_revision is not None:body['current_graph_revision']=exc.current_graph_revision
        return JSONResponse(status_code=exc.status_code, content=body)

    @app.get("/demo/jeonju")
    def jeonju_bootstrap() -> dict:
        if not engine.scope:
            raise PocContractError("wrong_region")
        s = engine.scope
        def coordinate(node):
            a = store.get_node(node)
            return {"lat":a["lat"], "lon":a["lon"]}
        return {**s.data,"graph_revision":database.graph_revision,"profile":"demo_jeonju",
                "alignment_sources":["measured_references","poc_start"],
                "poc_start_forward":{"lat":35.8458917,"lon":127.1319832},
                "origin":coordinate(s.data["origin_node"]),"destination":coordinate(s.data["destination_node"]),
                "segments":[{"edge_id":e,"physical_segment_id":e,"geometry":store.get_edge(e)["geometry"]} for e in sorted(s.allowed)]}

    @app.exception_handler(EdgeNotFoundError)
    async def edge_not_found_handler(_request: Request, exc: EdgeNotFoundError) -> JSONResponse:
        return JSONResponse(status_code=404, content={"status": "edge_not_found", "edge_id": exc.args[0] if exc.args else "unknown"})

    @app.exception_handler(RouteSessionNotFoundError)
    async def session_not_found_handler(_request: Request, exc: RouteSessionNotFoundError) -> JSONResponse:
        return JSONResponse(status_code=404, content={"status": "route_session_not_found", "session_id": exc.args[0]})

    @app.exception_handler(ObservationCandidateNotFoundError)
    async def candidate_not_found_handler(_request: Request, exc: ObservationCandidateNotFoundError) -> JSONResponse:
        return JSONResponse(status_code=404, content={"status": "candidate_not_found", "candidate_id": exc.args[0]})

    @app.exception_handler(GraphEnrichmentCandidateNotFoundError)
    async def graph_enrichment_candidate_not_found_handler(
        _request: Request, exc: GraphEnrichmentCandidateNotFoundError
    ) -> JSONResponse:
        return JSONResponse(
            status_code=404,
            content={
                "status": "graph_enrichment_candidate_not_found",
                "candidate_id": exc.args[0],
            },
        )

    @app.exception_handler(GraphEnrichmentUnavailableError)
    async def graph_enrichment_unavailable_handler(
        _request: Request, exc: GraphEnrichmentUnavailableError
    ) -> JSONResponse:
        return JSONResponse(
            status_code=503,
            content={"status": "graph_enrichment_unavailable", "message": str(exc)},
        )

    @app.exception_handler(GraphEnrichmentSimulationError)
    async def graph_enrichment_simulation_error_handler(
        _request: Request, exc: GraphEnrichmentSimulationError
    ) -> JSONResponse:
        return JSONResponse(
            status_code=422,
            content={"status": "candidate_not_simulatable", "message": str(exc)},
        )

    @app.get("/health")
    def health() -> dict:
        enrichment_summary = graph_enrichment_catalog.summary()
        manifest_path = PROJECT_ROOT / "data/processed/jeonju/p0_manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.is_file() else {}
        return {"status": "ok", "service": "NaVi", **(engine.scope.revisions() if engine.scope else {}),
            "profiles":[p.name for p in profiles.all()], "scope_edge_count":len(engine.scope.allowed) if engine.scope else None,
            "missing_inputs":manifest.get("missing_inputs",["p0_manifest.json"]), "field_crossing_check":"pending",
            "database": {"schema_version": database.SCHEMA_VERSION, "graph_revision": database.graph_revision,"edge_overlay_count":len(database.edge_overlays())},
            "observations": {"pending": len(database.list_candidates("pending"))},
            "graph": {"nodes": store.node_count, "edges": store.edge_count, "source": store.metadata.get("source"), "accessibility_attributes": store.metadata.get("accessibility_attributes")},
            "graph_enrichment": {"available": enrichment_summary.available, "candidate_count": enrichment_summary.candidate_count, "route_affecting_candidate_count": enrichment_summary.route_affecting_candidate_count}}

    @app.get("/profiles")
    def list_profiles() -> list[dict]:
        return [profile.model_dump() for profile in profiles.all()]

    @app.get("/graph")
    def graph() -> dict:
        return store.to_geojson()

    @app.post("/route", response_model=RouteResult)
    def route(payload: RouteRequest) -> RouteResult:
        return service.route(payload)

    @app.post("/route/compare", response_model=RouteComparison)
    def compare_routes(payload: RouteRequest) -> RouteComparison:
        return service.compare(payload)

    @app.get("/route/sessions/{session_id}", response_model=RouteSessionResponse)
    def route_session(session_id: str) -> RouteSessionResponse:
        return service.get_session(session_id)

    @app.post("/route/sessions/{session_id}/reroute", response_model=SessionRerouteResponse)
    def reroute_session(session_id: str, payload: SessionRerouteRequest) -> SessionRerouteResponse:
        return service.reroute_session(session_id, payload)

    @app.get("/edges/{edge_id}")
    def get_edge(edge_id: str) -> dict:
        return store.get_edge(edge_id)

    @app.get("/edges/{edge_id}/history")
    def edge_history(edge_id: str) -> list[dict]:
        store.get_edge(edge_id)
        return database.edge_history(edge_id)

    @app.patch("/edges/{edge_id}/status", response_model=EdgeStatusResponse)
    def update_edge_status(edge_id: str, payload: EdgeStatusUpdate) -> EdgeStatusResponse:
        return service.update_edge_and_recalculate(edge_id, payload)

    @app.get("/observations/candidates", response_model=list[ObservationCandidate])
    def candidates(status: str | None = Query(default=None)) -> list[ObservationCandidate]:
        return service.list_candidates(status)

    @app.post("/observations/candidates", response_model=ObservationCandidate, status_code=201)
    def create_candidate(payload: ObservationCandidateCreate) -> ObservationCandidate:
        return service.create_candidate(payload)

    @app.get("/observations/candidates/{candidate_id}", response_model=ObservationCandidate)
    def candidate(candidate_id: str) -> ObservationCandidate:
        return service.get_candidate(candidate_id)

    @app.post("/observations/candidates/{candidate_id}/review", response_model=ObservationReviewResponse)
    def review_candidate(candidate_id: str, payload: ObservationReviewRequest) -> ObservationReviewResponse:
        return service.review_candidate(candidate_id, payload)

    @app.get("/graph-enrichment/summary", response_model=GraphEnrichmentSummary)
    def graph_enrichment_summary() -> GraphEnrichmentSummary:
        return graph_enrichment_catalog.summary()

    @app.get(
        "/graph-enrichment/candidates",
        response_model=GraphEnrichmentCandidateList,
    )
    def graph_enrichment_candidates(
        candidate_type: str | None = Query(default=None, alias="type"),
        priority: str | None = Query(default=None),
        candidate_class: str | None = Query(default=None),
        route_affecting: bool | None = Query(default=None),
        offset: int = Query(default=0, ge=0),
        limit: int = Query(default=50, ge=1, le=250),
    ) -> GraphEnrichmentCandidateList:
        return graph_enrichment_catalog.list_candidates(
            candidate_type=candidate_type,
            priority=priority,
            candidate_class=candidate_class,
            route_affecting=route_affecting,
            offset=offset,
            limit=limit,
        )

    @app.get(
        "/graph-enrichment/candidates/{candidate_id}",
        response_model=GraphEnrichmentCandidate,
    )
    def graph_enrichment_candidate(candidate_id: str) -> GraphEnrichmentCandidate:
        return graph_enrichment_catalog.get_candidate(candidate_id)

    @app.post(
        "/graph-enrichment/simulate",
        response_model=GraphEnrichmentSimulationResponse,
    )
    def simulate_graph_enrichment_candidate(
        payload: GraphEnrichmentSimulationRequest,
    ) -> GraphEnrichmentSimulationResponse:
        return graph_enrichment_service.simulate(payload)

    app.mount("/static", StaticFiles(directory=FRONTEND_DIR), name="static")

    @app.get("/", include_in_schema=False)
    def index() -> FileResponse:
        return FileResponse(FRONTEND_DIR / "index.html")

    @app.get("/review", include_in_schema=False)
    def review() -> FileResponse:
        return FileResponse(FRONTEND_DIR / "review.html")

    return app


app = create_app()
