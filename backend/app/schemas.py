from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any, Literal

from pydantic import BaseModel, Field, field_validator, model_validator


class AccessibilityStatus(str, Enum):
    VERIFIED_PASS = "verified_pass"
    VERIFIED_BLOCK = "verified_block"
    CANDIDATE = "candidate"
    UNKNOWN = "unknown"
    STALE = "stale"


class Coordinate(BaseModel):
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)


class AccessibilityProfile(BaseModel):
    name: Literal["default", "wheelchair", "demo_jeonju"]
    description: str
    max_slope: float | None = None
    min_width: float | None = None
    max_curb_height: float | None = None
    allow_stairs: bool = True
    allow_unknown: bool = True
    allow_synthetic: bool = False
    requires_wheelchair_access: bool = False
    allowed_unverified_sources: list[str] = Field(default_factory=list)


class RouteRequest(BaseModel):
    origin: Coordinate
    destination: Coordinate
    profile: Literal["default", "wheelchair", "demo_jeonju"] = "wheelchair"
    session_id: str | None = Field(default=None, min_length=8, max_length=80)


class ExcludedEdge(BaseModel):
    edge_id: str
    name: str
    reasons: list[str]


class ProvenanceSummary(BaseModel):
    sources: list[str]
    accessibility_sources: list[str] = Field(default_factory=list)
    contains_synthetic: bool
    verified_edges: int
    unverified_edges: int


class RouteResult(BaseModel):
    status: Literal["ok"] = "ok"
    distance_m: float
    estimated_minutes: int
    route_type: Literal["standard", "accessible"]
    profile: Literal["default", "wheelchair", "demo_jeonju"]
    origin_node: str
    destination_node: str
    node_ids: list[str]
    edge_ids: list[str]
    geometry: list[list[float]]
    excluded_edges: list[ExcludedEdge] = Field(default_factory=list)
    reasons: list[str] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
    provenance: ProvenanceSummary
    session_id: str | None = None
    graph_revision: int | None = None
    expires_at: datetime | None = None
    route_revision: int = 1
    scope_revision: str | None = None
    dataset_revision: str | None = None
    graph_sha256: str | None = None
    region_id: str | None = None
    calculated_origin: Coordinate | None = None
    segments: list[dict[str, Any]] = Field(default_factory=list)


class RouteComparison(BaseModel):
    status: Literal["ok"] = "ok"
    standard: RouteResult
    accessible: RouteResult
    difference_m: float
    difference_pct: float
    reasons: list[str]
    warnings: list[str] = Field(default_factory=list)
    session_id: str | None = None
    graph_revision: int | None = None
    expires_at: datetime | None = None


class CurrentPosition(Coordinate):
    accuracy_m: float = Field(gt=0, le=3)
    timestamp: datetime
    calibration_revision: str = Field(min_length=1)


class AvoidanceUpdate(BaseModel):
    edge_id: str = Field(min_length=1, max_length=160)
    observation_id: str = Field(min_length=1, max_length=160)
    frame_id: str = Field(min_length=1, max_length=160)
    observed_at: datetime
    ttl_seconds: int = Field(default=60, ge=5, le=300)
    source: Literal["live_ai", "replay_ai", "contract_test"]
    evidence: dict[str, Any] = Field(default_factory=dict)


class AvoidanceClearance(BaseModel):
    """A new, sustained free-corridor observation; an opaque ID alone is not clearance."""
    edge_id: str = Field(min_length=1, max_length=160)
    observation_id: str = Field(min_length=1, max_length=160)
    frame_id: str = Field(min_length=1, max_length=160)
    observed_at: datetime
    source: Literal["live_ai", "replay_ai", "contract_test"]
    evidence: dict[str, Any] = Field(default_factory=dict)


class SessionRerouteRequest(BaseModel):
    temporary_blocked_edge_ids: list[str] = Field(default_factory=list, max_length=20)
    reason: str = Field(min_length=3, max_length=120)
    event_id: str | None = Field(default=None, min_length=8, max_length=160)
    region_id: str | None = None
    dataset_revision: str | None = None
    scope_revision: str | None = None
    graph_sha256: str | None = None
    graph_revision: int | None = None
    expected_route_revision: int | None = None
    current_position: CurrentPosition | None = None
    progress_edge_id: str | None = None
    avoidance_upserts: list[AvoidanceUpdate] = Field(default_factory=list, max_length=20)
    avoidance_removes: list[str] = Field(default_factory=list, max_length=20)
    clearance_observation_id: str | None = None
    avoidance_clearances: list[AvoidanceClearance] = Field(default_factory=list, max_length=20)

    @model_validator(mode="after")
    def validate_avoidance_operations(self):
        upserts = [item.edge_id for item in self.avoidance_upserts]
        clearances = [item.edge_id for item in self.avoidance_clearances]
        if len(set(upserts)) != len(upserts) or len(set(clearances)) != len(clearances):
            raise ValueError("one observation per edge is allowed in each event")
        if set(upserts) & (set(clearances) | set(self.avoidance_removes)):
            raise ValueError("an event cannot block and clear the same edge")
        return self

    @field_validator("temporary_blocked_edge_ids")
    @classmethod
    def validate_edge_ids(cls, values: list[str]) -> list[str]:
        normalized = [value.strip() for value in values]
        if any(not value for value in normalized):
            raise ValueError("edge_id는 비어 있을 수 없습니다.")
        return list(dict.fromkeys(normalized))


class SessionRerouteResponse(BaseModel):
    status: Literal["recalculated", "no_accessible_route"]
    session_id: str
    temporary_blocked_edge_ids: list[str]
    graph_revision: int
    route_affected: bool
    route_changed: bool
    previous_route: RouteResult | None = None
    recalculated_route: RouteResult | None = None
    comparison: RouteComparison | None = None
    warnings: list[str] = Field(default_factory=list)
    event_id: str | None = None
    route_revision: int = 1
    reason_code: str | None = None
    avoidances: list[dict[str, Any]] = Field(default_factory=list)


class EdgeStatusUpdate(BaseModel):
    blocked: bool
    reason: str | None = Field(default=None, max_length=120)
    status_source: Literal["manual", "public_data", "approved_observation"] = "manual"
    verified: bool = False
    actor: str | None = Field(default=None, max_length=80)
    session_id: str | None = Field(default=None, min_length=8, max_length=80)

    @model_validator(mode="after")
    def validate_update(self) -> "EdgeStatusUpdate":
        if self.blocked and not (self.reason and self.reason.strip()):
            raise ValueError("차단 상태에는 reason이 필요합니다.")
        if not self.blocked:
            self.reason = None
        if self.verified and not (self.actor and self.actor.strip()):
            raise ValueError("verified 상태에는 확인자 actor가 필요합니다.")
        return self


class EdgeStatusResponse(BaseModel):
    status: Literal["updated"] = "updated"
    edge: dict[str, Any]
    graph_revision: int
    affected_session_count: int = 0
    route_affected: bool
    route_recalculated: bool
    route_changed: bool
    recalculation_status: Literal["not_requested", "recalculated", "no_accessible_route"]
    previous_route: RouteResult | None = None
    recalculated_route: RouteResult | None = None


class ObservationCandidate(BaseModel):
    candidate_id: str
    edge_id: str
    approach_id: str | None = None
    sample_id: str | None = None
    mapping_status: str | None = None
    type: str
    source: str
    confidence: float | None = Field(default=None, ge=0, le=1)
    status: Literal["pending", "approved", "rejected", "needs_more_evidence"] = "pending"
    verified: bool = False
    session_id: str | None = None
    observed_at: datetime | None = None
    ai_result: str | None = None
    ai_note: str | None = None
    evidence_date: str | None = None
    evidence_url: str | None = None
    lat: float | None = None
    lon: float | None = None
    created_at: datetime | None = None
    updated_at: datetime | None = None
    latest_review: dict[str, Any] | None = None


class ObservationCandidateCreate(BaseModel):
    edge_id: str = Field(min_length=1, max_length=160)
    type: Literal[
        "blocked_path",
        "construction",
        "high_curb",
        "stairs",
        "elevator_outage",
        "temporary_closure",
    ] = "blocked_path"
    source: Literal["manual_camera", "manual"] = "manual_camera"
    session_id: str | None = Field(default=None, min_length=8, max_length=80)
    observed_at: datetime | None = None
    note: str | None = Field(default=None, max_length=300)
    lat: float | None = Field(default=None, ge=-90, le=90)
    lon: float | None = Field(default=None, ge=-180, le=180)

    @model_validator(mode="after")
    def validate_location(self) -> "ObservationCandidateCreate":
        if (self.lat is None) != (self.lon is None):
            raise ValueError("lat와 lon은 함께 입력해야 합니다.")
        return self


class ObservationReviewRequest(BaseModel):
    decision: Literal["approved", "rejected", "needs_more_evidence"]
    result: Literal["verified_pass", "verified_block"] | None = None
    reviewer: str = Field(min_length=2, max_length=80)
    observed_at: datetime
    reason: str = Field(min_length=3, max_length=300)
    measurements: dict[str, float | str | bool | None] = Field(default_factory=dict)

    @model_validator(mode="after")
    def validate_approval(self) -> "ObservationReviewRequest":
        if self.decision == "approved" and self.result is None:
            raise ValueError("승인에는 verified_pass 또는 verified_block 결과가 필요합니다.")
        return self


class ObservationReviewResponse(BaseModel):
    status: Literal["reviewed"] = "reviewed"
    candidate: ObservationCandidate
    graph_updated: bool
    graph_revision: int
    edge: dict[str, Any] | None = None


class RouteSessionResponse(BaseModel):
    session_id: str
    graph_revision: int
    created_at: datetime
    updated_at: datetime
    expires_at: datetime
    request: RouteRequest
    route: RouteResult | None = None
    comparison: RouteComparison | None = None
    temporary_blocked_edge_ids: list[str] = Field(default_factory=list)


class GraphEnrichmentCandidate(BaseModel):
    candidate_id: str
    edge_id: str
    type: str
    source: str
    confidence: float | None = Field(default=None, ge=0, le=1)
    status: Literal["pending"] = "pending"
    verified: Literal[False] = False
    graph_update_allowed: Literal[False] = False
    requires_human_review: Literal[True] = True
    priority: Literal["high", "medium", "low"]
    routing_impact: str
    mapping_status: str
    mapping_quality: str
    candidate_class: Literal[
        "routing_attribute", "diagnostic_sensitivity", "evidence_only"
    ] = "evidence_only"
    simulation_allowed: bool = False
    approval_eligible: bool = True
    quality_flags: list[str] = Field(default_factory=list)
    proposed_changes: dict[str, Any] = Field(default_factory=dict)
    current_values: dict[str, Any] = Field(default_factory=dict)
    evidence_count: int = Field(ge=1)
    source_types: list[str] = Field(default_factory=list)
    evidence: list[dict[str, Any]] = Field(default_factory=list)
    visual_evidence_refs: list[dict[str, Any]] = Field(default_factory=list)
    lat: float | None = Field(default=None, ge=-90, le=90)
    lon: float | None = Field(default=None, ge=-180, le=180)
    created_at: datetime


class GraphEnrichmentSummary(BaseModel):
    available: bool
    schema_version: str | None = None
    created_at: datetime | None = None
    baseline_graph_sha256: str | None = None
    baseline_matches_graph: bool
    candidate_count: int
    candidate_edge_count: int
    route_affecting_candidate_count: int
    evidence_only_candidate_count: int
    diagnostic_candidate_count: int = 0
    approval_eligible_candidate_count: int = 0
    orthophoto_referenced_candidate_count: int = 0
    candidate_counts_by_type: dict[str, int] = Field(default_factory=dict)
    candidate_counts_by_priority: dict[str, int] = Field(default_factory=dict)
    all_pending: bool
    all_unverified: bool
    graph_update_allowed: Literal[False] = False


class GraphEnrichmentCandidateList(BaseModel):
    available: bool
    total: int
    offset: int
    limit: int
    candidates: list[GraphEnrichmentCandidate] = Field(default_factory=list)


class GraphEnrichmentSimulationRequest(BaseModel):
    origin: Coordinate
    destination: Coordinate
    profile: Literal["default", "wheelchair", "demo_jeonju"] = "wheelchair"
    candidate_ids: list[str] = Field(min_length=1, max_length=20)

    @field_validator("candidate_ids")
    @classmethod
    def validate_candidate_ids(cls, values: list[str]) -> list[str]:
        normalized = [value.strip() for value in values]
        if any(not value for value in normalized):
            raise ValueError("candidate_id는 비어 있을 수 없습니다.")
        if len(set(normalized)) != len(normalized):
            raise ValueError("candidate_id는 중복될 수 없습니다.")
        return normalized


class GraphEnrichmentSimulationResponse(BaseModel):
    status: Literal["ok", "no_accessible_route"]
    candidate_ids: list[str]
    applied_edge_ids: list[str]
    baseline_candidate_edge_ids: list[str]
    baseline: RouteResult | None = None
    simulated: RouteResult | None = None
    route_changed: bool
    difference_m: float | None = None
    graph_revision: int
    graph_mutated: Literal[False] = False
    database_mutated: Literal[False] = False
    warnings: list[str] = Field(default_factory=list)
