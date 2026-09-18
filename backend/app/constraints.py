from __future__ import annotations

from typing import Any

from .schemas import AccessibilityProfile


REASON_LABELS = {
    "blocked": "통행 차단",
    "construction": "공사 구간",
    "temporary_closure": "임시 통행 불가",
    "stairs": "계단",
    "steep_slope": "설정값보다 급한 경사",
    "narrow_width": "설정값보다 좁은 통행 폭",
    "high_curb": "설정값보다 높은 턱",
    "elevator_unavailable": "엘리베이터 이용 불가",
    "unknown_accessibility": "접근성 정보 미확인",
    "stale_accessibility": "접근성 정보 재확인 필요",
    "wheelchair_not_accessible": "휠체어 통행 불가 표시",
}


def edge_constraint_reasons(
    edge: dict[str, Any], profile: AccessibilityProfile
) -> list[str]:
    """Return explicit hard-constraint reason codes for one edge."""

    reasons: list[str] = []
    if (edge.get("source") == "synthetic" or edge.get("accessibility_source") == "synthetic") and not profile.allow_synthetic:
        reasons.append("synthetic_not_allowed")
    tags = edge.get("raw_accessibility_tags") or {}
    def values(value):
        return value if isinstance(value, list) else [value]
    if any(str(value).lower() in {"no", "private"} for key in ("foot", "access") for value in values(tags.get(key))):
        reasons.append("access_denied")

    if bool(edge.get("blocked")):
        reasons.append("blocked")
        block_reason = str(edge.get("block_reason") or "").strip()
        if block_reason:
            reasons.append(block_reason)

    status = edge.get("accessibility_status")
    synthetic_experiment = (
        (edge.get("source") == "synthetic" or edge.get("accessibility_source") == "synthetic")
        and profile.allow_synthetic
    )
    source_allowed = str(edge.get("source")) in profile.allowed_unverified_sources
    if status == "unknown" and not profile.allow_unknown and not synthetic_experiment and not source_allowed:
        reasons.append("unknown_accessibility")
    if status == "stale" and not profile.allow_unknown:
        reasons.append("stale_accessibility")

    if bool(edge.get("stairs")) and not profile.allow_stairs:
        reasons.append("stairs")

    slope = edge.get("slope")
    if profile.max_slope is not None and slope is not None:
        if abs(float(slope)) > profile.max_slope:
            reasons.append("steep_slope")

    width = edge.get("width")
    if profile.min_width is not None and width is not None:
        if float(width) < profile.min_width:
            reasons.append("narrow_width")

    curb_height = edge.get("curb_height")
    if profile.max_curb_height is not None and curb_height is not None:
        if float(curb_height) > profile.max_curb_height:
            reasons.append("high_curb")

    if bool(edge.get("elevator_required")):
        if edge.get("elevator_status") != "available":
            reasons.append("elevator_unavailable")

    specific_wheelchair_reasons = {
        "stairs",
        "steep_slope",
        "narrow_width",
        "high_curb",
        "elevator_unavailable",
    }
    if (
        (profile.requires_wheelchair_access or profile.name == "wheelchair")
        and edge.get("wheelchair_accessible") is False
        and not specific_wheelchair_reasons.intersection(reasons)
    ):
        reasons.append("wheelchair_not_accessible")

    return list(dict.fromkeys(reasons))


def reason_label(reason: str) -> str:
    return REASON_LABELS.get(reason, reason.replace("_", " "))
