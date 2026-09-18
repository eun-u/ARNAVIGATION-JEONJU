"""Compare existing raw GPS/AR tracks with the pinned course. Diagnostic, not calibration."""
from __future__ import annotations
import argparse
import collections
import hashlib
import json
import math
from pathlib import Path
import sys

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'backend'))
from app.graph_store import GraphStore
from app.poc_scope import PocScope, project
from app.schemas import Coordinate


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--input-root',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    store=GraphStore(ROOT/'data/processed/jeonju_accessibility_graph.geojson');scope=PocScope(store)
    start=store.get_node(scope.data['origin_node']);lat,lon=start['lat'],start['lon']
    sy=111195.08;sx=sy*math.cos(math.radians(lat))
    runs=[]
    for file in sorted(args.input_root.glob('NaVi*/frames.jsonl')):
        rows=[json.loads(line) for line in file.read_text(encoding='utf-8').splitlines()]
        pairs=[];seen=set();nearest=None;coverage=collections.Counter()
        for r in rows:
            gps=r['collection']['sensors'].get('location')
            if not gps or r['tracking']!='TRACKING':continue
            e,n=(gps['lon']-lon)*sx,(gps['lat']-lat)*sy
            d=math.hypot(e,n)
            if nearest is None or d<nearest['raw_gps_distance_to_start_m']:
                nearest={'frame_id':r['frame_id'],'elapsed_ms':r['collection']['elapsed_ms'],
                    'image':r['image'],'raw_gps_distance_to_start_m':d,'reported_accuracy_m':gps['accuracy_m']}
            point=Coordinate(lat=gps['lat'],lon=gps['lon'])
            distance,edge=min((project(point,store.get_edge(edge)['geometry']).distance_m,edge) for edge in scope.allowed)
            if distance<=3.0:coverage[edge]+=1
            identity=gps['observed_at_epoch_ms']
            if gps['accuracy_m']<=10 and gps['age_ms']<=2000 and identity not in seen:
                seen.add(identity);pairs.append((r['pose'][0],-r['pose'][2],e,n))
        fit=None
        if len(pairs)>=3:
            means=[sum(x[i] for x in pairs)/len(pairs) for i in range(4)]
            dot=cross=0.0
            for x,y,e,n in pairs:
                x-=means[0];y-=means[1];e-=means[2];n-=means[3]
                dot+=x*e+y*n;cross+=x*n-y*e
            angle=math.atan2(cross,dot);c,s=math.cos(angle),math.sin(angle)
            tx,ty=means[2]-c*means[0]+s*means[1],means[3]-s*means[0]-c*means[1]
            residuals=[math.hypot(c*x-s*y+tx-e,s*x+c*y+ty-n) for x,y,e,n in pairs]
            fit={'samples':len(pairs),'rotation_degrees':math.degrees(angle),
                 'translation_east_m':tx,'translation_north_m':ty,
                 'raw_gps_residual_rmse_m':math.sqrt(sum(d*d for d in residuals)/len(residuals)),
                 'note':'Rigid fit to noisy GPS, not independent error or registration evidence'}
        runs.append({'run':file.parent.name,'frames_sha256':hashlib.sha256(file.read_bytes()).hexdigest(),
            'start_reference_candidate':nearest,'raw_gps_nearest_edge_samples_within_3m':dict(coverage),
            'diagnostic_ar_to_raw_gps_fit':fit,'verified':False})
    result={'kind':'course_correspondence_diagnostic','scope_revision':scope.data['scope_revision'],
        'graph_sha256':scope.data['graph_sha256'],'alignment_applied_to_originals':False,
        'field_verified':False,'runs':runs}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'output':str(args.output),'runs':len(runs),'rmse_to_raw_gps_m':[
        round(r['diagnostic_ar_to_raw_gps_fit']['raw_gps_residual_rmse_m'],2) if r['diagnostic_ar_to_raw_gps_fit'] else None for r in runs]}))


if __name__=='__main__':main()
