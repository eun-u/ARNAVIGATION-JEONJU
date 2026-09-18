"""Immutable service boundary and metre-based polyline matching for the Jeonju PoC."""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from math import cos, radians, hypot
from pathlib import Path

from .schemas import Coordinate


class PocContractError(ValueError):
    def __init__(self, reason_code: str, message: str | None = None, status_code: int = 409,
                 *, current_route_revision: int | None = None, current_graph_revision: int | None = None):
        super().__init__(message or reason_code)
        self.reason_code, self.status_code = reason_code, status_code
        self.current_route_revision = current_route_revision
        self.current_graph_revision = current_graph_revision


def graph_digest(path: Path) -> str:
    """Pin Git's UTF-8/LF bytes; Windows autocrlf is not a dataset revision."""
    return hashlib.sha256(path.read_bytes().replace(b'\r\n', b'\n')).hexdigest()


@dataclass
class Projection:
    point: Coordinate
    distance_m: float
    fraction: float
    left: list[list[float]]
    right: list[list[float]]


def project(point: Coordinate, geometry: list[list[float]]) -> Projection:
    """Local tangent plane, appropriate for this sub-kilometre campus scope."""
    sx, sy = 111195.08 * cos(radians(point.lat)), 111195.08
    lengths = [hypot((b[0]-a[0])*sx, (b[1]-a[1])*sy) for a, b in zip(geometry, geometry[1:])]
    total, before, best = sum(lengths), 0.0, None
    for i, (a, b, length) in enumerate(zip(geometry, geometry[1:], lengths)):
        ax, ay = (a[0]-point.lon)*sx, (a[1]-point.lat)*sy
        dx, dy = (b[0]-a[0])*sx, (b[1]-a[1])*sy
        t = max(0.0, min(1.0, -(ax*dx+ay*dy)/(length*length))) if length else 0.0
        distance = hypot(ax+t*dx, ay+t*dy)
        q = [a[0]+t*(b[0]-a[0]), a[1]+t*(b[1]-a[1])]
        candidate = Projection(Coordinate(lon=q[0], lat=q[1]), distance, (before+t*length)/total if total else 0,
                               geometry[:i+1]+[q], [q]+geometry[i+1:])
        if best is None or candidate.distance_m < best.distance_m:
            best = candidate
        before += length
    if best is None:
        raise PocContractError("invalid_geometry")
    return best


class PocScope:
    def __init__(self, store):
        self.data = json.loads((Path(__file__).parent / "config/jeonju_scope.json").read_text(encoding="utf-8"))
        actual_hash = graph_digest(store.path)
        if actual_hash != self.data["graph_sha256"]:
            raise PocContractError("graph_hash_mismatch", "Revalidate OSM lineage and revise jeonju_scope.json before using a changed Graph")
        self.allowed = frozenset(self.data["allowed_edge_ids"])
        if len(self.allowed) != 8:
            raise PocContractError("scope_invalid")
        for edge_id in self.allowed:
            edge = store.get_edge(edge_id)
            if edge.get("stairs") or edge.get("highway") != ["footway"]:
                raise PocContractError("scope_contains_non_walkway")

    def snapshot(self, store):
        graph = store.snapshot()
        graph.remove_edges_from([(u,v,k) for u,v,k,a in graph.edges(keys=True,data=True) if a["edge_id"] not in self.allowed])
        graph.remove_nodes_from([n for n, degree in graph.degree if degree == 0])
        return graph

    def match(self, store, point: Coordinate, accuracy_m: float = 0.25, progress_edge_id: str | None = None):
        ranked = sorted(((project(point, store.get_edge(e)["geometry"]), e) for e in self.allowed), key=lambda p:p[0].distance_m)
        projection, edge = ranked[0]
        if projection.distance_m > self.data["max_snap_distance_m"]:
            raise PocContractError("position_outside_poc", status_code=422)
        # Endpoints shared by adjacent edges are the same position, not an arbitrary edge choice.
        nearby = [(p,e) for p,e in ranked if p.distance_m <= projection.distance_m + accuracy_m + self.data["map_error_m"]]
        if len(nearby) > 1:
            endpoints = []
            for p,e in nearby:
                a = store.get_edge(e)
                endpoints.append(a["from_node"] if p.fraction < 0.015 else a["to_node"] if p.fraction > 0.985 else None)
            if None in endpoints or len(set(endpoints)) != 1:
                raise PocContractError("position_ambiguous", status_code=422)
        if progress_edge_id and progress_edge_id not in [e for _,e in nearby]:
            raise PocContractError("progress_mismatch", status_code=422)
        return edge, projection

    def revisions(self):
        return {key:self.data[key] for key in ("region_id", "dataset_revision", "scope_revision", "graph_sha256")}
