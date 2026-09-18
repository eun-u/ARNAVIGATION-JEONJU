"""Validate schema-2 capture bytes and spatial contracts, never evaluation labels.

This is not proof that a declared capture was made in the field. MP4 decoding is
checked by Android ClipReader, and actual inference/field acceptance is separate.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path, PureWindowsPath
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
METERS_PER_DEGREE = 111195.08
CALIBRATION_POLICY = "per_frame_ARCore_anchor_poses"


def number(value, reason):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(reason)
    return value


def integer(value, reason, minimum=0):
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ValueError(reason)
    return value


def vector(value, length, reason):
    if not isinstance(value, list) or len(value) != length:
        raise ValueError(reason)
    return [number(v, reason) for v in value]


def geo(value, reason):
    if not isinstance(value, dict):
        raise ValueError(reason)
    lat = number(value.get("lat"), reason)
    lon = number(value.get("lon"), reason)
    if not -90 <= lat <= 90 or not -180 <= lon <= 180:
        raise ValueError(reason)
    return lat, lon


def dimensions(width, height):
    integer(width, "invalid_image_dimensions", 1)
    integer(height, "invalid_image_dimensions", 1)
    if width * height >= 16_000_000:
        raise ValueError("invalid_image_dimensions")
    return width * height


def transform(value, reason):
    values = vector(value, 6, reason)
    if abs(values[2] * values[5] - values[3] * values[4]) < 1e-16:
        raise ValueError(reason)


def calibration(value, baseline=None):
    if not isinstance(value, dict) or value.get("schema_version") != 1 or value.get("method") != "two_measured_references":
        raise ValueError("calibration_reference_missing")
    if value.get("coordinate_system") != "AR_WORLD_METERS_to_WGS84":
        raise ValueError("calibration_coordinate_system_mismatch")
    if not isinstance(value.get("revision"), str) or not value["revision"].strip():
        raise ValueError("invalid_calibration_revision")
    integer(value.get("created_timestamp_ns"), "invalid_calibration_timestamp")
    refs = []
    for key in ("first", "second"):
        ref = value.get(key)
        if not isinstance(ref, dict) or not isinstance(ref.get("label"), str) or not ref["label"].strip():
            raise ValueError("invalid_reference_label")
        accuracy = number(ref.get("accuracy_m"), "invalid_reference_accuracy")
        if not .01 <= accuracy <= .5:
            raise ValueError("invalid_reference_accuracy")
        lat, _ = geo(ref.get("geo"), "invalid_reference_coordinate")
        if abs(lat) >= 89:
            raise ValueError("invalid_reference_coordinate")
        vector(ref.get("world"), 3, "invalid_reference_world")
        refs.append(ref)
    a, b = refs
    east = (b["geo"]["lon"] - a["geo"]["lon"]) * METERS_PER_DEGREE * math.cos(math.radians(a["geo"]["lat"]))
    north = (b["geo"]["lat"] - a["geo"]["lat"]) * METERS_PER_DEGREE
    dx = b["world"][0] - a["world"][0]
    dz = b["world"][2] - a["world"][2]
    local_length, map_length = math.hypot(dx, dz), math.hypot(east, north)
    if min(local_length, map_length) < 5 or abs(local_length - map_length) > max(.5, map_length * .05):
        raise ValueError("reference_distance_mismatch")
    yaw = math.atan2(east, north) - math.atan2(dx, -dz)
    yaw_error = math.atan2(a["accuracy_m"] + b["accuracy_m"], map_length)
    if abs(number(value.get("yaw_radians"), "calibration_transform_mismatch") - yaw) >= 1e-6:
        raise ValueError("calibration_transform_mismatch")
    if abs(number(value.get("yaw_error_radians"), "calibration_uncertainty_mismatch") - yaw_error) >= 1e-6:
        raise ValueError("calibration_uncertainty_mismatch")
    if baseline is not None:
        same = all(value[k] == baseline[k] for k in ("revision", "created_timestamp_ns"))
        same = same and all(value[ref][k] == baseline[ref][k] for ref in ("first", "second") for k in ("geo", "accuracy_m", "label"))
        if not same:
            raise ValueError("frame_anchor_reference_mismatch")
    return value


def frame_location(c, pose, stamp):
    a = c["first"]
    x, north = pose[0] - a["world"][0], -(pose[2] - a["world"][2])
    yaw = c["yaw_radians"]
    east = x * math.cos(yaw) + north * math.sin(yaw)
    n = north * math.cos(yaw) - x * math.sin(yaw)
    location = (a["geo"]["lat"] + n / METERS_PER_DEGREE,
                a["geo"]["lon"] + east / (METERS_PER_DEGREE * math.cos(math.radians(a["geo"]["lat"]))))
    elapsed = (stamp - c["created_timestamp_ns"]) / 1e9
    displacement = math.hypot(x, north)
    error = (max(a["accuracy_m"], c["second"]["accuracy_m"]) + displacement * math.sin(c["yaw_error_radians"])
             + displacement * .005 + elapsed * .001) if 0 <= elapsed <= 600 else None
    return location, error


def png_dimensions(path):
    # Check the recorded PNG's IHDR; full image/media decoding still occurs on Android.
    with path.open("rb") as stream:
        header = stream.read(33)
    if (len(header) != 33 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[8:16] != b"\x00\x00\x00\rIHDR"
            or zlib.crc32(header[12:29]) & 0xffffffff != struct.unpack(">I", header[29:33])[0]):
        raise ValueError("invalid_cpu_png_header")
    return struct.unpack(">II", header[16:24])


def validate(directory: Path) -> dict:
    directory = directory.resolve()
    required = ["recording.mp4", "clip_manifest.json", "frames.jsonl", "calibration.json"]
    missing = [str(directory / n) for n in required if not (directory / n).is_file()]
    if missing:
        raise ValueError("input_missing: " + ", ".join(missing))
    m = json.loads((directory / "clip_manifest.json").read_text(encoding="utf-8"))
    if m.get("schema_version") != 2 or m.get("frame_calibration_policy") != CALIBRATION_POLICY:
        raise ValueError("frame_anchor_calibration_required")
    if m.get("completion_state") != "finalized":
        raise ValueError("clip_not_finalized")
    recording_duration = integer(m.get("recording_duration_ms"), "invalid_recording_duration", 1)
    scope = json.loads((ROOT / "backend/app/config/jeonju_scope.json").read_text(encoding="utf-8"))
    for key in ("region_id", "scope_revision", "dataset_revision", "graph_sha256"):
        if m.get(key) != scope[key]:
            raise ValueError(key + "_mismatch")
    if m.get("input_provenance") != "arcore_live_capture":
        raise ValueError("real_arcore_capture_required")
    model = json.loads((ROOT / "android/feature/ai-perception/src/main/assets/model_manifest.json").read_text(encoding="utf-8"))
    if m.get("model_sha256") != model["sha256"]:
        raise ValueError("model_sha256_mismatch")
    dimensions(m.get("cpu_image_width"), m.get("cpu_image_height"))

    def safe(name):
        if not isinstance(name, str) or not name or PureWindowsPath(name).drive or "\\" in name:
            raise ValueError("unsafe_clip_path")
        path = (directory / name).resolve()
        if path == directory or not path.is_relative_to(directory) or Path(name).is_absolute():
            raise ValueError("unsafe_clip_path")
        return path

    hashes = m.get("files")
    if not isinstance(hashes, dict):
        raise ValueError("clip_hashes_missing")
    for name, digest in hashes.items():
        if hashlib.sha256(safe(name).read_bytes()).hexdigest() != digest:
            raise ValueError("clip_hash_mismatch: " + name)
    for name in ("recording.mp4", "frames.jsonl", "calibration.json"):
        if name not in hashes:
            raise ValueError("missing_file_hash: " + name)
    base = calibration(json.loads((directory / "calibration.json").read_text(encoding="utf-8")))
    rows = [json.loads(line) for line in (directory / "frames.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    integer(m.get("frames"), "frame_count_mismatch", 1)
    if not rows or len(rows) != m["frames"]:
        raise ValueError("frame_count_mismatch")
    last, last_epoch, ids, valid, rotations = -1, -1, set(), 0, set()
    calibrated_epoch = None
    for row in rows:
        stamp = integer(row.get("timestamp_ns"), "invalid_frame_timestamp")
        frame_id = integer(row.get("frame_id"), "invalid_frame_id")
        if stamp <= last or frame_id in ids:
            raise ValueError("nonmonotonic_or_duplicate_frame")
        last = stamp
        ids.add(frame_id)
        integer(row.get("observed_at_epoch_ms"), "invalid_capture_epoch", 1)
        tracking_epoch = integer(row.get("tracking_epoch"), "invalid_tracking_epoch")
        if tracking_epoch < last_epoch:
            raise ValueError("nonmonotonic_tracking_epoch")
        last_epoch = tracking_epoch
        rotation = integer(row.get("rotation_degrees"), "invalid_rotation")
        if rotation not in (0, 90, 180, 270):
            raise ValueError("invalid_rotation")
        rotations.add(rotation)
        if row.get("tracking") not in ("WAITING", "TRACKING", "DEGRADED", "UNAVAILABLE"):
            raise ValueError("invalid_tracking_state")
        if row.get("pose_coordinates") != "AR_WORLD_METERS_x_right_y_up_negative_z_forward":
            raise ValueError("pose_coordinate_system_mismatch")
        pose = vector(row.get("pose"), 7, "invalid_spatial_numbers")
        k = vector(row.get("intrinsics"), 6, "invalid_spatial_numbers")
        dimensions(k[0], k[1])
        if abs(sum(v * v for v in pose[3:]) - 1) >= .02 or min(k[2:4]) <= 0:
            raise ValueError("invalid_pose_or_intrinsics")
        if [k[0], k[1]] != [m["cpu_image_width"], m["cpu_image_height"]]:
            raise ValueError("cpu_image_dimensions_mismatch")
        transform(row.get("image_to_texture"), "invalid_image_to_texture")
        if "image_to_view" in row:
            transform(row["image_to_view"], "invalid_image_to_view")
        if row.get("ground_height_m") is not None:
            number(row["ground_height_m"], "invalid_ground_height")
        current = row.get("calibration")
        accuracy = None
        if row.get("calibration_revision") is not None:
            if row["calibration_revision"] != base["revision"]:
                raise ValueError("frame_calibration_mismatch")
            current = calibration(current, base)
            if calibrated_epoch is not None and tracking_epoch != calibrated_epoch:
                raise ValueError("frame_calibration_tracking_epoch_mismatch")
            calibrated_epoch = tracking_epoch
            expected_geo, accuracy = frame_location(current, pose, stamp)
            actual_geo = geo(row.get("geo_coordinate"), "invalid_frame_geo_coordinate")
            if any(abs(a - b) > 1e-8 for a, b in zip(expected_geo, actual_geo)):
                raise ValueError("frame_geo_coordinate_mismatch")
            actual_accuracy = row.get("horizontal_accuracy_m")
            if accuracy is None:
                if actual_accuracy is not None:
                    raise ValueError("frame_accuracy_mismatch")
            elif abs(number(actual_accuracy, "invalid_frame_accuracy") - accuracy) > 1e-6:
                raise ValueError("frame_accuracy_mismatch")
        elif current is not None or row.get("geo_coordinate") is not None or row.get("horizontal_accuracy_m") is not None:
            raise ValueError("uncalibrated_frame_has_alignment")
        if row.get("image") not in hashes:
            raise ValueError("unhashed_image")
        if png_dimensions(safe(row["image"])) != (k[0], k[1]):
            raise ValueError("cpu_image_dimensions_mismatch")
        for key in ("depth", "semantics"):
            if key not in row:
                continue
            part = row[key]
            pixels = dimensions(part.get("width"), part.get("height"))
            integer(part.get("timestamp_ns"), "invalid_spatial_timestamp")
            if key == "depth" and (part.get("unit") != "mm" or part.get("byte_order") != "little_endian" or part.get("invalid") != 0):
                raise ValueError("depth_format_mismatch")
            if key == "semantics" and part.get("labels") != "ARCore_SemanticLabel":
                raise ValueError("semantic_labels_mismatch")
            for name, size in [(part.get("path"), pixels * (2 if key == "depth" else 1)), (part.get("confidence"), pixels)]:
                if name not in hashes or safe(name).stat().st_size != size:
                    raise ValueError("invalid_spatial_file: " + str(name))
        if (row.get("depth") and row.get("semantics") and current is not None and accuracy is not None and accuracy <= 1.5
                and row["tracking"] == "TRACKING" and all(abs(row[key]["timestamp_ns"] - stamp) <= 100_000_000 for key in ("depth", "semantics"))):
            valid += 1
    if not valid:
        raise ValueError("no_measured_spatial_frames")
    for field, expected in (("first_timestamp_ns", rows[0]["timestamp_ns"]), ("last_timestamp_ns", rows[-1]["timestamp_ns"]),
                            ("first_capture_epoch_ms", rows[0]["observed_at_epoch_ms"]), ("last_capture_epoch_ms", rows[-1]["observed_at_epoch_ms"])):
        if m.get(field) != expected:
            raise ValueError("manifest_" + field + "_mismatch")
    if m.get("rotations_degrees") != sorted(rotations):
        raise ValueError("manifest_rotations_mismatch")
    if recording_duration < (last - rows[0]["timestamp_ns"]) / 1_000_000 - 500:
        raise ValueError("recording_duration_mismatch")
    if safe("recording.mp4").stat().st_size < 1024:
        raise ValueError("recording_empty")
    return {"status": "input_validated_not_inference_verified", "frames": len(rows), "measured_spatial_frames": valid,
            "clip_id": m["clip_id"], "duration_ns": last - rows[0]["timestamp_ns"],
            "validation_scope": "schema_hash_spatial_contract", "media_decode_verified": False, "field_capture_verified": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    print(json.dumps(validate(args.directory)))
