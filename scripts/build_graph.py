from __future__ import annotations

import argparse
import csv
import hashlib
import itertools
import json
import math
import re
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable

import networkx as nx
import osmnx as ox
from pyproj import Transformer
from shapely.geometry import LineString, Point
from shapely.ops import transform


PROJECT_ROOT = Path(__file__).resolve().parents[1]
PACKAGE_DIR = (
    PROJECT_ROOT
    / "docs"
    / "ONWAY_안양_대표회랑_MVP_데이터패키지_20260915"
)
DEFAULT_INPUT = PROJECT_ROOT / "data" / "raw" / "anyang_corridor_walk_20260915.graphml"
DEFAULT_OUTPUT = PROJECT_ROOT / "data" / "processed" / "anyang_accessibility_graph.geojson"
DEFAULT_MAPPINGS = PROJECT_ROOT / "data" / "processed" / "onway_crosswalk_mappings.json"
DEFAULT_CANDIDATES = PROJECT_ROOT / "data" / "processed" / "review_candidates.json"
PILOT_APPROACH_IDS = {"CW01-A", "CW04-A", "CW10-B", "CW13-B", "CW18-A"}
DEMO_ORIGIN_SAMPLE = "CW04"
DEMO_DESTINATION_SAMPLE = "CW20"
MAX_MAPPING_DISTANCE_M = 15.0


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def as_list(value: Any) -> list[Any]:
    if isinstance(value, list):
        return value
    if value is None:
        return []
    return [value]


def first_text(value: Any, default: str = "unknown") -> str:
    values = as_list(value)
    return str(values[0]) if values else default


def parse_number(value: Any) -> float | None:
    for item in as_list(value):
        match = re.search(r"-?\d+(?:\.\d+)?", str(item).replace(",", "."))
        if match:
            return float(match.group())
    return None


def incline_percent(value: Any) -> float | None:
    """Grade magnitude for an undirected graph; keep direction in the raw tags."""
    result = []
    for item in as_list(value):
        text = str(item).strip().lower()
        match = re.fullmatch(r"([+-]?\d+(?:\.\d+)?)\s*(%|°|deg)?", text)
        if not match:
            return None  # up/down or malformed tags are unknown, not zero.
        number = float(match[1])
        if match[2] in {"°", "deg"}:
            if abs(number) >= 90:
                return None
            number = math.tan(math.radians(number)) * 100
        result.append(abs(number))
    return max(result) if result else None


def width_meters(value: Any) -> float | None:
    result = []
    for item in as_list(value):
        match = re.fullmatch(r"(\d+(?:\.\d+)?)\s*(m|cm|ft)?", str(item).strip().lower())
        if not match:
            return None
        result.append(float(match[1]) * {None: 1, "m": 1, "cm": .01, "ft": .3048}[match[2]])
    return min(result) if result else None


def node_id(osm_node_id: Any) -> str:
    return f"OSM_N{osm_node_id}"


def normalize_way_ids(value: Any) -> list[int]:
    result: list[int] = []
    for item in as_list(value):
        try:
            result.append(int(item))
        except (TypeError, ValueError):
            continue
    return sorted(set(result))


def stable_edge_id(u: Any, v: Any, key: Any, way_ids: list[int]) -> str:
    ways = "-".join(map(str, way_ids)) or "unknown"
    low, high = sorted((str(u), str(v)))
    raw = f"{ways}|{low}|{high}|{key}"
    suffix = hashlib.sha1(raw.encode("utf-8"), usedforsecurity=False).hexdigest()[:10]
    return f"OSM_E_{ways}_{suffix}"


def oriented_coordinates(
    graph: nx.MultiGraph, u: Any, v: Any, attrs: dict[str, Any]
) -> list[list[float]]:
    geometry = attrs.get("geometry")
    if geometry is None:
        coordinates = [
            [float(graph.nodes[u]["x"]), float(graph.nodes[u]["y"])],
            [float(graph.nodes[v]["x"]), float(graph.nodes[v]["y"])],
        ]
    else:
        coordinates = [[float(x), float(y)] for x, y in geometry.coords]

    u_xy = (float(graph.nodes[u]["x"]), float(graph.nodes[u]["y"]))
    start_distance = (coordinates[0][0] - u_xy[0]) ** 2 + (
        coordinates[0][1] - u_xy[1]
    ) ** 2
    end_distance = (coordinates[-1][0] - u_xy[0]) ** 2 + (
        coordinates[-1][1] - u_xy[1]
    ) ** 2
    if end_distance < start_distance:
        coordinates.reverse()
    return coordinates


def nearest_node(graph: nx.MultiGraph, lon: float, lat: float) -> Any:
    return min(
        graph.nodes,
        key=lambda current: (
            (float(graph.nodes[current]["x"]) - lon) ** 2
            + (float(graph.nodes[current]["y"]) - lat) ** 2
        ),
    )


def pair_key(u: Any, v: Any) -> frozenset[Any]:
    return frozenset((u, v))


def path_distance(graph: nx.Graph, path: list[Any]) -> float:
    return sum(float(graph[u][v]["length"]) for u, v in zip(path, path[1:]))


def select_detour_edge(
    graph: nx.Graph,
    path: list[Any],
    parallel_counts: dict[frozenset[Any], int],
) -> tuple[tuple[Any, Any], list[Any]]:
    baseline = path_distance(graph, path)
    candidates: list[tuple[float, int, str, tuple[Any, Any], list[Any]]] = []
    middle = (len(path) - 1) / 2

    for index, (u, v) in enumerate(zip(path, path[1:])):
        if parallel_counts[pair_key(u, v)] != 1:
            continue
        trial = graph.copy()
        trial.remove_edge(u, v)
        try:
            detour = nx.shortest_path(trial, path[0], path[-1], weight="length")
        except nx.NetworkXNoPath:
            continue
        delta = path_distance(trial, detour) - baseline
        if detour == path or delta <= 0:
            continue
        candidates.append(
            (delta, -abs(index - middle), f"{u}:{v}", (u, v), detour)
        )

    if not candidates:
        raise RuntimeError("No non-bridge demo edge produced a distinct detour")
    _, _, _, selected, detour = max(candidates)
    return selected, detour


def build_edge_records(
    graph: nx.MultiGraph,
) -> tuple[list[dict[str, Any]], dict[frozenset[Any], list[dict[str, Any]]]]:
    records: list[dict[str, Any]] = []
    by_pair: dict[frozenset[Any], list[dict[str, Any]]] = defaultdict(list)

    for u, v, key, attrs in graph.edges(keys=True, data=True):
        way_ids = normalize_way_ids(attrs.get("osmid"))
        highway_values = [str(value) for value in as_list(attrs.get("highway"))]
        wheelchair_values = [str(value) for value in as_list(attrs.get("wheelchair"))]
        coordinates = oriented_coordinates(graph, u, v, attrs)
        is_steps = "steps" in highway_values
        wheelchair_accessible: bool | None = None
        if is_steps or "no" in wheelchair_values:
            wheelchair_accessible = False
        elif wheelchair_values and all(value in {"yes", "designated"} for value in wheelchair_values):
            wheelchair_accessible = True

        record = {
            "edge_id": stable_edge_id(u, v, key, way_ids),
            "from_node": node_id(u),
            "to_node": node_id(v),
            "osm_u": str(u),
            "osm_v": str(v),
            "osm_key": str(key),
            "osm_way_ids": way_ids,
            "name": first_text(attrs.get("name"), "OSM 보행 구간"),
            "length": round(float(attrs.get("length", 0.0)), 3),
            "geometry": coordinates,
            "stairs": is_steps,
            "slope": incline_percent(attrs.get("incline")),
            "width": width_meters(attrs.get("width")),
            "curb_height": None,
            "surface": first_text(attrs.get("surface")),
            "elevator_required": False,
            "elevator_status": None,
            "blocked": False,
            "block_reason": None,
            "wheelchair_accessible": wheelchair_accessible,
            "accessibility_status": "unknown",
            "source": "osm",
            "accessibility_source": "osm_tags",
            "confidence": 0.7,
            "verified": False,
            "updated_at": None,
            "highway": highway_values,
            "raw_accessibility_tags": {
                key_name: attrs.get(key_name)
                for key_name in (
                    "foot",
                    "sidewalk",
                    "smoothness",
                    "kerb",
                    "wheelchair",
                    "access", "incline", "width", "surface", "footway", "crossing",
                    "barrier", "entrance", "oneway:foot", "tactile_paving",
                )
                if attrs.get(key_name) is not None
            },
            "crosswalk_samples": [],
            "approach_ids": [],
            "demo_editable": False,
        }
        records.append(record)
        by_pair[pair_key(u, v)].append(record)
    return records, by_pair


def build_simple_graph(
    graph: nx.MultiGraph, records_by_pair: dict[frozenset[Any], list[dict[str, Any]]]
) -> nx.Graph:
    simple = nx.Graph()
    simple.add_nodes_from(graph.nodes(data=True))
    for pair, records in records_by_pair.items():
        if len(pair) != 2:
            continue
        u, v = tuple(pair)
        selected = min(records, key=lambda item: (item["length"], item["edge_id"]))
        simple.add_edge(
            u,
            v,
            length=float(selected["length"]),
            edge_id=selected["edge_id"],
        )
    return simple


def project_records(records: Iterable[dict[str, Any]]) -> dict[str, LineString]:
    transformer = Transformer.from_crs("EPSG:4326", "EPSG:5179", always_xy=True)
    return {
        record["edge_id"]: transform(
            transformer.transform, LineString(record["geometry"])
        )
        for record in records
    }


def map_points_to_edges(
    records: list[dict[str, Any]],
    crosswalks: list[dict[str, str]],
    approaches: list[dict[str, str]],
) -> list[dict[str, Any]]:
    projected = project_records(records)
    transformer = Transformer.from_crs("EPSG:4326", "EPSG:5179", always_xy=True)
    records_by_id = {record["edge_id"]: record for record in records}
    by_way: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for record in records:
        for way_id in record["osm_way_ids"]:
            by_way[way_id].append(record)

    approach_groups: dict[str, list[dict[str, str]]] = defaultdict(list)
    for approach in approaches:
        approach_groups[approach["sample_id"]].append(approach)

    mappings: list[dict[str, Any]] = []
    for crosswalk in crosswalks:
        legacy_way = crosswalk.get("osm_way_id", "").strip()
        candidates = by_way.get(int(legacy_way), []) if legacy_way.isdigit() else []
        method = "osm_way_id"
        if not candidates:
            candidates = records
            method = "spatial_nearest"

        point = Point(
            *transformer.transform(float(crosswalk["lon"]), float(crosswalk["lat"]))
        )
        selected = min(
            candidates,
            key=lambda record: projected[record["edge_id"]].distance(point),
        )
        distance = projected[selected["edge_id"]].distance(point)
        mapping_status = "mapped" if distance <= MAX_MAPPING_DISTANCE_M else "mapping_required"
        if mapping_status == "mapped":
            selected["crosswalk_samples"].append(crosswalk["sample_id"])
            selected["approach_ids"].extend(
                approach["approach_id"]
                for approach in approach_groups[crosswalk["sample_id"]]
            )
        mappings.append(
            {
                "sample_id": crosswalk["sample_id"],
                "management_id": crosswalk["management_id"],
                "legacy_proxy_edge_id": crosswalk["osm_proxy_edge_id"],
                "legacy_osm_way_id": legacy_way or None,
                "edge_id": selected["edge_id"] if mapping_status == "mapped" else None,
                "mapping_status": mapping_status,
                "mapping_method": method,
                "distance_m": round(distance, 3),
                "geometry_quality": crosswalk["geometry_quality"],
                "source": "public_data_estimated_geometry",
                "verified": False,
            }
        )

    for record in records:
        record["crosswalk_samples"] = sorted(set(record["crosswalk_samples"]))
        record["approach_ids"] = sorted(set(record["approach_ids"]))
    return mappings


def build_candidates(
    mappings: list[dict[str, Any]],
    approaches: list[dict[str, str]],
    prechecks: list[dict[str, str]],
    view_metadata: list[dict[str, str]],
) -> list[dict[str, Any]]:
    mapping_by_sample = {item["sample_id"]: item for item in mappings}
    approach_by_id = {item["approach_id"]: item for item in approaches}
    view_by_id = {item["approach_id"]: item for item in view_metadata}
    candidates: list[dict[str, Any]] = []

    for precheck in prechecks:
        approach_id = precheck["approach_id"]
        if approach_id not in PILOT_APPROACH_IDS:
            continue
        approach = approach_by_id[approach_id]
        mapping = mapping_by_sample[precheck["sample_id"]]
        view = view_by_id.get(approach_id, {})
        candidates.append(
            {
                "candidate_id": f"ONWAY-{approach_id}-20260915",
                "approach_id": approach_id,
                "sample_id": precheck["sample_id"],
                "edge_id": mapping["edge_id"],
                "mapping_status": mapping["mapping_status"],
                "type": precheck["ai_candidate_label"],
                "source": "ai_candidate",
                "confidence": float(precheck["ai_confidence"]),
                "status": "pending",
                "ai_result": precheck["final_ai_precheck_result"],
                "ai_note": precheck["final_ai_precheck_note"],
                "evidence_date": precheck["evidence_shot_date"] or None,
                "evidence_url": view.get("roadview_link") or approach.get("evidence_url") or None,
                "lat": float(approach["lat"]),
                "lon": float(approach["lon"]),
                "created_at": "2026-09-15T00:00:00+09:00",
                "verified": False,
            }
        )
    return sorted(candidates, key=lambda item: item["candidate_id"])


def apply_demo_overlay(
    graph: nx.MultiGraph,
    simple: nx.Graph,
    records_by_pair: dict[frozenset[Any], list[dict[str, Any]]],
    origin: Any,
    destination: Any,
) -> dict[str, Any]:
    standard = nx.shortest_path(simple, origin, destination, weight="length")
    parallel_counts = {pair: len(records) for pair, records in records_by_pair.items()}
    constraint_pair, accessible_before = select_detour_edge(
        simple, standard, parallel_counts
    )

    accessible_graph = simple.copy()
    accessible_graph.remove_edge(*constraint_pair)
    block_pair, accessible_after = select_detour_edge(
        accessible_graph, accessible_before, parallel_counts
    )

    constraint_record = records_by_pair[pair_key(*constraint_pair)][0]
    constraint_record.update(
        {
            "stairs": True,
            "wheelchair_accessible": False,
            "accessibility_source": "synthetic",
            "confidence": 0.6,
            "verified": False,
            "demo_constraint": "stairs",
        }
    )
    block_record = records_by_pair[pair_key(*block_pair)][0]
    block_record.update(
        {
            "accessibility_source": "synthetic",
            "confidence": 0.6,
            "verified": False,
            "demo_block_target": True,
            "demo_editable": True,
            "name": "현장 상태 변경 실험 구간",
        }
    )

    def edge_ids(path: list[Any]) -> list[str]:
        return [
            str(simple[u][v]["edge_id"])
            for u, v in zip(path, path[1:])
        ]

    after_graph = accessible_graph.copy()
    after_graph.remove_edge(*block_pair)
    return {
        "origin_node": node_id(origin),
        "destination_node": node_id(destination),
        "block_edge": block_record["edge_id"],
        "synthetic_constraint_edges": [constraint_record["edge_id"]],
        "expected": {
            "standard": {
                "distance_m": round(path_distance(simple, standard), 1),
                "edge_ids": edge_ids(standard),
            },
            "accessible_before": {
                "distance_m": round(path_distance(accessible_graph, accessible_before), 1),
                "edge_ids": edge_ids(accessible_before),
            },
            "accessible_after": {
                "distance_m": round(path_distance(after_graph, accessible_after), 1),
                "edge_ids": edge_ids(accessible_after),
            },
        },
    }


def build_graph(
    input_path: Path,
    output_path: Path,
    mappings_path: Path,
    candidates_path: Path,
) -> dict[str, Any]:
    directed = ox.io.load_graphml(input_path)
    graph = ox.convert.to_undirected(directed)
    records, records_by_pair = build_edge_records(graph)
    simple = build_simple_graph(graph, records_by_pair)

    crosswalks = read_csv(PACKAGE_DIR / "selected_crosswalks_40.csv")
    approaches = read_csv(PACKAGE_DIR / "approaches_80_review_queue.csv")
    prechecks = read_csv(PACKAGE_DIR / "ai_precheck_80.csv")
    view_metadata = read_csv(PACKAGE_DIR / "approach_view_metadata.csv")
    mappings = map_points_to_edges(records, crosswalks, approaches)
    candidates = build_candidates(mappings, approaches, prechecks, view_metadata)

    crosswalk_by_sample = {item["sample_id"]: item for item in crosswalks}
    origin_row = crosswalk_by_sample[DEMO_ORIGIN_SAMPLE]
    destination_row = crosswalk_by_sample[DEMO_DESTINATION_SAMPLE]
    origin = nearest_node(graph, float(origin_row["lon"]), float(origin_row["lat"]))
    destination = nearest_node(
        graph, float(destination_row["lon"]), float(destination_row["lat"])
    )
    demo = apply_demo_overlay(graph, simple, records_by_pair, origin, destination)

    node_features: list[dict[str, Any]] = []
    for osm_node, attrs in graph.nodes(data=True):
        current_id = node_id(osm_node)
        is_origin = current_id == demo["origin_node"]
        is_destination = current_id == demo["destination_node"]
        if is_origin:
            name = "출발지 A · CW04"
        elif is_destination:
            name = "목적지 B · CW20"
        else:
            name = f"OSM Node {osm_node}"
        node_features.append(
            {
                "type": "Feature",
                "geometry": {
                    "type": "Point",
                    "coordinates": [float(attrs["x"]), float(attrs["y"])],
                },
                "properties": {
                    "feature_type": "node",
                    "node_id": current_id,
                    "name": name,
                    "node_type": "intersection" if graph.degree[osm_node] >= 3 else "waypoint",
                    "source": "osm",
                    "confidence": 0.9,
                    "verified": False,
                    "display_selectable": is_origin or is_destination,
                },
            }
        )

    edge_features = []
    for record in records:
        props = {key: value for key, value in record.items() if key != "geometry"}
        props["feature_type"] = "edge"
        edge_features.append(
            {
                "type": "Feature",
                "geometry": {
                    "type": "LineString",
                    "coordinates": record["geometry"],
                },
                "properties": props,
            }
        )

    mapped_count = sum(item["mapping_status"] == "mapped" for item in mappings)
    payload = {
        "type": "FeatureCollection",
        "metadata": {
            "name": "NaVi Anyang OSM-derived walking graph",
            "area": "안양역–안양청년1번가–안양1동 남부 생활권",
            "crs": "EPSG:4326",
            "source": "osm_snapshot_and_onway_public_data",
            "osm_snapshot": str(input_path.relative_to(PROJECT_ROOT)).replace("\\", "/"),
            "osm_snapshot_sha256": sha256(input_path),
            "osm_as_of": "2026-09-15T23:59:59Z",
            "accessibility_attributes": "osm_unknown_with_synthetic_demo_overlay",
            "verified": False,
            "disclaimer": (
                "OSM 기반 보행 proxy Graph이며 실제 보도 실측망이 아니다. "
                "접근성 데모 속성은 synthetic이고 사람 검수 후보는 승인 전 경로에 반영하지 않는다."
            ),
            "onway_crosswalks": len(crosswalks),
            "onway_mapped_crosswalks": mapped_count,
            "onway_approaches": len(approaches),
            "review_candidates": len(candidates),
            "demo": demo,
        },
        "features": [*node_features, *edge_features],
    }

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    mappings_path.write_text(
        json.dumps(mappings, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    candidates_path.write_text(
        json.dumps(candidates, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return {
        "nodes": len(node_features),
        "edges": len(edge_features),
        "mapped_crosswalks": mapped_count,
        "review_candidates": len(candidates),
        "demo": demo,
        "output": str(output_path),
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Build the NaVi runtime graph from a frozen OSM snapshot."
    )
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--mappings", type=Path, default=DEFAULT_MAPPINGS)
    parser.add_argument("--candidates", type=Path, default=DEFAULT_CANDIDATES)
    args = parser.parse_args()
    result = build_graph(
        args.input.resolve(),
        args.output.resolve(),
        args.mappings.resolve(),
        args.candidates.resolve(),
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
