import copy
import json
from pathlib import Path

import pytest

from scripts.run_m1_vis_comparison import (
    M1VisError,
    build_report,
    deterministic_report,
    load_fixture,
    main,
    parse_fixture,
)


FIXTURE_DIRECTORY = Path(__file__).parent / "fixtures" / "m1_vis"
FIXTURE_PATH = FIXTURE_DIRECTORY / "synthetic_static_frame.json"
EXPECTED_PATH = FIXTURE_DIRECTORY / "synthetic_static_frame_expected.json"


def fixture_payload() -> dict:
    return json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))


def report_from(payload: dict, processing_millis: float = 0.0) -> dict:
    return build_report(
        parse_fixture(payload, FIXTURE_DIRECTORY),
        processing_millis=processing_millis,
    )


def test_golden_fixture_improves_mask_alignment_without_changing_guidance() -> None:
    report = build_report(load_fixture(FIXTURE_PATH))

    assert deterministic_report(report) == deterministic_report(
        json.loads(EXPECTED_PATH.read_text(encoding="utf-8"))
    )
    assert report["gate"] == {
        "status": "APPLIED_SHADOW",
        "fallback_reason": None,
        "mask_age_ms": 150,
        "mask_confidence": 0.91,
    }
    assert report["guidance"]["active_variant"] == "GEOMETRY_BASELINE"
    assert report["guidance"]["user_visible_variant"] == "GEOMETRY_BASELINE"
    assert report["delta"]["inside_sidewalk_ratio"] > 0
    assert report["delta"]["mean_sidewalk_center_offset_px"] < 0
    assert report["safety"]["route_mutated"] is False
    assert report["safety"]["graph_mutated"] is False


@pytest.mark.parametrize(
    ("change", "expected_reason"),
    [
        (lambda payload: payload["mask"].update(confidence=0.699), "LOW_CONFIDENCE"),
        (
            lambda payload: payload["frame"].update(evaluation_timestamp_ms=1301),
            "MASK_STALE",
        ),
        (lambda payload: payload["mask"].update(frame_id="frame-other"), "FRAME_MISMATCH"),
        (
            lambda payload: payload["mask"].update(timestamp_ms=1200),
            "TIMESTAMP_INVALID",
        ),
        (lambda payload: payload["mask"].update(height_px=13), "DIMENSION_MISMATCH"),
        (lambda payload: payload["mask"].update(sidewalk_rows=[]), "EMPTY_SIDEWALK"),
    ],
)
def test_invalid_masks_fall_back_to_identical_a_ribbon(change, expected_reason) -> None:
    payload = fixture_payload()
    change(payload)

    report = report_from(payload)

    assert report["gate"]["status"] == "FALLBACK_A"
    assert report["gate"]["fallback_reason"] == expected_reason
    assert report["variants"]["b"]["applied"] is False
    assert report["variants"]["b"]["centerline_px"] == report["variants"]["a"]["centerline_px"]
    assert report["guidance"]["active_variant"] == "GEOMETRY_BASELINE"


def test_missing_mask_falls_back_to_a() -> None:
    payload = fixture_payload()
    payload["mask"] = None

    report = report_from(payload)

    assert report["gate"]["fallback_reason"] == "MASK_MISSING"
    assert report["gate"]["mask_age_ms"] is None
    assert report["variants"]["a"]["centerline_px"] == report["variants"]["b"]["centerline_px"]


def test_confidence_and_age_boundaries_are_inclusive() -> None:
    payload = fixture_payload()
    payload["mask"]["confidence"] = 0.70
    payload["frame"]["evaluation_timestamp_ms"] = 1300

    report = report_from(payload)

    assert report["gate"]["status"] == "APPLIED_SHADOW"
    assert report["gate"]["mask_age_ms"] == 300


def test_lateral_correction_never_exceeds_policy_limit() -> None:
    payload = fixture_payload()
    payload["policy"]["max_lateral_shift_px"] = 1.25

    report = report_from(payload)

    assert report["variants"]["b"]["max_lateral_shift_px"] == 1.25
    assert report["variants"]["b"]["mean_lateral_shift_px"] == 1.25


def test_report_repeatability_excludes_only_processing_time() -> None:
    first = report_from(fixture_payload(), processing_millis=1.25)
    second = report_from(fixture_payload(), processing_millis=99.0)

    assert deterministic_report(first) == deterministic_report(second)
    assert first["runtime"]["processing_ms"] != second["runtime"]["processing_ms"]


def test_cli_writes_json_and_side_by_side_svg(tmp_path: Path) -> None:
    report_path = tmp_path / "report.json"
    svg_path = tmp_path / "comparison.svg"

    assert main(
        [
            "--input",
            str(FIXTURE_PATH),
            "--report",
            str(report_path),
            "--svg",
            str(svg_path),
        ]
    ) == 0

    report = json.loads(report_path.read_text(encoding="utf-8"))
    svg = svg_path.read_text(encoding="utf-8")
    assert report["schema_version"] == "m1-vis-report-v1"
    assert "A · BASELINE" in svg
    assert "B · SHADOW" in svg


def test_fixture_rejects_points_outside_the_frame() -> None:
    payload = fixture_payload()
    payload["baseline"]["centerline"][0]["x"] = 16

    with pytest.raises(M1VisError, match="inside the frame"):
        parse_fixture(payload, FIXTURE_DIRECTORY)
