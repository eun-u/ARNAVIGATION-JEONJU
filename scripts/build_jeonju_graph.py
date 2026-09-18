from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path
from typing import Any

import networkx as nx
import osmnx as ox

try:
    from scripts.build_graph import (
        as_list,
        build_edge_records,
        build_simple_graph,
        nearest_node,
        node_id,
        pair_key,
        path_distance,
        select_detour_edge,
        sha256,
    )
except ImportError:
    from build_graph import (  # type: ignore[no-redef]
        as_list,
        build_edge_records,
        build_simple_graph,
        nearest_node,
        node_id,
        pair_key,
        path_distance,
        select_detour_edge,
        sha256,
    )


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_INPUT = PROJECT_ROOT / "data" / "raw" / "jeonju" / "osm" / "2026" / "map.osm"
DEFAULT_OUTPUT = PROJECT_ROOT / "data" / "processed" / "jeonju_accessibility_graph.geojson"
DEFAULT_SUMMARY = PROJECT_ROOT / "data" / "processed" / "jeonju_graph_build_summary.json"
DEFAULT_CROSSWALK_SUMMARY = (
    PROJECT_ROOT / "data" / "raw" / "jeonju" / "source_manifest.json"
)

ORIGIN = (127.13198610, 35.84635140)
DESTINATION = (127.13128260, 35.84578700)
WALKABLE_HIGHWAYS = {
    "footway",
    "living_street",
    "path",
    "pedestrian",
    "residential",
    "service",
    "steps",
    "tertiary",
    "tertiary_link",
    "track",
    "unclassified",
}


def normalized_values(value: Any) -> set[str]:
    return {str(item).strip().lower() for item in as_list(value)}


def is_walkable(attrs: dict[str, Any]) -> bool:
    highways = normalized_values(attrs.get("highway"))
    if not highways.intersection(WALKABLE_HIGHWAYS):
        return False
    foot = normalized_values(attrs.get("foot"))
    access = normalized_values(attrs.get("access"))
    if foot.intersection({"yes", "designated", "permissive"}):
        return True
    if foot.intersection({"no", "private"}):
        return False
    if access.intersection({"no", "private"}):
        return False
    return True


def load_walk_graph(input_path: Path) -> nx.MultiGraph:
    directed = ox.graph_from_xml(
        input_path,
        bidirectional=True,
        simplify=True,
        retain_all=True,
    )
    directed.remove_edges_from(
        [(u, v, key) for u, v, key, attrs in directed.edges(keys=True, data=True) if not is_walkable(attrs)]
    )
    isolated = list(nx.isolates(directed))
    directed.remove_nodes_from(isolated)
    return ox.convert.to_undirected(directed)


def demo_contract(
    simple: nx.Graph,
    records_by_pair: dict[frozenset[Any], list[dict[str, Any]]],
    origin: Any,
    destination: Any,
) -> dict[str, Any]:
    route = nx.shortest_path(simple, origin, destination, weight="length")
    parallel_counts = {pair: len(records) for pair, records in records_by_pair.items()}
    block_pair, reroute = select_detour_edge(simple, route, parallel_counts)
    block_record = min(
        records_by_pair[pair_key(*block_pair)],
        key=lambda item: (item["length"], item["edge_id"]),
    )
    block_record.update(
        {
            "demo_block_target": True,
            "demo_editable": True,
            "name": "전북대 현장 상태 변경 실험 구간",
        }
    )

    def edge_ids(path: list[Any]) -> list[str]:
        return [str(simple[u][v]["edge_id"]) for u, v in zip(path, path[1:])]

    baseline_distance = round(path_distance(simple, route), 1)
    return {
        "origin_node": node_id(origin),
        "destination_node": node_id(destination),
        "block_edge": block_record["edge_id"],
        "synthetic_constraint_edges": [],
        "expected": {
            "standard": {"distance_m": baseline_distance, "edge_ids": edge_ids(route)},
            "accessible_before": {"distance_m": baseline_distance, "edge_ids": edge_ids(route)},
            "accessible_after": {
                "distance_m": round(path_distance(simple, reroute), 1),
                "edge_ids": edge_ids(reroute),
            },
        },
    }


def build_graph(input_path: Path, output_path: Path, summary_path: Path) -> dict[str, Any]:
    graph = load_walk_graph(input_path)
    records, records_by_pair = build_edge_records(graph)
    simple = build_simple_graph(graph, records_by_pair)

    origin = nearest_node(graph, *ORIGIN)
    destination = nearest_node(graph, *DESTINATION)
    if not nx.has_path(simple, origin, destination):
        raise RuntimeError("provided Jeonju origin and destination are disconnected")
    demo = demo_contract(simple, records_by_pair, origin, destination)

    node_features: list[dict[str, Any]] = []
    for osm_node, attrs in graph.nodes(data=True):
        current_id = node_id(osm_node)
        is_origin = current_id == demo["origin_node"]
        is_destination = current_id == demo["destination_node"]
        if is_origin:
            name = "전북대 실증 출발점"
        elif is_destination:
            name = "전북대 실증 도착점"
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
                    "source": "osm_user_handoff",
                    "confidence": 0.7,
                    "verified": False,
                    "display_selectable": is_origin or is_destination,
                },
            }
        )

    edge_features: list[dict[str, Any]] = []
    highway_counts: defaultdict[str, int] = defaultdict(int)
    for record in records:
        for highway in record.get("highway") or ["unknown"]:
            highway_counts[str(highway)] += 1
        props = {key: value for key, value in record.items() if key != "geometry"}
        props.update(
            {
                "feature_type": "edge",
                "source": "osm_user_handoff",
                "verified": False,
            }
        )
        edge_features.append(
            {
                "type": "Feature",
                "geometry": {"type": "LineString", "coordinates": record["geometry"]},
                "properties": props,
            }
        )

    crosswalk_status = "not_prepared"
    crosswalk_rows = 0
    if DEFAULT_CROSSWALK_SUMMARY.exists():
        source_manifest = json.loads(DEFAULT_CROSSWALK_SUMMARY.read_text(encoding="utf-8"))
        crosswalk_status = source_manifest.get("crosswalks", {}).get("status", crosswalk_status)
        crosswalk_rows = int(source_manifest.get("crosswalks", {}).get("jeonju_row_count", 0))

    payload = {
        "type": "FeatureCollection",
        "metadata": {
            "name": "NaVi Jeonju JBNU OSM-derived walking graph",
            "area": "전북대학교 전주캠퍼스 및 주변",
            "crs": "EPSG:4326",
            "source": "user_provided_osm_export",
            "osm_snapshot": input_path.relative_to(PROJECT_ROOT).as_posix(),
            "osm_snapshot_sha256": sha256(input_path),
            "accessibility_attributes": "osm_tags_only_unknown_unless_explicit",
            "verified": False,
            "field_test_only": True,
            "graph_update_allowed": False,
            "crosswalk_source_status": crosswalk_status,
            "jeonju_crosswalk_rows": crosswalk_rows,
            "disclaimer": (
                "사용자 제공 OSM 기반 보행 proxy Graph이며 실제 보도 실측망이 아니다. "
                "전주시 횡단보도 행이 제공 파일에 없어 횡단보도 접근성 속성은 병합하지 않았다."
            ),
            "demo": demo,
        },
        "features": [*node_features, *edge_features],
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )

    result = {
        "input": input_path.relative_to(PROJECT_ROOT).as_posix(),
        "output": output_path.relative_to(PROJECT_ROOT).as_posix(),
        "nodes": len(node_features),
        "edges": len(edge_features),
        "connected_components": nx.number_connected_components(simple),
        "highway_counts": dict(sorted(highway_counts.items())),
        "crosswalk_status": crosswalk_status,
        "jeonju_crosswalk_rows": crosswalk_rows,
        "verified": False,
        "graph_update_allowed": False,
        "demo": demo,
    }
    summary_path.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the Jeonju/JBNU runtime graph.")
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--summary", type=Path, default=DEFAULT_SUMMARY)
    args = parser.parse_args()
    result = build_graph(args.input.resolve(), args.output.resolve(), args.summary.resolve())
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
