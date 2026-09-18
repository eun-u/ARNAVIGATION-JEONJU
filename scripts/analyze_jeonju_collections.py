"""Audit recorded detector outputs; never create calibration or verified scene labels.

Media dependencies are optional review tools (Pillow). No fresh model inference is run.
"""
from __future__ import annotations

import argparse
import collections
import csv
import hashlib
import json
import math
from pathlib import Path
import sys

SUPPORTED = {'person', 'bicycle', 'car', 'motorcycle', 'bus', 'truck', 'traffic_cone'}
THRESHOLD = 0.6


def quantiles(values):
    values = sorted(values)
    return {name: values[min(len(values)-1, max(0, math.ceil(len(values)*q)-1))]
            for name, q in [('min',0),('median',0.5),('p95',0.95),('max',1)]} if values else {}


def analyze(directory):
    manifest = json.loads((directory/'clip_manifest.json').read_text(encoding='utf-8'))
    payload = (directory/'frames.jsonl').read_bytes()
    if hashlib.sha256(payload).hexdigest() != manifest['files']['frames.jsonl']:
        raise ValueError('frames changed after intake')
    rows = [json.loads(line) for line in payload.splitlines()]
    events = []
    recorded = collections.Counter()
    eligible = collections.Counter()
    frame_labels = collections.Counter()
    for row in rows:
        frame_labels.update({d['label'] for d in row['collection']['detections']})
        for detection in row['collection']['detections']:
            recorded[detection['label']] += 1
            if detection['confidence'] >= THRESHOLD:
                eligible[detection['label']] += 1
            events.append({'run':directory.name, 'frame_id':row['frame_id'],
                'elapsed_s':row['collection']['elapsed_ms']/1000,
                'timestamp_ns':row['timestamp_ns'], 'image':row['image'],
                'label':detection['label'], 'confidence':detection['confidence'],
                'supported':detection['label'] in SUPPORTED,
                'class_and_score_pass':detection['label'] in SUPPORTED and detection['confidence'] >= THRESHOLD,
                'bounds':detection['bounds'], 'tracking':row['tracking']})
    gaps = [(b['timestamp_ns']-a['timestamp_ns'])/1e6 for a,b in zip(rows,rows[1:])]
    sensors = [r['collection']['sensors'] for r in rows]
    stats = {
        'run':directory.name, 'frames':len(rows), 'duration_s':manifest['recording_duration_ms']/1000,
        'recorded_detection_counts':dict(recorded), 'frames_with_recorded_label':dict(frame_labels),
        'score_ge_0_6_detection_counts':dict(eligible),
        'supported_score_ge_0_6_detection_counts':{k:eligible[k] for k in sorted(SUPPORTED)},
        'inference_ms':quantiles([r['collection']['inference_ms'] for r in rows]),
        'sample_gap_ms':quantiles(gaps), 'sample_gaps_over_500ms':sum(g>500 for g in gaps),
        'lux':quantiles([s['light']['lux'] for s in sensors if s.get('light')]),
        'location_accuracy_m':quantiles([s['location']['accuracy_m'] for s in sensors if s.get('location')]),
        'location_age_ms':quantiles([s['location']['age_ms'] for s in sensors if s.get('location')]),
        'tracking':dict(collections.Counter(r['tracking'] for r in rows)),
        'calibrated_frames':sum(r.get('calibration') is not None for r in rows),
        'manifest_sha256':hashlib.sha256((directory/'clip_manifest.json').read_bytes()).hexdigest(),
    }
    return rows, events, stats


def save_sheet(items, destination, columns=4, rows_per_page=6):
    from PIL import Image, ImageDraw, ImageFont
    try:
        font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',14)
    except OSError:
        font=ImageFont.load_default()
    per_page=columns*rows_per_page
    for page,start in enumerate(range(0,len(items),per_page),1):
        group=items[start:start+per_page]
        canvas=Image.new('RGB',(columns*240,math.ceil(len(group)/columns)*355),'#eeeeee')
        draw=ImageDraw.Draw(canvas)
        for index,(directory,row,selected) in enumerate(group):
            image=Image.open(directory/row['image']).convert('RGB').rotate(-row['rotation_degrees'],expand=True)
            painter=ImageDraw.Draw(image)
            detections=selected if selected is not None else row['collection']['detections']
            for det in detections:
                box=[det['bounds'][i]*(image.width if i%2==0 else image.height) for i in range(4)]
                color='#ff4444' if det['label'] in SUPPORTED and det['confidence']>=THRESHOLD else '#ffff00'
                painter.rectangle(box,outline=color,width=3)
                painter.text((box[0],max(0,box[1]-17)),f"{det['label']} {det['confidence']:.2f}",fill=color,font=font)
            image.thumbnail((240,320))
            x,y=index%columns*240,index//columns*355
            canvas.paste(image,(x,y))
            short=directory.name.split('-')[3]
            draw.text((x+3,y+320),f"{short} | {row['collection']['elapsed_ms']/1000:.2f}s | f{row['frame_id']}",fill='black',font=font)
            caption=', '.join(f"{d['label']} {d['confidence']:.2f}" for d in detections)
            draw.text((x+3,y+337),caption[:32],fill='black',font=font)
        canvas.save(destination.with_name(destination.stem+f'-{page:02}.jpg'),quality=94)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input-root',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--media-tools',type=Path)
    args=parser.parse_args()
    if args.media_tools: sys.path.insert(0,str(args.media_tools.resolve()))
    args.output.mkdir(parents=True,exist_ok=True)
    all_events=[]; reports=[]; supported_images=[]; cat_examples=[]
    for directory in sorted(args.input_root.glob('NaVi-collect-*')):
        if not (directory/'frames.jsonl').exists(): continue
        rows,events,stats=analyze(directory)
        reports.append(stats);all_events.extend(events)
        if args.media_tools:
            seconds=rows[-1]['collection']['elapsed_ms']/1000
            times=range(0,math.ceil(seconds),5)
            selected=[(directory,min(rows,key=lambda r:abs(r['collection']['elapsed_ms']/1000-t)),None) for t in times]
            save_sheet(selected,args.output/(directory.name+'-overview'))
            for row in rows:
                det=[d for d in row['collection']['detections'] if d['label'] in SUPPORTED and d['confidence']>=THRESHOLD]
                if det: supported_images.append((directory,row,det))
            cat_rows=[r for r in rows if any(d['label']=='cat' and d['confidence']>=THRESHOLD for d in r['collection']['detections'])]
            if cat_rows:
                for i in sorted({round(n*(len(cat_rows)-1)/3) for n in range(4)}):
                    row=cat_rows[i]
                    cat_examples.append((directory,row,[d for d in row['collection']['detections'] if d['label']=='cat']))
    if args.media_tools:
        for label in sorted(SUPPORTED):
            group=[(directory,row,[d for d in det if d['label']==label]) for directory,row,det in supported_images if any(d['label']==label for d in det)]
            save_sheet(group,args.output/('eligible-'+label))
        save_sheet(cat_examples,args.output/'cat-audit-sample')
    report={'analysis_kind':'recorded_detector_output_audit', 'fresh_inference':False,
        'threshold':THRESHOLD,'supported_labels':sorted(SUPPORTED),'ground_truth_verified':False,
        'calibration_status':'pending','routing_or_fusion_replay_executed':False,
        'runs':reports,'total_frames':sum(r['frames'] for r in reports)}
    policy=Path(__file__).resolve().parents[1]/'android/feature/guidance-fusion/src/main/kotlin/kr/co/navi/mobility/guidance/fusion/SpatialGuidanceFusion.kt'
    report['decision_policy_source_sha256']=hashlib.sha256(policy.read_bytes()).hexdigest()
    (args.output/'analysis.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    with (args.output/'detections.csv').open('w',newline='',encoding='utf-8') as file:
        fields=['run','frame_id','elapsed_s','timestamp_ns','image','label','confidence','supported','class_and_score_pass','bounds','tracking']
        writer=csv.DictWriter(file,fieldnames=fields);writer.writeheader();writer.writerows(all_events)
    print(json.dumps({'analysis_file':str(args.output/'analysis.json'),'runs':len(reports),'frames':report['total_frames'],'detections':len(all_events)}))


if __name__=='__main__': main()
