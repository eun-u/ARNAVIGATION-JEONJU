from __future__ import annotations

import argparse
import json
import sys
import tempfile
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
        sha256,
    )
except ImportError:
    from build_graph import (  # type: ignore[no-redef]
        as_list,
        build_edge_records,
        build_simple_graph,
        nearest_node,
        node_id,
        sha256,
    )


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_INPUT = PROJECT_ROOT / "data" / "raw" / "jeonju" / "osm" / "2026" / "map.osm"
DEFAULT_OUTPUT = PROJECT_ROOT / "data" / "processed" / "jeonju" / "candidate_graph.geojson"
DEFAULT_SUMMARY = PROJECT_ROOT / "data" / "processed" / "jeonju" / "candidate_graph_summary.json"
ACTIVE_GRAPH = PROJECT_ROOT / "data" / "processed" / "jeonju_accessibility_graph.geojson"
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
ACCESS_TAGS = ["osmid", "highway", "foot", "access", "footway", "wheelchair", "incline",
               "width", "surface", "smoothness", "sidewalk", "kerb", "crossing", "barrier",
               "entrance", "oneway:foot", "tactile_paving"]
NODE_TAGS = ["highway", "barrier", "entrance", "kerb", "crossing", "wheelchair", "access", "foot"]


def normalized_values(value: Any) -> set[str]:
    return {str(item).strip().lower() for item in as_list(value)}


def is_walkable(attrs: dict[str, Any]) -> bool:
    highways = normalized_values(attrs.get("highway"))
    if not highways.intersection(WALKABLE_HIGHWAYS):
        return False
    foot = normalized_values(attrs.get("foot"))
    access = normalized_values(attrs.get("access"))
    if foot.intersection({"no", "private"}):
        return False
    if access.intersection({"no", "private"}):
        return False
    # This runtime is undirected. Exclude directed foot restrictions conservatively
    # instead of silently making a one-way footway bidirectional.
    if normalized_values(attrs.get("oneway:foot")) - {"no", "0", "false"}:
        return False
    return True


def load_walk_graph(input_path: Path) -> nx.MultiGraph:
    ox.settings.useful_tags_way = sorted(set(ox.settings.useful_tags_way) | set(ACCESS_TAGS))
    ox.settings.useful_tags_node = sorted(set(ox.settings.useful_tags_node) | set(NODE_TAGS))
    directed = ox.graph_from_xml(
        input_path,
        bidirectional=True,
        simplify=False,
        retain_all=True,
    )
    directed.remove_edges_from(
        [(u, v, key) for u, v, key, attrs in directed.edges(keys=True, data=True) if not is_walkable(attrs)]
    )
    isolated = list(nx.isolates(directed))
    directed.remove_nodes_from(isolated)
    directed = ox.simplification.simplify_graph(
        directed, node_attrs_include=NODE_TAGS, edge_attrs_differ=ACCESS_TAGS, track_merged=True,
    )
    return ox.convert.to_undirected(directed)


def demo_contract(
    payload: dict[str, Any],
) -> dict[str, Any]:
    """Candidate-only diagnostic using production constraints, never AI events.

    IDs may change after improved splitting. Match the physical branch by its
    original way and endpoint lineage. Activation requires a new scope revision.
    """
    sys.path.insert(0, str(PROJECT_ROOT / "backend"))
    from app.graph_store import GraphStore
    from app.profiles import ProfileRegistry
    from app.routing import RouteEngine, RouteNotFoundError
    from app.schemas import Coordinate, RouteRequest
    branch = [f["properties"]["edge_id"] for f in payload["features"]
              if 471373642 in f["properties"].get("osm_way_ids", [])]
    with tempfile.TemporaryDirectory(prefix="navi-candidate-") as temp:
        path = Path(temp) / "candidate.geojson"
        path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        # Explicitly diagnostic: the active eight IDs cannot be assumed after regeneration.
        engine = RouteEngine(GraphStore(path), ProfileRegistry(), enforce_poc=False)
        request = RouteRequest(origin=Coordinate(lon=ORIGIN[0],lat=ORIGIN[1]),
                               destination=Coordinate(lon=DESTINATION[0],lat=DESTINATION[1]),profile="demo_jeonju")
        results = {}
        for name, blocked in (("accessible_before", set()), ("accessible_after", set(branch))):
            try:
                r = engine.find_accessible_route(request, blocked)
                results[name] = {"distance_m": r.distance_m, "edge_ids": r.edge_ids,
                                 "stairs_edges": sum(bool(engine.store.get_edge(e)["stairs"]) for e in r.edge_ids)}
            except RouteNotFoundError:
                results[name] = {"status": "no_accessible_route"}
        return {**payload["metadata"]["demo"], "profile": "demo_jeonju", "expected": results,
                "diagnostic_block_way": 471373642, "diagnostic_block_edges": branch,
                "evidence_type": "candidate_graph_contract_test_only", "scope_enforced": False,
                "activation_requires_scope_revision": True}


def build_graph(input_path: Path, output_path: Path, summary_path: Path) -> dict[str, Any]:
    if ACTIVE_GRAPH.resolve() in (output_path.resolve(), summary_path.resolve()):
        raise ValueError("Active P0 Graph is pinned. Build a candidate and review lineage/scope before activation.")
    graph = load_walk_graph(input_path)
    records, records_by_pair = build_edge_records(graph)
    simple = build_simple_graph(graph, records_by_pair)

    origin = nearest_node(graph, *ORIGIN)
    destination = nearest_node(graph, *DESTINATION)
    if not nx.has_path(simple, origin, destination):
        raise RuntimeError("provided Jeonju origin and destination are disconnected")
    demo = {"origin_node": node_id(origin), "destination_node": node_id(destination)}

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
                    "raw_accessibility_tags": {key: attrs[key] for key in NODE_TAGS if key in attrs},
                },
            }
        )

    edge_features: list[dict[str, Any]] = []
    highway_counts: defaultdict[str, int] = defaultdict(int)
    for record in records:
        # Preserve barrier evidence at both endpoints. Unknown barriers are not
        # inferred passable; this affects candidates only, not the pinned graph.
        endpoint_tags = [graph.nodes[int(record[key])] for key in ("osm_u", "osm_v")]
        if any(normalized_values(a.get("access")) & {"no", "private"} or
               normalized_values(a.get("foot")) & {"no", "private"} or
               (a.get("barrier") and a.get("barrier") != "entrance") for a in endpoint_tags):
            record.update(blocked=True, block_reason="barrier_review_required")
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
            "osm_snapshot": input_path.as_posix(),
            "osm_snapshot_sha256": sha256(input_path),
            "accessibility_attributes": "osm_tags_only_unknown_unless_explicit",
            "verified": False,
            "field_test_only": True,
            "graph_update_allowed": False,
            "candidate_only": True,
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
    demo = demo_contract(payload)
    payload["metadata"]["demo"] = demo
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )

    result = {
        "input": str(input_path),
        "output": str(output_path),
        "nodes": len(node_features),
        "edges": len(edge_features),
        "connected_components": nx.number_connected_components(simple),
        "highway_counts": dict(sorted(highway_counts.items())),
        "crosswalk_status": crosswalk_status,
        "jeonju_crosswalk_rows": crosswalk_rows,
        "verified": False,
        "graph_update_allowed": False,
        "candidate_only": True,
        "oneway_foot_policy": "excluded_pending_directed_runtime",
        "demo": demo,
    }
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="Build an unactivated Jeonju candidate; preserve the pinned P0 runtime graph.")
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--summary", type=Path, default=DEFAULT_SUMMARY)
    args = parser.parse_args()
    result = build_graph(args.input.resolve(), args.output.resolve(), args.summary.resolve())
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
