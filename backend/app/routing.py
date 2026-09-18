from __future__ import annotations

from math import asin, ceil, cos, radians, sin, sqrt
from collections.abc import Mapping
from typing import Any

import networkx as nx

from .constraints import edge_constraint_reasons
from .graph_store import GraphStore
from .profiles import ProfileRegistry
from .schemas import AccessibilityProfile, Coordinate, ExcludedEdge, ProvenanceSummary, RouteRequest, RouteResult
from .poc_scope import PocScope, project


class RouteNotFoundError(RuntimeError):
    def __init__(self, status: str, message: str, reason_code: str | None = None) -> None:
        super().__init__(message)
        self.status, self.message = status, message
        self.reason_code = reason_code


def haversine_m(a: Coordinate, b: Coordinate) -> float:
    radius = 6_371_000
    p1, p2 = radians(a.lat), radians(b.lat)
    dp, dl = radians(b.lat - a.lat), radians(b.lon - a.lon)
    value = sin(dp / 2) ** 2 + cos(p1) * cos(p2) * sin(dl / 2) ** 2
    return 2 * radius * asin(sqrt(value))


class RouteEngine:
    def __init__(self, store: GraphStore, profiles: ProfileRegistry, *, enforce_poc: bool = True) -> None:
        self.store, self.profiles = store, profiles
        self.scope = PocScope(store) if enforce_poc and store.metadata.get("osm_snapshot", "").startswith("data/raw/jeonju/") else None

    def _source(self):
        return self.scope.snapshot(self.store) if self.scope else self.store.snapshot()

    def find_shortest_route(self, request: RouteRequest) -> RouteResult:
        graph = self._source()
        origin_node, destination_node = self._snap_nodes(graph, request)
        try:
            nodes = nx.shortest_path(graph, origin_node, destination_node, weight="length", method="dijkstra")
        except (nx.NetworkXNoPath, nx.NodeNotFound) as exc:
            raise RouteNotFoundError("no_route", "일반 경로를 찾을 수 없습니다.") from exc
        return self._build_result(graph, nodes, "standard", "default", [], [])

    def find_accessible_route(
        self,
        request: RouteRequest,
        excluded_edge_ids: set[str] | None = None,
        *,
        edge_attribute_overlays: Mapping[str, Mapping[str, Any]] | None = None,
        blocked_positions: Mapping[str, tuple[Coordinate, float]] | None = None,
    ) -> RouteResult:
        profile = self.profiles.get(request.profile)
        temporary_blocks = excluded_edge_ids or set()
        attribute_overlays = edge_attribute_overlays or {}
        source = self._source()
        origin_node, destination_node = self._snap_nodes(source, request)
        accessible = nx.MultiGraph()
        accessible.add_nodes_from(source.nodes(data=True))
        excluded: list[ExcludedEdge] = []
        for from_node, to_node, key, attrs in source.edges(data=True, keys=True):
            edge_id = str(attrs["edge_id"])
            effective_attrs = {**attrs, **attribute_overlays.get(edge_id, {})}
            reasons = edge_constraint_reasons(effective_attrs, profile)
            if edge_id in temporary_blocks:
                # When already inside an impacted edge, allow only the measured safe half
                # leading away from the obstacle. Never teleport to that half's endpoint.
                location = (blocked_positions or {}).get(edge_id)
                safe_partial = bool(location and attrs.get("poc_partial") and
                                    project(location[0], attrs["geometry"]).distance_m > location[1])
                if safe_partial:
                    effective_attrs = {**effective_attrs,"partial_avoidance_escape":True}
                else:
                    reasons = [*reasons, "session_blocked"]
            if reasons:
                excluded.append(ExcludedEdge(edge_id=edge_id, name=str(effective_attrs.get("name") or edge_id), reasons=reasons))
            else:
                accessible.add_edge(from_node, to_node, key=key, **effective_attrs)
        try:
            nodes = nx.shortest_path(accessible, origin_node, destination_node, weight="length", method="dijkstra")
        except (nx.NetworkXNoPath, nx.NodeNotFound) as exc:
            raise RouteNotFoundError("no_accessible_route", f"{profile.name} 조건을 만족하는 접근 가능한 경로가 없습니다.", "no_route_within_poc" if self.scope else None) from exc
        route_reasons = list(dict.fromkeys(reason for edge in excluded for reason in edge.reasons))
        return self._build_result(accessible, nodes, "accessible", profile.name, excluded, route_reasons)

    def _snap_nodes(self, graph: nx.MultiGraph, request: RouteRequest) -> tuple[str, str]:
        if self.scope:
            def insert(point: Coordinate, name: str) -> str:
                edge_id, _ = self.scope.match(self.store, point)
                candidates = [(project(point, a["geometry"]),u,v,k,a) for u,v,k,a in graph.edges(keys=True,data=True) if a["edge_id"] == edge_id]
                p,u,v,k,a = min(candidates, key=lambda item:item[0].distance_m)
                # Geometry orientation follows stored from_node, not NetworkX iteration order.
                u,v = a["from_node"], a["to_node"]
                if p.fraction < 1e-6:
                    return u
                if p.fraction > 1-1e-6:
                    return v
                graph.remove_edge(u,v,k)
                graph.add_node(name, lat=p.point.lat, lon=p.point.lon)
                for left,right,geometry,fraction,suffix in [(u,name,p.left,p.fraction,"a"),(name,v,p.right,1-p.fraction,"b")]:
                    graph.add_edge(left,right,key=f"{k}:{name}:{suffix}", **{**a,"from_node":left,"to_node":right,"geometry":geometry,"length":a["length"]*fraction,"poc_partial":True})
                return name
            return insert(request.origin,"P0_CURRENT"), insert(request.destination,"P0_DESTINATION")
        def nearest(point: Coordinate) -> str:
            return min(graph.nodes, key=lambda node_id: haversine_m(point, Coordinate(lat=float(graph.nodes[node_id]["lat"]), lon=float(graph.nodes[node_id]["lon"]))))
        return nearest(request.origin), nearest(request.destination)

    @staticmethod
    def _edge_for_step(graph: nx.MultiGraph, from_node: str, to_node: str) -> dict[str, Any]:
        candidates = graph.get_edge_data(from_node, to_node)
        if not candidates:
            raise RouteNotFoundError("no_route", "경로 Edge를 해석할 수 없습니다.")
        return min(candidates.values(), key=lambda edge: (float(edge.get("length", float("inf"))), str(edge.get("edge_id", ""))))

    def _build_result(self, graph: nx.MultiGraph, nodes: list[str], route_type: str, profile_name: str, excluded_edges: list[ExcludedEdge], reasons: list[str]) -> RouteResult:
        edge_ids: list[str] = []
        geometry: list[list[float]] = []
        total_distance = 0.0
        used_edges: list[dict[str, Any]] = []
        segments: list[dict[str, Any]] = []
        for from_node, to_node in zip(nodes, nodes[1:]):
            attrs = self._edge_for_step(graph, from_node, to_node)
            edge_ids.append(str(attrs["edge_id"]))
            total_distance += float(attrs["length"])
            used_edges.append(attrs)
            coordinates = [list(map(float, point)) for point in attrs["geometry"]]
            if attrs.get("from_node") != from_node:
                coordinates.reverse()
            segments.append({"edge_id":str(attrs["edge_id"]), "physical_segment_id":str(attrs["edge_id"]), "geometry":coordinates, "length_m":float(attrs["length"]),"partial_avoidance_escape":bool(attrs.get("partial_avoidance_escape"))})
            geometry.extend(coordinates[1:] if geometry and geometry[-1] == coordinates[0] else coordinates)

        sources = sorted({str(edge.get("source", "unknown")) for edge in used_edges})
        accessibility_sources = sorted({str(edge.get("accessibility_source", edge.get("source", "unknown"))) for edge in used_edges})
        verified_edges = sum(bool(edge.get("verified")) for edge in used_edges)
        unverified_edges = len(used_edges) - verified_edges
        contains_synthetic = "synthetic" in sources or "synthetic" in accessibility_sources
        warnings = ["턱·경사·폭 임계값은 PoC 실험 설정이며 법적 기준이나 현장 실측값이 아닙니다."]
        if contains_synthetic:
            warnings.append("이 경로에는 현장 실측이 아닌 synthetic 접근성 속성이 포함되어 있습니다.")
        if unverified_edges:
            warnings.append(f"사용 Edge {unverified_edges}개는 실제 현장 검증을 완료하지 않았습니다.")
        speed_m_per_minute = 75 if route_type == "standard" else 60
        warnings.append(f"예상 시간은 분당 {speed_m_per_minute}m의 가정 속도로 계산했습니다.")
        return RouteResult(
            distance_m=round(total_distance, 1), estimated_minutes=max(1, ceil(total_distance / speed_m_per_minute)),
            route_type=route_type, profile=profile_name, origin_node=nodes[0], destination_node=nodes[-1], node_ids=nodes,
            edge_ids=edge_ids, geometry=geometry, excluded_edges=excluded_edges, reasons=reasons, warnings=warnings,
            provenance=ProvenanceSummary(sources=sources, accessibility_sources=accessibility_sources, contains_synthetic=contains_synthetic, verified_edges=verified_edges, unverified_edges=unverified_edges),
            calculated_origin=Coordinate(lat=graph.nodes[nodes[0]]["lat"], lon=graph.nodes[nodes[0]]["lon"]),
            segments=segments,
            **(self.scope.revisions() if self.scope else {}),
        )
