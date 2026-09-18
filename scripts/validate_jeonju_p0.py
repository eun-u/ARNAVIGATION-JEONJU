"""Recompute P0 machine checks through the production RouteEngine (no AI claims)."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT / "backend"))
from app.graph_store import GraphStore
from app.profiles import ProfileRegistry
from app.routing import RouteEngine, RouteNotFoundError
from app.schemas import Coordinate, RouteRequest


def validate(root: Path = ROOT) -> dict:
    m = json.loads((root / "data/processed/jeonju/p0_manifest.json").read_text(encoding="utf-8"))
    for name,digest in m["files"].items():
        if hashlib.sha256((root/m["raw_directory"]/name).read_bytes()).hexdigest() != digest:
            raise ValueError(f"source_hash_mismatch: {name}")
    store = GraphStore(root/m["graph_path"])
    engine = RouteEngine(store,ProfileRegistry())
    s = engine.scope
    assert s and s.revisions() == {k:m[k] for k in s.revisions()}
    def pos(node):
        a=store.get_node(node)
        return Coordinate(lat=a["lat"],lon=a["lon"])
    q=RouteRequest(origin=pos(s.data["origin_node"]),destination=pos(s.data["destination_node"]),profile="demo_jeonju")
    base=engine.find_accessible_route(q)
    # Verification-only target; never passed to camera, perception, fusion or runtime bootstrap.
    branch="OSM_E_471373642_95ec0c4e13"
    detour=engine.find_accessible_route(q,{branch})
    assert base.distance_m == 130.7 and detour.distance_m == 153.5
    assert len(base.edge_ids)==5 and len(detour.edge_ids)==4
    assert set(base.edge_ids+detour.edge_ids)==s.allowed
    for e in base.edge_ids+detour.edge_ids: assert not store.get_edge(e)["stairs"]
    for request,blocks in [(q,set(base.edge_ids+detour.edge_ids)),(q.model_copy(update={"profile":"wheelchair"}),set())]:
        try: engine.find_accessible_route(request,blocks)
        except RouteNotFoundError as e: assert e.reason_code=="no_route_within_poc"
        else: raise AssertionError("expected_no_accessible_route")
    result={"status":"passed","evidence_type":"data_and_route_contract_only",**s.revisions(),"checksum_verified":m["checksum_verified"],
        "base":base.model_dump(mode="json"),"detour":detour.model_dump(mode="json"),"strict_unknown_rejected":True,
        "no_route_within_poc":True,"steps_on_routes":0,"field_crossing_check":"pending"}
    return result


if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--output",type=Path,default=ROOT/"artifacts/jeonju/validation.json")
    args=parser.parse_args()
    result=validate()
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"status":result["status"],"base_m":result["base"]["distance_m"],"detour_m":result["detour"]["distance_m"],"output":str(args.output)}))
