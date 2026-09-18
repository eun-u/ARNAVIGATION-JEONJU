from __future__ import annotations

from datetime import timedelta
from threading import RLock
from uuid import uuid4

from .database import Database, utc_now
from .graph_store import GraphStore
from .routing import RouteEngine, RouteNotFoundError
from .schemas import EdgeStatusResponse, EdgeStatusUpdate, ObservationCandidate, ObservationReviewRequest, ObservationReviewResponse, RouteComparison, RouteRequest, RouteResult, RouteSessionResponse
from .schemas import ObservationCandidateCreate, SessionRerouteRequest, SessionRerouteResponse
from .poc_sessions import reroute_poc
from .poc_scope import PocContractError


class RouteSessionNotFoundError(KeyError):
    pass


class ObservationCandidateNotFoundError(KeyError):
    pass


class RouteService:
    SESSION_TTL = timedelta(hours=24)

    def __init__(self, store: GraphStore, engine: RouteEngine, database: Database) -> None:
        self.store, self.engine, self.database = store, engine, database
        self._lock = RLock()

    def _session_identity(self, requested: str | None) -> tuple[str, object]:
        if requested:
            existing = self.database.get_session(requested)
            if not existing:
                raise RouteSessionNotFoundError(requested)
            return requested, utc_now() + self.SESSION_TTL
        return str(uuid4()), utc_now() + self.SESSION_TTL

    def _decorate_route(self, result: RouteResult, session_id: str, expires_at) -> RouteResult:
        return result.model_copy(update={"session_id": session_id, "graph_revision": self.database.graph_revision, "expires_at": expires_at})

    def route(self, request: RouteRequest) -> RouteResult:
        if request.alignment_source == "poc_start":
            scope = self.engine.scope
            if scope is None:
                raise PocContractError("poc_start_scope_required", status_code=422)
            from .routing import haversine_m
            from .schemas import Coordinate
            graph = scope.snapshot(self.store)
            for point, key in ((request.origin, "origin_node"), (request.destination, "destination_node")):
                node = graph.nodes[scope.data[key]]
                if haversine_m(point, Coordinate(lat=node["lat"], lon=node["lon"])) > 0.1:
                    raise PocContractError("poc_start_fixed_course_required", status_code=422)
        if self.engine.scope and request.session_id:
            raise PocContractError("existing_session_requires_reroute")
        session_id, expires_at = self._session_identity(request.session_id)
        result = self._decorate_route(self.engine.find_accessible_route(request), session_id, expires_at).model_copy(update={"alignment_source": request.alignment_source})
        self.database.save_session(
            session_id,
            request.model_dump(mode="json", exclude={"session_id"}),
            result.model_dump(mode="json"),
            None,
            expires_at,
            [],
        )
        return result

    def _comparison(
        self,
        request: RouteRequest,
        session_id: str,
        expires_at,
        temporary_blocked_edge_ids: set[str] | None = None,
    ) -> RouteComparison:
        standard = self._decorate_route(self.engine.find_shortest_route(request), session_id, expires_at)
        accessible = self._decorate_route(
            self.engine.find_accessible_route(request, temporary_blocked_edge_ids),
            session_id,
            expires_at,
        )
        difference = round(accessible.distance_m - standard.distance_m, 1)
        difference_pct = round(difference / standard.distance_m * 100, 2) if standard.distance_m else 0.0
        return RouteComparison(
            standard=standard, accessible=accessible, difference_m=difference, difference_pct=difference_pct,
            reasons=accessible.reasons, warnings=list(dict.fromkeys(standard.warnings + accessible.warnings)),
            session_id=session_id, graph_revision=self.database.graph_revision, expires_at=expires_at,
        )

    def compare(self, request: RouteRequest) -> RouteComparison:
        if request.alignment_source == "poc_start":
            raise PocContractError("poc_start_requires_route", status_code=422)
        if self.engine.scope and request.session_id:
            raise PocContractError("existing_session_requires_reroute")
        session_id, expires_at = self._session_identity(request.session_id)
        comparison = self._comparison(request, session_id, expires_at)
        self.database.save_session(
            session_id,
            request.model_dump(mode="json", exclude={"session_id"}),
            comparison.accessible.model_dump(mode="json"),
            comparison.model_dump(mode="json"),
            expires_at,
            [],
        )
        return comparison

    def get_session(self, session_id: str) -> RouteSessionResponse:
        record = self.database.get_session(session_id)
        if not record:
            raise RouteSessionNotFoundError(session_id)
        return RouteSessionResponse(
            session_id=session_id, graph_revision=record["graph_revision"], created_at=record["created_at"], updated_at=record["updated_at"], expires_at=record["expires_at"],
            request=RouteRequest(**record["request"]), route=RouteResult(**record["route"]) if record["route"] else None,
            comparison=RouteComparison(**record["comparison"]) if record["comparison"] else None,
            temporary_blocked_edge_ids=record["temporary_blocked_edge_ids"],
        )

    def reroute_session(
        self,
        session_id: str,
        update: SessionRerouteRequest,
    ) -> SessionRerouteResponse:
        with self._lock:
            record = self.database.get_session(session_id)
            if not record:
                raise RouteSessionNotFoundError(session_id)
            if self.engine.scope:
                return reroute_poc(self, record, update)
            for edge_id in update.temporary_blocked_edge_ids:
                self.store.get_edge(edge_id)

            combined_blocks = list(
                dict.fromkeys(
                    [
                        *record["temporary_blocked_edge_ids"],
                        *update.temporary_blocked_edge_ids,
                    ]
                )
            )
            previous = RouteResult(**record["route"]) if record.get("route") else None
            request = RouteRequest(**record["request"])
            expires_at = utc_now() + self.SESSION_TTL
            comparison: RouteComparison | None = None
            recalculated: RouteResult | None = None
            status = "recalculated"
            try:
                if record.get("comparison") is not None:
                    comparison = self._comparison(
                        request,
                        session_id,
                        expires_at,
                        set(combined_blocks),
                    )
                    recalculated = comparison.accessible
                else:
                    recalculated = self._decorate_route(
                        self.engine.find_accessible_route(request, set(combined_blocks)),
                        session_id,
                        expires_at,
                    )
            except RouteNotFoundError:
                status = "no_accessible_route"

            self.database.save_session(
                session_id,
                record["request"],
                recalculated.model_dump(mode="json") if recalculated else None,
                comparison.model_dump(mode="json") if comparison else None,
                expires_at,
                combined_blocks,
            )
            route_affected = bool(
                previous
                and set(update.temporary_blocked_edge_ids).intersection(previous.edge_ids)
            )
            route_changed = bool(
                previous
                and (recalculated is None or previous.edge_ids != recalculated.edge_ids)
            )
            return SessionRerouteResponse(
                status=status,
                session_id=session_id,
                temporary_blocked_edge_ids=combined_blocks,
                graph_revision=self.database.graph_revision,
                route_affected=route_affected,
                route_changed=route_changed,
                previous_route=previous,
                recalculated_route=recalculated,
                comparison=comparison,
                warnings=[
                    f"{len(combined_blocks)}개 구간을 현재 세션에서만 임시 제외했습니다.",
                    "이 입력은 공용 Graph나 다른 사용자 경로를 변경하지 않습니다.",
                    f"입력 사유: {update.reason}",
                ],
            )

    def update_edge_and_recalculate(self, edge_id: str, update: EdgeStatusUpdate, observation_id: str | None = None) -> EdgeStatusResponse:
        with self._lock:
            before = self.store.get_edge(edge_id)
            updated_edge = self.store.update_edge_status(edge_id, update)
            revision = self.database.record_edge_change(edge_id, before, updated_edge, update.actor, update.status_source, observation_id)
            caller_previous: RouteResult | None = None
            caller_recalculated: RouteResult | None = None
            caller_status = "not_requested"
            route_affected = False
            route_changed = False
            affected_count = 0

            for record in self.database.active_sessions():
                old_route_data = record.get("route")
                old_route = RouteResult(**old_route_data) if old_route_data else None
                should_recalculate = not update.blocked or bool(old_route and edge_id in old_route.edge_ids)
                if not should_recalculate:
                    continue
                affected_count += 1
                request = RouteRequest(**record["request"])
                expires_at = utc_now() + self.SESSION_TTL
                comparison = None
                new_route = None
                status = "recalculated"
                try:
                    if record.get("comparison") is not None:
                        comparison = self._comparison(
                            request,
                            record["session_id"],
                            expires_at,
                            set(record["temporary_blocked_edge_ids"]),
                        )
                        new_route = comparison.accessible
                    else:
                        new_route = self._decorate_route(
                            self.engine.find_accessible_route(
                                request,
                                set(record["temporary_blocked_edge_ids"]),
                            ),
                            record["session_id"],
                            expires_at,
                        )
                except RouteNotFoundError:
                    status = "no_accessible_route"
                self.database.save_session(
                    record["session_id"],
                    record["request"],
                    new_route.model_dump(mode="json") if new_route else None,
                    comparison.model_dump(mode="json") if comparison else None,
                    expires_at,
                    record["temporary_blocked_edge_ids"],
                )
                if record["session_id"] == update.session_id:
                    caller_previous, caller_recalculated, caller_status = old_route, new_route, status
                    route_affected = bool(old_route and edge_id in old_route.edge_ids)
                    route_changed = bool(old_route and (new_route is None or old_route.edge_ids != new_route.edge_ids))

            return EdgeStatusResponse(
                edge=updated_edge, graph_revision=revision, affected_session_count=affected_count,
                route_affected=route_affected, route_recalculated=caller_status != "not_requested", route_changed=route_changed,
                recalculation_status=caller_status, previous_route=caller_previous, recalculated_route=caller_recalculated,
            )

    def list_candidates(self, status: str | None = None) -> list[ObservationCandidate]:
        return [ObservationCandidate(**item) for item in self.database.list_candidates(status)]

    def create_candidate(self, payload: ObservationCandidateCreate) -> ObservationCandidate:
        self.store.get_edge(payload.edge_id)
        if payload.session_id and not self.database.get_session(payload.session_id):
            raise RouteSessionNotFoundError(payload.session_id)
        observed_at = payload.observed_at or utc_now()
        candidate = {
            "candidate_id": f"MOB_{uuid4().hex[:12].upper()}",
            "edge_id": payload.edge_id,
            "type": payload.type,
            "source": payload.source,
            "confidence": None,
            "status": "pending",
            "verified": False,
            "session_id": payload.session_id,
            "observed_at": observed_at.isoformat(),
            "evidence_date": observed_at.date().isoformat(),
            "ai_result": None,
            "ai_note": payload.note,
            "lat": payload.lat,
            "lon": payload.lon,
        }
        return ObservationCandidate(**self.database.create_candidate(candidate))

    def get_candidate(self, candidate_id: str) -> ObservationCandidate:
        item = self.database.get_candidate(candidate_id)
        if not item:
            raise ObservationCandidateNotFoundError(candidate_id)
        return ObservationCandidate(**item)

    def review_candidate(self, candidate_id: str, review: ObservationReviewRequest) -> ObservationReviewResponse:
        candidate = self.get_candidate(candidate_id)
        review_data = review.model_dump(mode="json")
        reviewed = self.database.review_candidate(candidate_id, review_data)
        graph_updated = False
        edge = None
        if review.decision == "approved":
            measurement_values = {key: value for key, value in review.measurements.items() if key in {"slope", "width", "curb_height", "stairs", "wheelchair_accessible"}}
            blocking_candidate_types = {
                "construction_block_candidate",
                "blocked_path",
                "construction",
                "high_curb",
                "stairs",
                "elevator_outage",
                "temporary_closure",
            }
            should_apply_status = candidate.type in blocking_candidate_types or review.result == "verified_pass" or bool(measurement_values)
            if should_apply_status:
                if review.result == "verified_block" and candidate.type in blocking_candidate_types:
                    update_result = self.update_edge_and_recalculate(candidate.edge_id, EdgeStatusUpdate(blocked=True, reason=review.reason, status_source="approved_observation", verified=True, actor=review.reviewer), candidate_id)
                    edge = update_result.edge
                elif review.result == "verified_pass":
                    update_result = self.update_edge_and_recalculate(candidate.edge_id, EdgeStatusUpdate(blocked=False, status_source="approved_observation", verified=True, actor=review.reviewer), candidate_id)
                    edge = update_result.edge
                else:
                    edge = self.store.get_edge(candidate.edge_id)
                if measurement_values:
                    before = self.store.get_edge(candidate.edge_id)
                    measurement_values.update(accessibility_source="manual", verified=True)
                    edge = self.store.apply_edge_overlay(candidate.edge_id, measurement_values)
                    self.database.record_edge_change(candidate.edge_id, before, edge, review.reviewer, "approved_observation", candidate_id)
                graph_updated = True
        return ObservationReviewResponse(candidate=ObservationCandidate(**reviewed), graph_updated=graph_updated, graph_revision=self.database.graph_revision, edge=edge)
