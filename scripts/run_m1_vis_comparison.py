"""Compare a geometry ribbon with a mask-assisted shadow ribbon on one frame."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import math
import mimetypes
import time
from dataclasses import dataclass
from html import escape
from pathlib import Path
from typing import Any, Iterable


INPUT_SCHEMA_VERSION = "m1-vis-input-v1"
REPORT_SCHEMA_VERSION = "m1-vis-report-v1"
DEFAULT_MIN_MASK_CONFIDENCE = 0.70
DEFAULT_MAX_MASK_AGE_MS = 300


class M1VisError(ValueError):
    """Raised when a static comparison fixture is invalid."""


@dataclass(frozen=True)
class Point:
    x: float
    y: float


@dataclass(frozen=True)
class FrameInput:
    frame_id: str
    timestamp_ms: int
    evaluation_timestamp_ms: int
    width_px: int
    height_px: int
    image_path: Path | None


@dataclass(frozen=True)
class BaselineInput:
    centerline: tuple[Point, ...]
    ribbon_half_width_px: float


@dataclass(frozen=True)
class MaskInput:
    frame_id: str
    timestamp_ms: int
    width_px: int
    height_px: int
    confidence: float
    sidewalk_rows: dict[int, tuple[tuple[int, int], ...]]


@dataclass(frozen=True)
class PolicyInput:
    min_mask_confidence: float
    max_mask_age_ms: int
    max_lateral_shift_px: float


@dataclass(frozen=True)
class ComparisonInput:
    case_id: str
    source: str
    synthetic: bool
    frame: FrameInput
    baseline: BaselineInput
    mask: MaskInput | None
    policy: PolicyInput


def _object(value: Any, field: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise M1VisError(f"{field} must be an object")
    return value


def _array(value: Any, field: str) -> list[Any]:
    if not isinstance(value, list):
        raise M1VisError(f"{field} must be an array")
    return value


def _string(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise M1VisError(f"{field} must be a non-empty string")
    return value.strip()


def _boolean(value: Any, field: str) -> bool:
    if not isinstance(value, bool):
        raise M1VisError(f"{field} must be a boolean")
    return value


def _integer(value: Any, field: str, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise M1VisError(f"{field} must be an integer >= {minimum}")
    return value


def _number(value: Any, field: str, minimum: float | None = None) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise M1VisError(f"{field} must be a number")
    result = float(value)
    if not math.isfinite(result) or (minimum is not None and result < minimum):
        qualifier = f" >= {minimum}" if minimum is not None else ""
        raise M1VisError(f"{field} must be a finite number{qualifier}")
    return result


def _read_json_object(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise M1VisError(f"could not read JSON fixture: {path}") from error
    return _object(value, "root")


def _parse_point(value: Any, field: str, width: int, height: int) -> Point:
    item = _object(value, field)
    x = _number(item.get("x"), f"{field}.x")
    y = _number(item.get("y"), f"{field}.y")
    if not 0 <= x <= width - 1 or not 0 <= y <= height - 1:
        raise M1VisError(f"{field} must lie inside the frame")
    return Point(x=x, y=y)


def _merge_spans(spans: list[tuple[int, int]]) -> tuple[tuple[int, int], ...]:
    merged: list[list[int]] = []
    for start, end in sorted(spans):
        if not merged or start > merged[-1][1] + 1:
            merged.append([start, end])
        else:
            merged[-1][1] = max(merged[-1][1], end)
    return tuple((start, end) for start, end in merged)


def _parse_mask(value: Any, field: str) -> MaskInput | None:
    if value is None:
        return None
    item = _object(value, field)
    width = _integer(item.get("width_px"), f"{field}.width_px", 1)
    height = _integer(item.get("height_px"), f"{field}.height_px", 1)
    confidence = _number(item.get("confidence"), f"{field}.confidence", 0.0)
    if confidence > 1.0:
        raise M1VisError(f"{field}.confidence must be between 0 and 1")
    rows: dict[int, tuple[tuple[int, int], ...]] = {}
    for index, raw_row in enumerate(_array(item.get("sidewalk_rows"), f"{field}.sidewalk_rows")):
        row_field = f"{field}.sidewalk_rows[{index}]"
        row = _object(raw_row, row_field)
        y = _integer(row.get("y"), f"{row_field}.y")
        if y >= height:
            raise M1VisError(f"{row_field}.y must be smaller than mask height")
        if y in rows:
            raise M1VisError(f"duplicate sidewalk row: {y}")
        parsed_spans: list[tuple[int, int]] = []
        for span_index, raw_span in enumerate(_array(row.get("spans"), f"{row_field}.spans")):
            span_field = f"{row_field}.spans[{span_index}]"
            span = _array(raw_span, span_field)
            if len(span) != 2:
                raise M1VisError(f"{span_field} must contain [start_x, end_x]")
            start = _integer(span[0], f"{span_field}[0]")
            end = _integer(span[1], f"{span_field}[1]")
            if start > end or end >= width:
                raise M1VisError(f"{span_field} must be ordered and inside mask width")
            parsed_spans.append((start, end))
        rows[y] = _merge_spans(parsed_spans)
    return MaskInput(
        frame_id=_string(item.get("frame_id"), f"{field}.frame_id"),
        timestamp_ms=_integer(item.get("timestamp_ms"), f"{field}.timestamp_ms"),
        width_px=width,
        height_px=height,
        confidence=confidence,
        sidewalk_rows=rows,
    )


def parse_fixture(payload: dict[str, Any], fixture_directory: Path) -> ComparisonInput:
    if payload.get("schema_version") != INPUT_SCHEMA_VERSION:
        raise M1VisError(f"schema_version must be {INPUT_SCHEMA_VERSION}")
    provenance = _object(payload.get("provenance"), "provenance")
    frame_value = _object(payload.get("frame"), "frame")
    width = _integer(frame_value.get("width_px"), "frame.width_px", 2)
    height = _integer(frame_value.get("height_px"), "frame.height_px", 2)
    image_value = frame_value.get("image_path")
    image_path: Path | None = None
    if image_value is not None:
        relative = Path(_string(image_value, "frame.image_path"))
        if relative.is_absolute():
            image_path = relative.resolve()
        else:
            image_path = (fixture_directory / relative).resolve()
        if not image_path.is_file():
            raise M1VisError(f"frame image does not exist: {image_path}")

    baseline_value = _object(payload.get("baseline"), "baseline")
    points = tuple(
        _parse_point(value, f"baseline.centerline[{index}]", width, height)
        for index, value in enumerate(
            _array(baseline_value.get("centerline"), "baseline.centerline")
        )
    )
    if len(points) < 2:
        raise M1VisError("baseline.centerline must contain at least two points")

    policy_value = _object(payload.get("policy"), "policy")
    min_confidence = _number(
        policy_value.get("min_mask_confidence", DEFAULT_MIN_MASK_CONFIDENCE),
        "policy.min_mask_confidence",
        0.0,
    )
    if min_confidence > 1.0:
        raise M1VisError("policy.min_mask_confidence must be between 0 and 1")

    return ComparisonInput(
        case_id=_string(payload.get("case_id"), "case_id"),
        source=_string(provenance.get("source"), "provenance.source"),
        synthetic=_boolean(provenance.get("synthetic"), "provenance.synthetic"),
        frame=FrameInput(
            frame_id=_string(frame_value.get("frame_id"), "frame.frame_id"),
            timestamp_ms=_integer(frame_value.get("timestamp_ms"), "frame.timestamp_ms"),
            evaluation_timestamp_ms=_integer(
                frame_value.get("evaluation_timestamp_ms"),
                "frame.evaluation_timestamp_ms",
            ),
            width_px=width,
            height_px=height,
            image_path=image_path,
        ),
        baseline=BaselineInput(
            centerline=points,
            ribbon_half_width_px=_number(
                baseline_value.get("ribbon_half_width_px"),
                "baseline.ribbon_half_width_px",
                0.1,
            ),
        ),
        mask=_parse_mask(payload.get("mask"), "mask"),
        policy=PolicyInput(
            min_mask_confidence=min_confidence,
            max_mask_age_ms=_integer(
                policy_value.get("max_mask_age_ms", DEFAULT_MAX_MASK_AGE_MS),
                "policy.max_mask_age_ms",
                0,
            ),
            max_lateral_shift_px=_number(
                policy_value.get("max_lateral_shift_px"),
                "policy.max_lateral_shift_px",
                0.0,
            ),
        ),
    )


def load_fixture(path: Path) -> ComparisonInput:
    resolved = path.resolve()
    return parse_fixture(_read_json_object(resolved), resolved.parent)


def _dense_polyline(points: tuple[Point, ...]) -> tuple[Point, ...]:
    result: list[Point] = []
    for start, end in zip(points, points[1:]):
        steps = max(1, math.ceil(max(abs(end.x - start.x), abs(end.y - start.y))))
        for step in range(steps):
            fraction = step / steps
            point = Point(
                x=start.x + (end.x - start.x) * fraction,
                y=start.y + (end.y - start.y) * fraction,
            )
            if not result or point != result[-1]:
                result.append(point)
    result.append(points[-1])
    return tuple(result)


def _pixel_row(value: float, height: int) -> int:
    return min(height - 1, max(0, int(math.floor(value + 0.5))))


def _nearest_span(x: float, spans: tuple[tuple[int, int], ...]) -> tuple[int, int] | None:
    if not spans:
        return None

    def distance(span: tuple[int, int]) -> float:
        start, end = span
        if start <= x <= end:
            return 0.0
        return min(abs(x - start), abs(x - end))

    return min(spans, key=lambda span: (distance(span), span[0], span[1]))


def _gate(comparison: ComparisonInput) -> tuple[str | None, int | None]:
    mask = comparison.mask
    if mask is None:
        return "MASK_MISSING", None
    if mask.width_px != comparison.frame.width_px or mask.height_px != comparison.frame.height_px:
        return "DIMENSION_MISMATCH", comparison.frame.evaluation_timestamp_ms - mask.timestamp_ms
    age = comparison.frame.evaluation_timestamp_ms - mask.timestamp_ms
    if age < 0:
        return "TIMESTAMP_INVALID", age
    if mask.frame_id != comparison.frame.frame_id or mask.timestamp_ms != comparison.frame.timestamp_ms:
        return "FRAME_MISMATCH", age
    if age > comparison.policy.max_mask_age_ms:
        return "MASK_STALE", age
    if mask.confidence < comparison.policy.min_mask_confidence:
        return "LOW_CONFIDENCE", age
    if not any(spans for spans in mask.sidewalk_rows.values()):
        return "EMPTY_SIDEWALK", age
    return None, age


def _correct_centerline(comparison: ComparisonInput, baseline: tuple[Point, ...]) -> tuple[Point, ...]:
    mask = comparison.mask
    if mask is None:
        return baseline
    limit = comparison.policy.max_lateral_shift_px
    corrected: list[Point] = []
    for point in baseline:
        row = _pixel_row(point.y, comparison.frame.height_px)
        span = _nearest_span(point.x, mask.sidewalk_rows.get(row, ()))
        if span is None:
            corrected.append(point)
            continue
        target_x = (span[0] + span[1]) / 2.0
        shift = max(-limit, min(limit, target_x - point.x))
        corrected.append(
            Point(
                x=max(0.0, min(comparison.frame.width_px - 1.0, point.x + shift)),
                y=point.y,
            )
        )
    return tuple(corrected)


def _ribbon_pixels(
    centerline: tuple[Point, ...],
    half_width: float,
    width: int,
    height: int,
) -> set[tuple[int, int]]:
    pixels: set[tuple[int, int]] = set()
    for point in centerline:
        y = _pixel_row(point.y, height)
        start = max(0, math.floor(point.x - half_width))
        end = min(width - 1, math.ceil(point.x + half_width))
        for x in range(start, end + 1):
            pixels.add((x, y))
    return pixels


def _sidewalk_pixels(mask: MaskInput | None) -> set[tuple[int, int]]:
    if mask is None:
        return set()
    return {
        (x, y)
        for y, spans in mask.sidewalk_rows.items()
        for start, end in spans
        for x in range(start, end + 1)
    }


def _round(value: float) -> float:
    return round(value, 6)


def _ratio(numerator: int, denominator: int) -> float:
    return _round(numerator / denominator) if denominator else 0.0


def _ribbon_metrics(
    comparison: ComparisonInput,
    centerline: tuple[Point, ...],
    sidewalk: set[tuple[int, int]],
) -> dict[str, Any]:
    ribbon = _ribbon_pixels(
        centerline,
        comparison.baseline.ribbon_half_width_px,
        comparison.frame.width_px,
        comparison.frame.height_px,
    )
    inside = len(ribbon & sidewalk)
    offsets: list[float] = []
    for point in centerline:
        row = _pixel_row(point.y, comparison.frame.height_px)
        spans = comparison.mask.sidewalk_rows.get(row, ()) if comparison.mask else ()
        span = _nearest_span(point.x, spans)
        if span is not None:
            offsets.append(abs(point.x - (span[0] + span[1]) / 2.0))
    mean_offset = sum(offsets) / len(offsets) if offsets else None
    max_offset = max(offsets) if offsets else None
    inside_ratio = _ratio(inside, len(ribbon))
    return {
        "ribbon_pixel_count": len(ribbon),
        "inside_sidewalk_pixel_count": inside,
        "inside_sidewalk_ratio": inside_ratio,
        "boundary_escape_ratio": _round(1.0 - inside_ratio),
        "sidewalk_center_sample_count": len(offsets),
        "sidewalk_center_coverage_ratio": _ratio(len(offsets), len(centerline)),
        "mean_sidewalk_center_offset_px": _round(mean_offset) if mean_offset is not None else None,
        "max_sidewalk_center_offset_px": _round(max_offset) if max_offset is not None else None,
    }


def _point_payload(points: tuple[Point, ...]) -> list[list[float]]:
    return [[_round(point.x), _round(point.y)] for point in points]


def _image_sha256(path: Path | None) -> str | None:
    if path is None:
        return None
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def build_report(comparison: ComparisonInput, processing_millis: float = 0.0) -> dict[str, Any]:
    fallback_reason, mask_age_ms = _gate(comparison)
    applied = fallback_reason is None
    baseline = _dense_polyline(comparison.baseline.centerline)
    shadow = _correct_centerline(comparison, baseline) if applied else baseline
    sidewalk = _sidewalk_pixels(comparison.mask)
    baseline_metrics = _ribbon_metrics(comparison, baseline, sidewalk)
    shadow_metrics = _ribbon_metrics(comparison, shadow, sidewalk)
    shifts = [abs(after.x - before.x) for before, after in zip(baseline, shadow)]
    mean_shift = sum(shifts) / len(shifts) if shifts else 0.0
    max_shift = max(shifts) if shifts else 0.0
    baseline_offset = baseline_metrics["mean_sidewalk_center_offset_px"]
    shadow_offset = shadow_metrics["mean_sidewalk_center_offset_px"]

    return {
        "schema_version": REPORT_SCHEMA_VERSION,
        "case_id": comparison.case_id,
        "provenance": {
            "source": comparison.source,
            "synthetic": comparison.synthetic,
            "verified": False,
            "field_verified": False,
        },
        "frame": {
            "frame_id": comparison.frame.frame_id,
            "timestamp_ms": comparison.frame.timestamp_ms,
            "evaluation_timestamp_ms": comparison.frame.evaluation_timestamp_ms,
            "width_px": comparison.frame.width_px,
            "height_px": comparison.frame.height_px,
            "image_present": comparison.frame.image_path is not None,
            "image_sha256": _image_sha256(comparison.frame.image_path),
        },
        "policy": {
            "min_mask_confidence": comparison.policy.min_mask_confidence,
            "max_mask_age_ms": comparison.policy.max_mask_age_ms,
            "max_lateral_shift_px": comparison.policy.max_lateral_shift_px,
        },
        "gate": {
            "status": "APPLIED_SHADOW" if applied else "FALLBACK_A",
            "fallback_reason": fallback_reason,
            "mask_age_ms": mask_age_ms,
            "mask_confidence": comparison.mask.confidence if comparison.mask else None,
        },
        "guidance": {
            "active_variant": "GEOMETRY_BASELINE",
            "user_visible_variant": "GEOMETRY_BASELINE",
            "shadow_variant": "AI_ASSISTED_SHADOW" if applied else "FALLBACK_A",
        },
        "variants": {
            "a": {
                "id": "GEOMETRY_BASELINE",
                "centerline_px": _point_payload(baseline),
                "metrics": baseline_metrics,
            },
            "b": {
                "id": "AI_ASSISTED_SHADOW",
                "applied": applied,
                "centerline_px": _point_payload(shadow),
                "metrics": shadow_metrics,
                "mean_lateral_shift_px": _round(mean_shift),
                "max_lateral_shift_px": _round(max_shift),
            },
        },
        "delta": {
            "inside_sidewalk_ratio": _round(
                shadow_metrics["inside_sidewalk_ratio"]
                - baseline_metrics["inside_sidewalk_ratio"]
            ),
            "boundary_escape_ratio": _round(
                shadow_metrics["boundary_escape_ratio"]
                - baseline_metrics["boundary_escape_ratio"]
            ),
            "mean_sidewalk_center_offset_px": (
                _round(shadow_offset - baseline_offset)
                if shadow_offset is not None and baseline_offset is not None
                else None
            ),
        },
        "segmentation_accuracy": {
            "status": "not_evaluated",
            "reason": "NO_GROUND_TRUTH_MASK",
            "iou": None,
            "dice": None,
            "pixel_recall": None,
        },
        "temporal_stability": {
            "status": "not_evaluated",
            "reason": "SINGLE_STATIC_FRAME",
            "jitter_px": None,
        },
        "safety": {
            "mode": "shadow",
            "route_mutated": False,
            "graph_mutated": False,
            "public_graph_update_allowed": False,
        },
        "runtime": {
            "processing_ms": _round(processing_millis),
            "excluded_from_determinism": True,
        },
    }


def deterministic_report(report: dict[str, Any]) -> dict[str, Any]:
    """Return the comparison content used for repeatability checks."""
    copied = json.loads(json.dumps(report))
    copied.get("runtime", {}).pop("processing_ms", None)
    return copied


def _svg_image(frame: FrameInput, x: float, y: float) -> str:
    if frame.image_path is None:
        return (
            f'<rect x="{x}" y="{y}" width="{frame.width_px}" height="{frame.height_px}" '
            'fill="#171b22"/>'
        )
    media_type = mimetypes.guess_type(frame.image_path.name)[0] or "application/octet-stream"
    encoded = base64.b64encode(frame.image_path.read_bytes()).decode("ascii")
    return (
        f'<image x="{x}" y="{y}" width="{frame.width_px}" height="{frame.height_px}" '
        f'preserveAspectRatio="none" href="data:{escape(media_type)};base64,{encoded}"/>'
    )


def _svg_mask(mask: MaskInput | None, x_offset: float, y_offset: float, opacity: float) -> str:
    if mask is None:
        return ""
    rectangles = []
    for y, spans in sorted(mask.sidewalk_rows.items()):
        for start, end in spans:
            rectangles.append(
                f'<rect x="{x_offset + start}" y="{y_offset + y}" '
                f'width="{end - start + 1}" height="1" fill="#40d38a" opacity="{opacity}"/>'
            )
    return "".join(rectangles)


def _svg_polyline(
    points: list[list[float]],
    x_offset: float,
    y_offset: float,
    color: str,
    stroke_width: float,
) -> str:
    values = " ".join(f"{x_offset + point[0]},{y_offset + point[1]}" for point in points)
    return (
        f'<polyline points="{values}" fill="none" stroke="{color}" '
        f'stroke-width="{stroke_width}" stroke-linecap="round" stroke-linejoin="round"/>'
    )


def render_comparison_svg(comparison: ComparisonInput, report: dict[str, Any]) -> str:
    gap = max(2.0, min(40.0, comparison.frame.width_px * 0.04))
    header = max(4.0, min(48.0, comparison.frame.height_px * 0.15))
    font_size = max(1.2, min(14.0, header * 0.35))
    stroke_width = max(
        1.4,
        min(10.0, min(comparison.frame.width_px, comparison.frame.height_px) * 0.015),
    )
    panel_width = comparison.frame.width_px
    total_width = panel_width * 3 + gap * 2
    total_height = comparison.frame.height_px + header
    offsets = (0, panel_width + gap, (panel_width + gap) * 2)
    labels = (
        "A · BASELINE",
        "SIDEWALK MASK",
        "B · SHADOW" if report["gate"]["status"] == "APPLIED_SHADOW" else "B · FALLBACK A",
    )
    parts = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        f'<svg xmlns="http://www.w3.org/2000/svg" width="1200" viewBox="0 0 {total_width} {total_height}">',
        '<rect width="100%" height="100%" fill="#0d1117"/>',
    ]
    for index, offset in enumerate(offsets):
        parts.append(
            f'<text x="{offset + panel_width / 2}" y="{header * 0.68}" '
            f'fill="#e6edf3" font-family="sans-serif" font-size="{font_size}" text-anchor="middle">'
            f'{escape(labels[index])}</text>'
        )
        parts.append(_svg_image(comparison.frame, offset, header))
        parts.append(
            _svg_mask(
                comparison.mask,
                offset,
                header,
                0.58 if index == 1 else 0.22,
            )
        )
    parts.append(
        _svg_polyline(
            report["variants"]["a"]["centerline_px"],
            offsets[0],
            header,
            "#ff6b6b",
            stroke_width,
        )
    )
    parts.append(
        _svg_polyline(
            report["variants"]["a"]["centerline_px"],
            offsets[1],
            header,
            "#ff6b6b",
            stroke_width,
        )
    )
    parts.append(
        _svg_polyline(
            report["variants"]["b"]["centerline_px"],
            offsets[1],
            header,
            "#64b5ff",
            stroke_width,
        )
    )
    parts.append(
        _svg_polyline(
            report["variants"]["b"]["centerline_px"],
            offsets[2],
            header,
            "#64b5ff",
            stroke_width,
        )
    )
    parts.append("</svg>\n")
    return "".join(parts)


def _atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(content, encoding="utf-8")
    temporary.replace(path)


def write_report(report: dict[str, Any], output_path: Path) -> None:
    _atomic_write(output_path, json.dumps(report, ensure_ascii=False, indent=2) + "\n")


def parse_args(argv: Iterable[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="M1-VIS input manifest")
    parser.add_argument("--report", required=True, type=Path, help="JSON report output")
    parser.add_argument("--svg", type=Path, help="Optional side-by-side SVG output")
    return parser.parse_args(argv)


def main(argv: Iterable[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        comparison = load_fixture(args.input)
        started = time.perf_counter_ns()
        report = build_report(comparison)
        report["runtime"]["processing_ms"] = _round(
            (time.perf_counter_ns() - started) / 1_000_000.0
        )
        write_report(report, args.report)
        if args.svg is not None:
            _atomic_write(args.svg, render_comparison_svg(comparison, report))
        print(
            f"M1-VIS case={report['case_id']} gate={report['gate']['status']} "
            f"inside_delta={report['delta']['inside_sidewalk_ratio']:+.6f}"
        )
        return 0
    except (M1VisError, OSError) as error:
        raise SystemExit(f"M1-VIS comparison failed: {error}") from error


if __name__ == "__main__":
    raise SystemExit(main())
