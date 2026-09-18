"""Check raw one-button collection integrity, never approve calibration or C01-C03 labels."""
import argparse
import hashlib
import json
from pathlib import Path, PureWindowsPath

ROOT = Path(__file__).resolve().parents[1]


def validate(directory: Path) -> dict:
    directory = directory.resolve()
    manifest = json.loads((directory / "clip_manifest.json").read_text(encoding="utf-8"))
    if (manifest.get("schema_version") != 3 or manifest.get("capture_purpose") != "case_collection"
            or manifest.get("frame_calibration_policy") != "unregistered_ARCore_world"
            or manifest.get("completion_state") != "finalized"):
        raise ValueError("raw_collection_manifest_required")
    if (manifest.get("input_provenance") != "arcore_live_capture" or manifest.get("calibration_status") != "pending"
            or any(manifest.get(key) is not False for key in ("replay_eligible", "field_acceptance_verified", "case_labels_verified"))):
        raise ValueError("unverified_collection_must_remain_pending")
    expected = json.loads((ROOT / "backend/app/config/jeonju_scope.json").read_text(encoding="utf-8"))
    for key in ("region_id", "scope_revision", "dataset_revision", "graph_sha256"):
        if manifest.get(key) != expected[key]:
            raise ValueError(key + "_mismatch")

    def safe(name):
        if not isinstance(name, str) or not name or "\\" in name or PureWindowsPath(name).drive:
            raise ValueError("unsafe_path")
        path = (directory / name).resolve()
        if not path.is_relative_to(directory) or path == directory:
            raise ValueError("unsafe_path")
        return path

    hashes = manifest.get("files", {})
    for name in ("recording.mp4", "frames.jsonl", "collection_summary.json", "capture_state.json"):
        if name not in hashes:
            raise ValueError("required_file_hash_missing: " + name)
    for name, digest in hashes.items():
        if hashlib.sha256(safe(name).read_bytes()).hexdigest() != digest:
            raise ValueError("file_hash_mismatch: " + name)
    if (directory / "calibration.json").exists():
        raise ValueError("raw_collection_must_not_claim_calibration")
    rows = [json.loads(line) for line in safe("frames.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    if not rows or len(rows) != manifest.get("frames"):
        raise ValueError("frame_count_mismatch")
    last_stamp, last_id, last_elapsed = -1, -1, -1
    counts = {key: 0 for key in ("C01", "C02", "C03")}
    for row in rows:
        stamp, frame_id = row["timestamp_ns"], row["frame_id"]
        if stamp <= last_stamp or frame_id <= last_id:
            raise ValueError("frame_time_not_monotonic")
        last_stamp, last_id = stamp, frame_id
        if any(row.get(key) is not None for key in ("calibration", "calibration_revision", "geo_coordinate", "horizontal_accuracy_m")):
            raise ValueError("device_location_is_not_AR_alignment")
        meta = row["collection"]
        elapsed = meta["elapsed_ms"]
        if isinstance(elapsed, bool) or not isinstance(elapsed, int) or not 0 <= elapsed < 180_000 or elapsed < last_elapsed:
            raise ValueError("invalid_collection_time")
        last_elapsed = elapsed
        case = ("C01", "C02", "C03")[elapsed // 60_000]
        if meta.get("prompt_window") != case or meta.get("case_label_verified") is not False:
            raise ValueError("prompt_window_is_not_a_verified_label")
        counts[case] += 1
        if meta.get("sensors", {}).get("location_role") != "raw_device_location_not_AR_calibration":
            raise ValueError("location_provenance_missing")
        if row["image"] not in hashes:
            raise ValueError("image_hash_missing")
        for key in ("depth", "semantics"):
            part = row.get(key)
            if part:
                pixels = part["width"] * part["height"]
                for name, size in ((part["path"], pixels * (2 if key == "depth" else 1)), (part["confidence"], pixels)):
                    if name not in hashes or safe(name).stat().st_size != size:
                        raise ValueError("sensor_bytes_mismatch")
    summary = json.loads(safe("collection_summary.json").read_text(encoding="utf-8"))
    if summary.get("frames") != len(rows) or {item["id"]: item["frames"] for item in summary["case_windows"]} != counts:
        raise ValueError("case_counts_mismatch")
    return {"status": "raw_collection_integrity_passed", "frames": len(rows), "prompt_window_frames": counts,
            "capture_workflow_completed": summary.get("capture_workflow_completed"), "calibration_status": "pending",
            "case_labels_verified": False, "field_acceptance_verified": False, "replay_eligible": False,
            "media_decode": "not_checked_by_host_validator"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    print(json.dumps(validate(args.directory), ensure_ascii=False, indent=2))
