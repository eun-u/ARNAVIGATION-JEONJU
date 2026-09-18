"""Assess recorded run evidence without issuing whole-project acceptance.

Usage: python scripts/assess_jeonju_run.py <host-run-directory> [--evaluation <file>]
The explicit, evaluator-only JSON must contain clip_id, expected_reroutes,
expected_edge_ids and expected_labels. Optional time_window_ms=[start,end] is
relative to the first captured frame; all expected reroutes must fall inside it.
Optional expected_route_status distinguishes recalculated from no_accessible_route;
without it, route availability is not an evaluated expectation.
It is never read by perception or used to choose an edge. This script is read-only
unless --output is supplied, and never creates captures or evaluation labels.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load(path):
    value = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON object required: {path}")
    return value


def p95(values):
    values = sorted(float(v) for v in values if isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v) and v >= 0)
    return values[max(0, math.ceil(len(values) * .95) - 1)] if values else None


def assess(run: Path, evaluation: Path | None = None) -> dict:
    run = Path(run).resolve()
    if run.is_file():
        run = run.parent
    host_path = run / "run_summary.json"
    host = load(host_path) if host_path.is_file() else {}
    app_dir = run / "app" if (run / "app").is_dir() else run
    app_path = app_dir / "run_summary.json"
    app = load(app_path) if app_path.is_file() and app_path != host_path else host.get("app", host if "input_mode" in host else {})
    event_path = app_dir / "events.jsonl"
    events = [json.loads(line) for line in event_path.read_text(encoding="utf-8-sig").splitlines() if line.strip()] if event_path.is_file() else []
    if any(not isinstance(e, dict) for e in events):
        raise ValueError("events.jsonl must contain JSON objects")
    scope = load(ROOT / "backend/app/config/jeonju_scope.json")
    model = load(ROOT / "android/feature/ai-perception/src/main/assets/model_manifest.json")
    mode = app.get("input_mode", host.get("mode"))
    run_id = app.get("run_id", host.get("run_id"))
    checks, reasons = {}, []
    failures = []

    def check(name, status, reason, *, critical=False, **detail):
        checks[name] = {"status": status, "reason": reason, **detail}
        if status != "passed":
            reasons.append(reason)
        if status == "failed" and critical:
            failures.append(reason)

    actual_mode = mode in ("Live", "Replay")
    check("input_mode", "passed" if actual_mode else "pending", "live_or_replay_required" if not actual_mode else "input_mode_explicit", mode=mode)
    check("event_log", "passed" if events else "pending", "event_log_loaded" if events else "event_log_missing_or_empty")
    coherent = bool(run_id) and all(e.get("run_id") == run_id and e.get("input_mode") == mode for e in events)
    if host.get("app") and host["app"] != app:
        coherent = False
    if host.get("run_id") and host["run_id"] != run_id:
        coherent = False
    check("run_identity", "passed" if coherent else "failed", "run_identity_consistent" if coherent else "mixed_run_or_mode_evidence", critical=bool(events))
    runtime_failure = app.get("status") in ("input_or_runtime_failure", "interrupted", "failed") or host.get("status") == "failed" or any(e.get("type") in ("failure", "tts_error") for e in events)
    check("runtime", "failed" if runtime_failure else "passed", "runtime_failure_recorded" if runtime_failure else "no_failure_event_recorded", critical=actual_mode)

    frames = {str(e["frame_id"]): (i, e) for i, e in enumerate(events) if e.get("type") == "frame" and "frame_id" in e}
    frame_events = [e for e in events if e.get("type") == "frame"]
    forbidden = lambda e: e.get("synthetic") is True or e.get("contract_test") is True or e.get("source") == "contract_test" or any(x in str(e.get("evidence_type", "")).lower() for x in ("synthetic", "contract"))
    valid_frames = actual_mode and bool(frames) and all(
        not forbidden(e) and isinstance(e.get("inference_ms"), (int, float)) and e["inference_ms"] >= 0
        and isinstance(e.get("timestamp_ns"), int) and e["timestamp_ns"] >= 0 for _, e in frames.values()
    )
    check("inference_frames", "passed" if valid_frames else "pending", "real_input_inference_events_present" if valid_frames else "actual_inference_frames_unproven", count=len(frames))
    count_ok = app.get("frames") == len(frame_events) and len(frames) == len(frame_events)
    check("frame_count", "passed" if count_ok else "failed", "frame_count_consistent" if count_ok else "frame_count_or_frame_id_mismatch", critical=actual_mode and bool(frame_events))
    if actual_mode and any(forbidden(e) for e in frame_events):
        check("input_authenticity", "failed", "contract_or_synthetic_frames_are_not_ai_demo_evidence", critical=True)

    bindings = [host, host.get("server_health", {}), app]
    bindings += [e for e in events if e.get("type") == "reroute_requested"]
    bindings += [e.get("recalculated_route") or {} for e in events if e.get("type") == "route_applied"]
    pinned = True
    for key in ("region_id", "dataset_revision", "scope_revision", "graph_sha256"):
        values = {b[key] for b in bindings if b.get(key) is not None}
        status = "pending" if not values else "passed" if values == {scope[key]} else "failed"
        check(key, status, key + ("_matched" if status == "passed" else "_missing" if status == "pending" else "_mismatch"), critical=actual_mode, observed=sorted(values), expected=scope[key])
        pinned &= status == "passed"
    loaded = [e for e in events if e.get("type") == "model_loaded"]
    hashes = {e["model_sha256"] for e in loaded if e.get("model_sha256")}
    model_ok = hashes == {model["sha256"]} and app.get("model_loading_verified") is True
    check("model_hash", "passed" if model_ok else "failed" if hashes else "pending", "model_hash_matched" if model_ok else "model_hash_missing_or_mismatched", critical=actual_mode, expected=model["sha256"], observed=sorted(hashes))
    revisions = {e["model_revision"] for e in loaded if e.get("model_revision")}
    if revisions:
        check("model_revision", "passed" if revisions == {model["model_revision"]} else "failed", "model_revision_matched" if revisions == {model["model_revision"]} else "model_revision_mismatch", critical=actual_mode)
    else:
        check("model_revision", "pending", "model_revision_not_logged_separately_from_hash")

    observations = {(str(e.get("frame_id")), e.get("observation_id")): (i, e) for i, e in enumerate(events) if e.get("type") == "observation"}
    requests = {}
    for i, event in enumerate(events):
        if event.get("type") == "reroute_requested":
            # A later retry of an already applied idempotent request is not its cause.
            requests.setdefault(event.get("event_id"), (i, event))
    wrong_sources = [a.get("source") for _, e in requests.values() for a in e.get("avoidance_upserts", [])
                     if a.get("source") != ("live_ai" if mode == "Live" else "replay_ai") or forbidden(a) or forbidden(a.get("evidence", {}))]
    if actual_mode and wrong_sources:
        check("observation_source", "failed", "contract_synthetic_or_wrong_input_mode_observation", critical=True, sources=wrong_sources)
    applied = [(i, e) for i, e in enumerate(events) if e.get("type") == "route_applied"]
    chains, broken = [], []
    prior_revisions = {}
    for index, result in applied:
        event_id, edge = result.get("event_id"), result.get("edge_id")
        frame_id, observation_id = str(result.get("frame_id")), result.get("observation_id")
        frame, observation, request = frames.get(frame_id), observations.get((frame_id, observation_id)), requests.get(event_id)
        if not (frame and observation and request and frame[0] < observation[0] < request[0] < index):
            broken.append({"event_id": event_id, "reason": "missing_or_misordered_causal_chain"})
            continue
        req = request[1]
        items = [a for a in req.get("avoidance_upserts", []) if a.get("observation_id") == observation_id and str(a.get("frame_id")) == frame_id and a.get("edge_id") == edge]
        source = "live_ai" if mode == "Live" else "replay_ai"
        session, revision = result.get("session_id"), result.get("route_revision")
        route = result.get("recalculated_route") or {}
        valid = (
            actual_mode and len(items) == 1 and items[0].get("source") == source and not forbidden(items[0])
            and not forbidden(items[0].get("evidence", {})) and not forbidden(observation[1])
            and observation[1].get("edge_id") == edge and observation[1].get("decision") == "temporary_avoidance"
            and edge in scope["allowed_edge_ids"] and isinstance(revision, int) and bool(session)
            and revision == req.get("expected_route_revision", -2) + 1
            and revision > prior_revisions.get(session, 0)
            and result.get("status") in ("recalculated", "no_accessible_route")
        )
        if result.get("status") == "recalculated":
            valid &= route.get("session_id") == session and route.get("route_revision") == revision
            valid &= route.get("dataset_revision") == scope["dataset_revision"] and route.get("graph_sha256") == scope["graph_sha256"]
        if not valid:
            broken.append({"event_id": event_id, "reason": "chain_source_edge_session_or_revision_mismatch"})
            continue
        prior_revisions[session] = revision
        chains.append({"frame_id": frame_id, "observation_id": observation_id, "event_id": event_id, "edge_id": edge,
                       "session_id": session, "route_revision": revision, "source": source, "status": result["status"],
                       "timestamp_ns": frame[1]["timestamp_ns"], "request_index": request[0]})
    check("causal_chain", "failed" if broken else "passed" if chains else "pending", "causal_chain_broken" if broken else "observation_to_route_chain_verified" if chains else "no_automatic_mapping_chain", critical=actual_mode, broken=broken, count=len(chains))
    count_ok = app.get("reroutes") == len(applied) and len({e.get("event_id") for _, e in applied}) == len(applied)
    check("reroute_count", "passed" if count_ok else "failed", "reroute_count_consistent" if count_ok else "reroute_count_or_event_id_mismatch", critical=actual_mode)

    guidance_routes = [c for c in chains if c["status"] == "recalculated"]
    tts_complete, rendered = [], []
    for chain in guidance_routes:
        utterance = f"{chain['session_id']}:{chain['route_revision']}"
        starts = [(i, e) for i, e in enumerate(events) if e.get("type") == "tts_started" and e.get("utterance_id") == utterance and i > chain["request_index"]]
        done = [i for i, e in enumerate(events) if e.get("type") == "tts_finished" and e.get("utterance_id") == utterance]
        if any(e.get("current_snapshot") != utterance for _, e in starts):
            check("tts_stale_snapshot", "failed", "tts_started_for_a_different_current_snapshot", critical=True)
        if starts and any(d > starts[0][0] for d in done) and all(e.get("current_snapshot") == utterance for _, e in starts):
            tts_complete.append(utterance)
        acks = {e.get("type") for i, e in enumerate(events) if i > chain["request_index"] and e.get("session_id") == chain["session_id"] and e.get("route_revision") == chain["route_revision"] and e.get("rendered") is True}
        if {"map_rendered", "ar_rendered"} <= acks:
            rendered.append(utterance)
    check("tts_completion", "passed" if guidance_routes and len(tts_complete) == len(guidance_routes) else "pending", "matching_tts_started_and_finished" if guidance_routes and len(tts_complete) == len(guidance_routes) else "matching_session_revision_tts_completion_not_verified", utterances=tts_complete)
    check("render_completion", "passed" if guidance_routes and len(rendered) == len(guidance_routes) else "pending", "matching_map_and_ar_render_acknowledgments" if guidance_routes and len(rendered) == len(guidance_routes) else "render_not_verified_dispatch_is_not_rendering", rendered=rendered)
    render_samples = [e for e in events if e.get("type") == "guidance_render_submitted"]
    submitted = [e for e in render_samples if e.get("draw_submitted") is True and any(
        e.get("session_id") == c["session_id"] and e.get("route_revision") == c["route_revision"] for c in guidance_routes)]
    check("render_submission", "passed" if submitted else "pending", "draw_commands_submitted_not_display_completion" if submitted else "matching_session_revision_draw_submission_not_recorded", samples=len(render_samples), matching_submissions=len(submitted))
    leases = app.get("open_frame_leases")
    check("frame_leases", "passed" if leases == 0 else "pending" if leases is None else "failed", "reported_open_frame_leases_zero" if leases == 0 else "open_frame_leases_missing_or_nonzero", critical=actual_mode, value=leases)
    starts = [e for e in events if e.get("type") == "started"]
    automatic = len(starts) == 1 and starts[0].get("user_start_count") == 1 and starts[0].get("obstacle_user_actions") == 0
    check("automatic_start", "passed" if automatic else "failed" if starts else "pending", "one_start_zero_obstacle_actions" if automatic else "one_start_zero_obstacle_actions_not_verified", critical=actual_mode)

    matched = False
    if evaluation is None:
        check("evaluation", "pending", "explicit_evaluation_file_required_to_judge_mapping_correctness")
    else:
        expected = load(Path(evaluation))
        required = {"clip_id", "expected_reroutes", "expected_edge_ids", "expected_labels"}
        valid_eval = required <= expected.keys() and isinstance(expected.get("expected_reroutes"), int) and not isinstance(expected.get("expected_reroutes"), bool) and expected["expected_reroutes"] >= 0
        valid_eval &= all(isinstance(expected.get(k), list) and all(isinstance(v, str) and v for v in expected[k]) for k in ("expected_edge_ids", "expected_labels"))
        window = expected.get("time_window_ms")
        expected_status = expected.get("expected_route_status")
        valid_eval &= expected_status is None or expected_status in ("recalculated", "no_accessible_route")
        valid_eval &= window is None or (isinstance(window, list) and len(window) == 2 and all(isinstance(v, (int, float)) and math.isfinite(v) for v in window) and 0 <= window[0] <= window[1])
        if not valid_eval:
            check("evaluation", "failed", "invalid_evaluation_contract_no_expectations_were_inferred", critical=True)
        elif expected["clip_id"] != app.get("clip_id") or (expected.get("input_mode") and expected["input_mode"] != mode):
            check("evaluation", "failed", "evaluation_clip_or_mode_mismatch", critical=True)
        elif not valid_frames:
            check("evaluation", "pending", "evaluation_requires_actual_inference_frames")
        else:
            observed_labels = set()
            for e in frame_events + [e for _, e in observations.values()]:
                observed_labels.update(e.get("detection_labels", [])); observed_labels.update(e.get("labels", []))
            for _, req in requests.values():
                for item in req.get("avoidance_upserts", []):
                    observed_labels.update(item.get("evidence", {}).get("labels", []))
            missing_labels = set(expected["expected_labels"]) - observed_labels
            first_ns = min(e["timestamp_ns"] for _, e in frames.values())
            time_ok = window is None or all(window[0] <= (c["timestamp_ns"] - first_ns) / 1e6 <= window[1] for c in chains)
            actions_ok = len(applied) == len(chains) == expected["expected_reroutes"] and {c["edge_id"] for c in chains} == set(expected["expected_edge_ids"]) and time_ok
            actions_ok &= expected_status is None or all(c['status'] == expected_status for c in chains)
            if not actions_ok:
                check("evaluation", "failed", "explicit_expected_reroutes_edges_or_time_window_mismatch", critical=True)
            elif missing_labels:
                check("evaluation", "pending", "expected_object_label_evidence_missing", missing_labels=sorted(missing_labels))
            else:
                matched = True
                check("evaluation", "passed", "explicit_evaluation_expectations_matched", evaluator_only_file=str(Path(evaluation).resolve()), evaluated_fields=sorted(required | ({'time_window_ms'} if window is not None else set()) | ({'expected_route_status'} if expected_status is not None else set())))

    metrics = {"inference_frames": len(frames), "applied_reroutes": len(applied), "verified_chains": len(chains),
               "inference_p95_ms": p95([e.get("inference_ms") for e in frame_events]),
               "backend_p95_ms": app.get("backend_p95_ms"),
               "state_dispatch_p95_ms": p95([e.get("state_dispatch_ms") for _, e in applied]),
               "reaction_p95_ms": p95([e.get("reaction_ms") for _, e in applied]), "open_frame_leases": leases,
               "render_sample_count": len(render_samples), "matching_draw_submission_count": len(submitted),
               "render_frame_interval_p95_ms": p95([e.get("frame_interval_ms") for e in render_samples]),
               "render_work_p95_ms": p95([e.get("render_work_ms") for e in render_samples])}
    for key, target in (("inference_p95_ms", 200), ("backend_p95_ms", 1000), ("state_dispatch_p95_ms", 500), ("reaction_p95_ms", 5000)):
        value = metrics[key]
        check(key, "pending" if value is None else "passed" if value <= target else "not_met", key + ("_unmeasured" if value is None else "_within_target" if value <= target else "_target_missed"), measured=value, target=target)
    status = "rejected" if failures else "verified_partial" if valid_frames and model_ok and pinned and (chains or matched) else "input_pending"
    return {"schema_version": 1, "evidence_status": status, "run_id": run_id, "input_mode": mode,
            "whole_project_acceptance": "not_issued", "automatic_mapping_chain_verified": bool(chains) and not broken and actual_mode and valid_frames and model_ok and pinned and not failures,
            "evaluation_matched": matched and not failures, "field_acceptance_verified": False,
            "checks": checks, "metrics": metrics, "chains": chains, "reasons": list(dict.fromkeys(reasons)),
            "missing_acceptance_gates": ["independent_field_alignment_measurements", "positive_replay_three_runs", "negative_C02_C03_runs", "physical_device_live_three_runs", "AR_alignment_25m", "integrated_10minute_crash_ANR_resource_soak"],
            "limits": ["Event logs do not independently authenticate source video or field measurements.",
                       "Model smoke input and guidance dispatch are not automatic mapping or render completion.",
                       "Per-run evidence never establishes repeated-scene, real-device, alignment or soak acceptance."],
            "sources": {"host_summary": str(host_path), "app_summary": str(app_path), "events": str(event_path)}}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    parser.add_argument("--evaluation", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result = assess(args.run, args.evaluation)
    except (OSError, ValueError, TypeError, KeyError) as exc:
        result = {"evidence_status": "rejected", "whole_project_acceptance": "not_issued", "reasons": ["invalid_or_unreadable_evidence: " + str(exc)]}
    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="utf-8")
    print(output)
    raise SystemExit(1 if result["evidence_status"] == "rejected" else 0)
