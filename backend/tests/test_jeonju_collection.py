"""Synthetic archive contracts only; placeholder media cannot prove real capture or scene labels."""
import hashlib
import importlib.util
import json
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("collection_validator", ROOT / "scripts/validate_jeonju_collection.py")
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


def seal(directory, manifest):
    manifest["files"] = {p.relative_to(directory).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                         for p in directory.rglob("*") if p.is_file() and p.name != "clip_manifest.json"}
    (directory / "clip_manifest.json").write_text(json.dumps(manifest), encoding="utf-8")


@pytest.fixture
def archive(tmp_path):
    scope = json.loads((ROOT / "backend/app/config/jeonju_scope.json").read_text(encoding="utf-8"))
    manifest = {key: scope[key] for key in ("region_id", "dataset_revision", "scope_revision", "graph_sha256")}
    manifest.update(schema_version=3, capture_purpose="case_collection", frame_calibration_policy="unregistered_ARCore_world",
                    completion_state="finalized", input_provenance="arcore_live_capture", calibration_status="pending",
                    replay_eligible=False, field_acceptance_verified=False, case_labels_verified=False, frames=1)
    (tmp_path / "images").mkdir()
    (tmp_path / "images/1.png").write_bytes(b"SYNTHETIC_PLACEHOLDER_NOT_AN_IMAGE")
    (tmp_path / "recording.mp4").write_bytes(b"SYNTHETIC_PLACEHOLDER_NOT_MEDIA")
    row = {"frame_id": 1, "timestamp_ns": 100, "image": "images/1.png",
           "collection": {"elapsed_ms": 1000, "prompt_window": "C01", "case_label_verified": False,
                          "sensors": {"location_role": "raw_device_location_not_AR_calibration"}}}
    (tmp_path / "frames.jsonl").write_text(json.dumps(row)+"\n", encoding="utf-8")
    summary = {"frames": 1, "capture_workflow_completed": False,
               "case_windows": [{"id": key, "frames": int(key == "C01")} for key in ("C01", "C02", "C03")]}
    (tmp_path / "collection_summary.json").write_text(json.dumps(summary), encoding="utf-8")
    (tmp_path / "capture_state.json").write_text('{"status":"interrupted"}', encoding="utf-8")
    seal(tmp_path, manifest)
    return tmp_path, manifest, row


def test_integrity_does_not_upgrade_incomplete_capture_to_field_evidence(archive):
    path, _, _ = archive
    result = validator.validate(path)
    assert result["frames"] == 1
    assert result["capture_workflow_completed"] is False
    assert result["replay_eligible"] is False
    assert result["field_acceptance_verified"] is False
    assert result["media_decode"] == "not_checked_by_host_validator"


@pytest.mark.parametrize("key", ["replay_eligible", "field_acceptance_verified", "case_labels_verified"])
def test_raw_capture_cannot_be_promoted_by_manifest_flag(archive, key):
    path, manifest, _ = archive
    manifest[key] = True
    seal(path, manifest)
    with pytest.raises(ValueError, match="remain_pending"):
        validator.validate(path)


def test_device_location_cannot_masquerade_as_registered_ar_position(archive):
    path, manifest, row = archive
    row["geo_coordinate"] = {"lat": 35.846, "lon": 127.131}
    (path / "frames.jsonl").write_text(json.dumps(row), encoding="utf-8")
    seal(path, manifest)
    with pytest.raises(ValueError, match="not_AR_alignment"):
        validator.validate(path)


def test_labels_must_match_recorded_prompt_windows(archive):
    path, manifest, row = archive
    row["collection"]["elapsed_ms"] = 61_000
    (path / "frames.jsonl").write_text(json.dumps(row), encoding="utf-8")
    seal(path, manifest)
    with pytest.raises(ValueError, match="prompt_window"):
        validator.validate(path)


def test_detects_corrupted_media_and_rejects_path_escape(archive):
    path, manifest, _ = archive
    (path / "recording.mp4").write_bytes(b"CHANGED")
    with pytest.raises(ValueError, match="hash_mismatch"):
        validator.validate(path)
    seal(path, manifest)
    manifest["files"]["../outside"] = "0" * 64
    (path / "clip_manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="unsafe_path"):
        validator.validate(path)
