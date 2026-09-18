"""Session-local AI avoidance. Never updates graph overlays or human review records."""
from __future__ import annotations
from datetime import datetime, timedelta, timezone
import json
import math

from .database import utc_now
from .poc_scope import PocContractError
from .routing import RouteNotFoundError, haversine_m
from .schemas import Coordinate, RouteRequest, RouteResult, SessionRerouteRequest, SessionRerouteResponse


def aware(value: datetime) -> datetime:
    if value.tzinfo is None:
        raise PocContractError("timezone_required", status_code=422)
    return value.astimezone(timezone.utc)


def check_observation_order(service, session_id, items, conflict):
    """Keep observation order durable across event IDs and server restarts.

    A new frame can renew an existing track. The same frame cannot renew its TTL,
    and an older frame cannot replace a newer avoidance or clearance.
    """
    history = []
    for row in service.database.connection.execute(
        "SELECT request_json FROM poc_events WHERE session_id=?", (session_id,)
    ).fetchall():
        request = json.loads(row["request_json"])
        history.extend(request.get("avoidance_upserts", []))
        history.extend(request.get("avoidance_clearances", []))
    for item in items:
        observed_at = aware(item.observed_at)
        for prior in history:
            if prior["source"] != item.source:
                continue
            if prior["observation_id"] == item.observation_id and prior["frame_id"] == item.frame_id:
                raise conflict("duplicate_observation")
            if (prior["edge_id"] == item.edge_id or prior["observation_id"] == item.observation_id):
                if observed_at <= aware(datetime.fromisoformat(prior["observed_at"])):
                    raise conflict("observation_out_of_order")
        history.append(item.model_dump(mode="json"))


def spatial_evidence(item, position, scope, store, *, clearance=False):
    evidence = item.evidence
    try:
        persistence = float(evidence.get("persistence_ms", 0))
        confidence = float(evidence.get("confidence", 0))
        error = float(evidence["accuracy_m"])
        numeric_valid = all(math.isfinite(v) for v in (persistence, confidence, error)) and (
            persistence >= 2000 and 0.6 <= confidence <= 1 and 0 < error <= 1.5
        )
    except (KeyError, ValueError, TypeError):
        numeric_valid = False
    conditions = (
        evidence.get("corridor_clear") is True and evidence.get("depth_valid") is True
        and evidence.get("semantics_valid") is True
    ) if clearance else (evidence.get("stationary") is True and evidence.get("corridor_occupied") is True)
    if not (numeric_valid and conditions and
            evidence.get("calibration_revision") == position.calibration_revision and evidence.get("model_revision")):
        raise PocContractError("insufficient_clearance_evidence" if clearance else "insufficient_spatial_evidence", status_code=422)
    try:
        point = Coordinate.model_validate(evidence["observed_position" if clearance else "object_position"])
        matched, _ = scope.match(store, point, error)
    except (KeyError, ValueError, TypeError) as exc:
        raise PocContractError("clearance_position_ambiguous" if clearance else "object_position_ambiguous", status_code=422) from exc
    if matched != item.edge_id:
        raise PocContractError("edge_impact_mismatch", status_code=422)
    return point, error


def reroute_poc(service, record, update):
    scope=service.engine.scope
    session_id=record["session_id"]
    if not update.event_id:
        raise PocContractError("event_id_required",status_code=422)
    state=service.database.poc_state(session_id)
    def conflict(reason):
        return PocContractError(reason, current_route_revision=state["route_revision"],
                                current_graph_revision=service.database.graph_revision)
    canonical=json.dumps(update.model_dump(mode="json"),sort_keys=True,separators=(",",":"))
    cached=service.database.poc_event(session_id,update.event_id)
    if cached:
        prior_request=json.dumps(SessionRerouteRequest.model_validate_json(cached["request_json"]).model_dump(mode="json"),sort_keys=True,separators=(",",":"))
        if prior_request != canonical:
            raise conflict("event_id_payload_conflict")
        return SessionRerouteResponse.model_validate_json(cached["response_json"])
    for key,value in scope.revisions().items():
        if getattr(update,key) != value:
            raise conflict(f"{key}_mismatch")
    if update.graph_revision != service.database.graph_revision:
        raise conflict("graph_revision_mismatch")
    if update.expected_route_revision != state["route_revision"]:
        raise conflict("stale_route_revision")
    if update.temporary_blocked_edge_ids:
        raise PocContractError("legacy_block_input_not_allowed",status_code=422)
    position=update.current_position
    if position is None:
        raise PocContractError("current_position_required",status_code=422)
    now=utc_now()
    if not -1 <= (now-aware(position.timestamp)).total_seconds() <= 5:
        raise PocContractError("position_stale",status_code=422)
    scope.match(service.store,position,position.accuracy_m,update.progress_edge_id)
    check_observation_order(service,session_id,[*update.avoidance_upserts,*update.avoidance_clearances],conflict)
    previous=RouteResult.model_validate(record["route"]) if record["route"] else None
    avoidances=state["avoidances"]
    for item in avoidances.values():
        if item["status"] == "active" and datetime.fromisoformat(item["expires_at"]) <= now:
            item["status"]="stale_unconfirmed" # Still excluded: expiry is not clearance.
    for item in update.avoidance_upserts:
        if item.edge_id not in scope.allowed:
            raise PocContractError("edge_outside_poc",status_code=422)
        if item.edge_id in avoidances and avoidances[item.edge_id]["source"] != item.source:
            raise conflict("observation_source_mismatch")
        observed_at=aware(item.observed_at)
        if item.source == "live_ai" and not -1 <= (now-observed_at).total_seconds() <= 5:
            raise PocContractError("observation_stale",status_code=422)
        if item.source != "contract_test":
            spatial_evidence(item,position,scope,service.store)
            if previous and item.edge_id not in previous.edge_ids and item.edge_id not in avoidances:
                raise PocContractError("edge_impact_mismatch",status_code=422)
        avoidances[item.edge_id]={**item.model_dump(mode="json"),"event_id":update.event_id,"status":"active",
            "expires_at":(now+timedelta(seconds=item.ttl_seconds)).isoformat(),"human_reviewed":False,"field_verified":False}
    clearances={item.edge_id:item for item in update.avoidance_clearances}
    if set(update.avoidance_removes)-set(clearances) or (update.clearance_observation_id and not clearances):
        raise PocContractError("clearance_evidence_required",status_code=422)
    for edge_id,item in clearances.items():
        if edge_id not in scope.allowed or (update.clearance_observation_id and update.clearance_observation_id != item.observation_id):
            raise PocContractError("clearance_evidence_required",status_code=422)
        prior=avoidances.get(edge_id)
        if not prior or prior["status"] == "removed":
            raise conflict("avoidance_not_active")
        observed_at=aware(item.observed_at)
        if item.source != prior["source"]:
            raise conflict("clearance_source_mismatch")
        if item.source == "live_ai" and not -1 <= (now-observed_at).total_seconds() <= 5:
            raise PocContractError("observation_stale",status_code=422)
        if item.source != "contract_test":
            if observed_at <= aware(datetime.fromisoformat(prior["observed_at"])):
                raise conflict("clearance_observation_mismatch")
            point,error=spatial_evidence(item,position,scope,service.store,clearance=True)
            try:
                obstacle=Coordinate.model_validate(prior["evidence"]["object_position"])
                obstacle_error=float(prior["evidence"]["accuracy_m"])
            except (KeyError,ValueError,TypeError) as exc:
                raise PocContractError("clearance_reference_missing",status_code=422) from exc
            if haversine_m(point,obstacle) > error+obstacle_error+scope.data["map_error_m"]:
                raise PocContractError("clearance_does_not_cover_obstacle",status_code=422)
        avoidances[edge_id]={**prior,"status":"removed","clearance_observation_id":item.observation_id,
                            "clearance":item.model_dump(mode="json"),"human_reviewed":False,"field_verified":False}
    blocks={e for e,a in avoidances.items() if a["status"] != "removed"}
    request=RouteRequest.model_validate({**record["request"],"origin":{"lat":position.lat,"lon":position.lon}})
    expires=now+service.SESSION_TTL
    revision=state["route_revision"]+1
    route=None
    blocked_positions={}
    for edge_id,avoidance in avoidances.items():
        evidence=avoidance.get("evidence",{})
        try:
            error=float(evidence.get("accuracy_m",99))
            if edge_id in blocks and evidence.get("object_position") and 0 < error <= 1.5:
                blocked_positions[edge_id]=(Coordinate.model_validate(evidence["object_position"]),error+scope.data["map_error_m"])
        except (ValueError,TypeError):
            pass # A contract-only unmeasured block excludes the whole physical edge.
    try:
        route=service._decorate_route(service.engine.find_accessible_route(request,blocks,blocked_positions=blocked_positions),session_id,expires).model_copy(update={"route_revision":revision})
    except RouteNotFoundError:
        pass
    response=SessionRerouteResponse(status="recalculated" if route else "no_accessible_route", session_id=session_id,
        temporary_blocked_edge_ids=sorted(blocks),graph_revision=service.database.graph_revision,
        route_affected=bool(previous and set(previous.edge_ids)&blocks),
        route_changed=previous is None or route is None or previous.edge_ids!=route.edge_ids,
        previous_route=previous,recalculated_route=route,event_id=update.event_id,route_revision=revision,
        reason_code=None if route else "no_route_within_poc",avoidances=list(avoidances.values()),
        warnings=["접근성 미확인 시연 프로필 · 현장 통행 가능성 검증 아님", "TTL 만료 구간은 재확인 전 계속 제외됩니다."])
    service.database.commit_poc_event(session_id,update.event_id,canonical,response.model_dump(mode="json"),
        request.model_dump(mode="json",exclude={"session_id"}),route.model_dump(mode="json") if route else None,
        {"route_revision":revision,"avoidances":avoidances},expires)
    return response
