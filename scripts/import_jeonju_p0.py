"""Import the handed-off P0 bundle without executing it or replacing the routing Graph."""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
from app.graph_store import GraphStore
from app.poc_scope import PocScope


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def verified_members(archive: Path) -> tuple[dict[str, bytes], dict[str, str]]:
    with zipfile.ZipFile(archive) as z:
        seen = set()
        if sum(i.file_size for i in z.infolist()) > 256 * 1024 * 1024:
            raise ValueError("archive_too_large")
        for item in z.infolist():
            name = item.filename
            p = PurePosixPath(name)
            if p.is_absolute() or ".." in p.parts or "\\" in name or ":" in name or (item.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError(f"unsafe_member: {name}")
            if name.casefold() in seen:
                raise ValueError(f"duplicate_member: {name}")
            seen.add(name.casefold())
        corrupt = z.testzip()
        if corrupt:
            raise ValueError(f"crc_mismatch: {corrupt}")
        checks = [n for n in z.namelist() if n.endswith("/07_manifest/checksums.sha256")]
        if len(checks) != 1:
            raise ValueError("checksum_manifest_missing_or_ambiguous")
        prefix = checks[0][:-len("07_manifest/checksums.sha256")]
        files = {}
        for item in z.infolist():
            if item.is_dir():
                continue
            if not item.filename.startswith(prefix):
                raise ValueError("multiple_bundle_roots")
            files[item.filename[len(prefix):]] = z.read(item)
        hashes = {}
        for line in files["07_manifest/checksums.sha256"].decode("utf-8-sig").splitlines():
            digest, name = line.split(maxsplit=1)
            name = name.lstrip("*")
            if name in hashes or name not in files or sha(files[name]) != digest.lower():
                raise ValueError(f"checksum_mismatch: {name}")
            hashes[name] = digest.lower()
        if set(hashes) != set(files) - {"07_manifest/checksums.sha256"}:
            raise ValueError("checksum_coverage_incomplete")
        return files, hashes


def import_bundle(archive: Path, output_root: Path = ROOT) -> dict:
    files, hashes = verified_members(archive)
    content_hash=sha('\n'.join(name+':'+sha(files[name]) for name in sorted(files)).encode('utf-8'))
    expected=json.loads((ROOT/'backend/app/config/jeonju_scope.json').read_text(encoding='utf-8'))
    if content_hash != expected['bundle_content_sha256']:
        raise ValueError('dataset_revision_content_mismatch: validate a new revision before importing changed source bytes')
    required = ["01_scope/jeonju_p0_scope.geojson", "02_osm/raw/jeonju_p0_osm_20260918.osm",
                "02_osm/processed/jeonju_p0_walk_network.geojson"]
    for name in required:
        if name not in files:
            raise ValueError(f"required_member_missing: {name}")
    scope_geo = json.loads(files[required[0]])
    if scope_geo["metadata"]["crs"] != "EPSG:4326" or scope_geo["metadata"]["bbox"] != [127.128,35.8425,127.1365,35.8495]:
        raise ValueError("scope_crs_or_bbox_mismatch")
    counts, warnings = {}, ["v2.2 document absent; eight-edge scope derived from user instruction and v2.1 route union", "field_crossing_check=pending"]
    for name, raw in files.items():
        if name.endswith(".geojson"):
            geo = json.loads(raw)
            if geo.get("type") != "FeatureCollection":
                raise ValueError(f"invalid_geojson: {name}")
            counts[name] = len(geo["features"])
            def coords(value):
                if value and isinstance(value[0], (float,int)):
                    if len(value) < 2 or not (126 < value[0] < 128 and 35 < value[1] < 37):
                        raise ValueError(f"coordinate_order_or_region: {name}")
                else:
                    for child in value: coords(child)
            for f in geo["features"]: coords(f["geometry"]["coordinates"])
        elif name.endswith(".csv"):
            encoding = "cp949" if "cp949" in name else "utf-8-sig"
            counts[name] = sum(1 for _ in csv.DictReader(io.StringIO(raw.decode(encoding))))
    if counts.get("07_manifest/source_manifest.csv") != 39:
        warnings.append(f"source_manifest.csv actual={counts.get('07_manifest/source_manifest.csv')}; upstream report=39")
    osm = ET.fromstring(files[required[1]])
    source_path = ROOT / "data/raw/jeonju/osm/2026/map.osm"
    source = ET.parse(source_path).getroot()
    original = {(e.tag,e.get("id")):e for e in source if e.tag in {"node","way","relation"}}
    nodes = {e.get("id") for e in osm.findall("node")}
    way_ids = {e.get("id") for e in osm.findall("way")}
    def signature(e):
        return (e.get("version"),e.get("lat"),e.get("lon"),[(c.tag, sorted(c.attrib.items())) for c in e])
    for element in osm:
        if element.tag not in {"node","way","relation"}: continue
        prior = original.get((element.tag,element.get("id")))
        if prior is None or signature(prior) != signature(element):
            raise ValueError(f"osm_lineage_mismatch: {element.tag}/{element.get('id')}")
        if element.tag == "way" and any(c.get("ref") not in nodes for c in element.findall("nd")):
            raise ValueError("missing_way_node")
    store = GraphStore(ROOT / "data/processed/jeonju_accessibility_graph.geojson")
    scope = PocScope(store)
    revision = scope.data["dataset_revision"]
    raw_dir = output_root / "data/raw/jeonju/p0/20260918"
    # Preserve previously imported bytes; never overwrite a different source revision.
    for name, raw in files.items():
        target = raw_dir / name
        if target.exists() and target.read_bytes() != raw:
            raise ValueError(f"existing_source_conflict: {target}")
    for name, raw in files.items():
        target = raw_dir / name
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists(): target.write_bytes(raw)
    mappings = []
    for _,_,attrs in store.graph.edges(data=True):
        ids = [str(x) for x in attrs.get("osm_way_ids",[])]
        if any(i in way_ids for i in ids):
            mappings.append({"edge_id":attrs["edge_id"],"osm_way_ids":ids,"osm_u":attrs.get("osm_u"),"osm_v":attrs.get("osm_v"),
                "region_id":scope.data["region_id"],"dataset_revision":revision,"source_file":required[1],
                "source_sha256":hashes[required[1]],"mapping_status":"matched","mapping_reason":"shared_osm_object_ids",
                "in_poc":attrs["edge_id"] in scope.allowed,"verified":False})
    if not scope.allowed.issubset({m["edge_id"] for m in mappings}):
        raise ValueError("scope_osm_join_incomplete")
    manifest = {"schema_version":1, **scope.revisions(),"bundle_sha256":sha(archive.read_bytes()),
        "bundle_content_sha256":content_hash,
        "graph_sha256_encoding":"utf8_lf","graph_file_sha256":sha(store.path.read_bytes()),
        "graph_path":"data/processed/jeonju_accessibility_graph.geojson","raw_directory":str(raw_dir.relative_to(output_root)).replace("\\","/"),
        "runtime_graph_is_walk_candidates":False,"file_count":len(files),"checksum_verified":len(hashes),
        "files":{n:sha(b) for n,b in files.items()},"counts":counts,"osm_counts":{t:len(osm.findall(t)) for t in ("node","way","relation")},
        "allowed_edge_ids":sorted(scope.allowed),"field_crossing_check":"pending","human_reviewed":False,"field_verified":False,
        "missing_inputs":["C01-C03/recording.mp4","C01-C03/clip_manifest.json","C01-C03/frames.jsonl","C01-C03/calibration.json","C01-C03/depth and semantics"],
        "warnings":warnings}
    write_json(output_root / "data/processed/jeonju/osm_edge_mapping.json",mappings)
    write_json(output_root / "data/processed/jeonju/p0_manifest.json",manifest)
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=Path, required=True)
    args = parser.parse_args()
    result = import_bundle(args.archive)
    print(json.dumps({k:result[k] for k in ("dataset_revision","file_count","checksum_verified","missing_inputs","warnings")},ensure_ascii=False))
