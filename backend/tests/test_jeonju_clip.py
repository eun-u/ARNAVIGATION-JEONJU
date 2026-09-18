"""Synthetic byte/geometry contracts only; never actual camera or AI evidence.

The placeholder MP4 deliberately cannot pass Android media decoding. All files
exist only under pytest tmp_path; none are field clips or Replay demo inputs.
"""
import copy
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import struct
import zlib

import pytest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("jeonju_clip_validator", ROOT / "scripts/validate_jeonju_clip.py")
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


def png(width=4, height=4):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress((b"\x00" + b"\x00" * width * 3) * height)) + chunk(b"IEND", b""))


def fixed_calibration():
    latitude = 35.846
    first = {"geo": {"lat": latitude, "lon": 127.132}, "world": [0.0, 0.0, 0.0], "accuracy_m": .1, "label": "contract A"}
    second = {"geo": {"lat": latitude + 10 / validator.METERS_PER_DEGREE, "lon": 127.132},
              "world": [0.0, 0.0, -10.0], "accuracy_m": .1, "label": "contract B"}
    return {"schema_version": 1, "method": "two_measured_references", "revision": "test-calibration",
            "created_timestamp_ns": 1_000_000_000, "first": first, "second": second,
            "yaw_radians": 0.0, "yaw_error_radians": math.atan2(.2, 10), "coordinate_system": "AR_WORLD_METERS_to_WGS84"}


@pytest.fixture
def clip(tmp_path):
    c = fixed_calibration()
    (tmp_path / "images").mkdir()
    (tmp_path / "depth").mkdir()
    (tmp_path / "semantics").mkdir()
    # Not media; only the host's size/hash contract is exercised by this placeholder.
    (tmp_path / "recording.mp4").write_bytes(b"SYNTHETIC_CONTRACT_NOT_MEDIA" * 50)
    scope = json.loads((ROOT / "backend/app/config/jeonju_scope.json").read_text(encoding="utf-8"))
    model = json.loads((ROOT / "android/feature/ai-perception/src/main/assets/model_manifest.json").read_text(encoding="utf-8"))
    manifest = {k: scope[k] for k in ("region_id", "scope_revision", "dataset_revision", "graph_sha256")}
    manifest.update(schema_version=2, clip_id="CONTRACT_ONLY", input_provenance="arcore_live_capture",
                    frame_calibration_policy=validator.CALIBRATION_POLICY, model_sha256=model["sha256"],
                    cpu_image_width=4, cpu_image_height=4, completion_state="finalized", recording_duration_ms=1000)
    rows = []
    for index in range(2):
        stamp = 1_200_000_000 + index * 200_000_000
        pose = [0.0, 0.0, -1.0, 0.0, 0.0, 0.0, 1.0]
        location, accuracy = validator.frame_location(c, pose, stamp)
        row = {"frame_id": index + 1, "timestamp_ns": stamp, "observed_at_epoch_ms": 1_700_000_000_000 + index * 200,
               "image": f"images/{index + 1}.png", "rotation_degrees": 0, "tracking": "TRACKING", "tracking_epoch": 0,
               "pose": pose, "intrinsics": [4, 4, 4.0, 4.0, 2.0, 2.0],
               "image_to_texture": [0.0, 0.0, .25, 0.0, 0.0, .25], "image_to_view": [0.0, 0.0, .25, 0.0, 0.0, .25],
               "calibration_revision": c["revision"], "calibration": copy.deepcopy(c), "ground_height_m": 0.0,
               "geo_coordinate": {"lat": location[0], "lon": location[1]}, "horizontal_accuracy_m": accuracy,
               "pose_coordinates": "AR_WORLD_METERS_x_right_y_up_negative_z_forward",
               "depth": {"path": f"depth/{index + 1}.u16", "confidence": f"depth/{index + 1}.conf", "width": 2, "height": 2,
                         "timestamp_ns": stamp, "unit": "mm", "invalid": 0, "byte_order": "little_endian"},
               "semantics": {"path": f"semantics/{index + 1}.u8", "confidence": f"semantics/{index + 1}.conf", "width": 2, "height": 2,
                             "timestamp_ns": stamp, "labels": "ARCore_SemanticLabel"}}
        (tmp_path / row["image"]).write_bytes(png())
        for key in ("depth", "semantics"):
            part = row[key]
            (tmp_path / part["path"]).write_bytes(struct.pack("<4H", 2000, 2000, 2000, 2000) if key == "depth" else bytes([5] * 4))
            (tmp_path / part["confidence"]).write_bytes(bytes([255] * 4))
        rows.append(row)

    def save():
        (tmp_path / "calibration.json").write_text(json.dumps(c), encoding="utf-8")
        (tmp_path / "frames.jsonl").write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
        manifest.update(frames=len(rows), first_timestamp_ns=rows[0]["timestamp_ns"], last_timestamp_ns=rows[-1]["timestamp_ns"],
                        first_capture_epoch_ms=rows[0]["observed_at_epoch_ms"], last_capture_epoch_ms=rows[-1]["observed_at_epoch_ms"],
                        rotations_degrees=sorted({row["rotation_degrees"] for row in rows}))
        manifest["files"] = {path.relative_to(tmp_path).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                             for path in tmp_path.rglob("*") if path.is_file() and path.name != "clip_manifest.json"}
        (tmp_path / "clip_manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    save()
    return tmp_path, c, rows, manifest, save


def test_schema2_contract_does_not_claim_media_inference_or_field_verification(clip):
    directory, _, _, _, _ = clip
    result = validator.validate(directory)
    assert result["frames"] == result["measured_spatial_frames"] == 2
    assert result["status"] == "input_validated_not_inference_verified"
    assert result["media_decode_verified"] is False
    assert result["field_capture_verified"] is False


def test_per_frame_anchor_translation_and_rotation_preserve_fixed_map_point(clip):
    directory, _, rows, _, save = clip
    row = rows[1]
    # AR world has translated and rotated 90 degrees; actual map refs are unchanged.
    row["calibration"]["first"]["world"] = [20.0, 0.0, 30.0]
    row["calibration"]["second"]["world"] = [30.0, 0.0, 30.0]
    row["calibration"]["yaw_radians"] = -math.pi / 2
    row["pose"][:3] = [21.0, 0.0, 30.0]
    save()
    assert validator.validate(directory)["measured_spatial_frames"] == 2


@pytest.mark.parametrize("field,value", [("label", "another point"), ("accuracy_m", .2), ("geo", {"lat": 35.846001, "lon": 127.132})])
def test_frame_cannot_replace_reference_identity_or_accuracy(clip, field, value):
    directory, _, rows, _, save = clip
    changed = rows[1]["calibration"]
    changed["first"][field] = value
    a, b = changed["first"], changed["second"]
    east = (b["geo"]["lon"] - a["geo"]["lon"]) * validator.METERS_PER_DEGREE * math.cos(math.radians(a["geo"]["lat"]))
    north = (b["geo"]["lat"] - a["geo"]["lat"]) * validator.METERS_PER_DEGREE
    changed["yaw_radians"] = math.atan2(east, north) - math.atan2(b["world"][0] - a["world"][0], a["world"][2] - b["world"][2])
    changed["yaw_error_radians"] = math.atan2(a["accuracy_m"] + b["accuracy_m"], math.hypot(east, north))
    save()
    with pytest.raises(ValueError, match="frame_anchor_reference_mismatch"):
        validator.validate(directory)


@pytest.mark.parametrize("field,value", [("revision", "changed-cal"), ("created_timestamp_ns", 1_100_000_000)])
def test_frame_cannot_reset_calibration_identity_or_age(clip, field, value):
    directory, _, rows, _, save = clip
    rows[1]["calibration"][field] = value
    save()
    with pytest.raises(ValueError, match="frame_anchor_reference_mismatch"):
        validator.validate(directory)


@pytest.mark.parametrize("field,value", [("schema_version", 1), ("frame_calibration_policy", "initial_only"),
                                        ("completion_state", "recording"), ("recording_duration_ms", 0)])
def test_old_or_unfinished_manifests_are_rejected(clip, field, value):
    directory, _, _, manifest, save = clip
    manifest[field] = value
    save()
    with pytest.raises(ValueError, match="frame_anchor_calibration_required|clip_not_finalized|invalid_recording_duration"):
        validator.validate(directory)


def test_same_calibration_cannot_cross_tracking_epoch(clip):
    directory, _, rows, _, save = clip
    rows[1]["tracking_epoch"] = 1
    save()
    with pytest.raises(ValueError, match="frame_calibration_tracking_epoch_mismatch"):
        validator.validate(directory)


def test_missing_or_stale_spatial_frames_are_retained_but_not_counted_usable(clip):
    directory, _, rows, _, save = clip
    rows[1].update(calibration_revision=None, calibration=None, geo_coordinate=None, horizontal_accuracy_m=None,
                   tracking="DEGRADED", tracking_epoch=1)
    rows[1].pop("depth")
    save()
    assert validator.validate(directory)["measured_spatial_frames"] == 1
    rows[0]["semantics"]["timestamp_ns"] -= 100_000_001
    save()
    with pytest.raises(ValueError, match="no_measured_spatial_frames"):
        validator.validate(directory)


@pytest.mark.parametrize("field,value,error", [
    ("image_to_view", [0, 0, 0, 0, 0, 0], "invalid_image_to_view"),
    ("image_to_view", [0, 0, .25, 0, 0, float("nan")], "invalid_image_to_view"),
    ("image_to_texture", [0, 0, .25], "invalid_image_to_texture"),
    ("pose", [0, 0, 0, 0, 0, 0, 0], "invalid_pose_or_intrinsics"),
    ("intrinsics", [4.5, 4, 4, 4, 2, 2], "invalid_image_dimensions"),
    ("ground_height_m", float("inf"), "invalid_ground_height"),
    ("tracking_epoch", -1, "invalid_tracking_epoch"),
    ("tracking", "invented", "invalid_tracking_state"),
    ("horizontal_accuracy_m", .01, "frame_accuracy_mismatch"),
    ("geo_coordinate", {"lat": 0, "lon": 0}, "frame_geo_coordinate_mismatch"),
])
def test_invalid_frame_values_cannot_be_silently_replayed(clip, field, value, error):
    directory, _, rows, _, save = clip
    rows[1][field] = value
    save()
    with pytest.raises(ValueError, match=error):
        validator.validate(directory)


def test_frame_without_calibration_cannot_keep_claimed_location(clip):
    directory, _, rows, _, save = clip
    rows[1].update(calibration_revision=None, calibration=None)
    save()
    with pytest.raises(ValueError, match="uncalibrated_frame_has_alignment"):
        validator.validate(directory)


def test_bad_measurement_size_even_with_updated_hash_is_rejected(clip):
    directory, _, rows, _, save = clip
    (directory / rows[1]["depth"]["path"]).write_bytes(b"\x00\x01")
    save()
    with pytest.raises(ValueError, match="invalid_spatial_file"):
        validator.validate(directory)


def test_png_size_must_match_intrinsics(clip):
    directory, _, rows, _, save = clip
    (directory / rows[1]["image"]).write_bytes(png(8, 4))
    save()
    with pytest.raises(ValueError, match="cpu_image_dimensions_mismatch"):
        validator.validate(directory)


def test_manifest_hash_tampering_is_rejected_before_geometry(clip):
    directory, _, rows, _, _ = clip
    (directory / rows[1]["image"]).write_bytes(b"tampered")
    with pytest.raises(ValueError, match="clip_hash_mismatch"):
        validator.validate(directory)


@pytest.mark.parametrize("unsafe", ["../outside", "C:/outside", "images\\file.png"])
def test_manifest_paths_cannot_leave_clip_directory(clip, unsafe):
    directory, _, _, manifest, _ = clip
    manifest["files"][unsafe] = "0" * 64
    (directory / "clip_manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="unsafe_clip_path"):
        validator.validate(directory)


def test_manifest_duration_must_cover_recorded_frames(clip):
    directory, _, rows, manifest, save = clip
    rows[1]["timestamp_ns"] += 2_000_000_000
    for key in ("depth", "semantics"):
        rows[1][key]["timestamp_ns"] = rows[1]["timestamp_ns"]
    _, rows[1]["horizontal_accuracy_m"] = validator.frame_location(rows[1]["calibration"], rows[1]["pose"], rows[1]["timestamp_ns"])
    manifest["recording_duration_ms"] = 1000
    save()
    with pytest.raises(ValueError, match="recording_duration_mismatch"):
        validator.validate(directory)
