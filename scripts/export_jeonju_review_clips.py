"""Export visible-only review previews. Output is NOT an ARCore or precision replay bundle."""
import argparse
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import sys


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input-root',type=Path,required=True)
    parser.add_argument('--selection',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--media-tools',type=Path,required=True)
    args=parser.parse_args()
    sys.path.insert(0,str(args.media_tools.resolve()))
    import av
    from PIL import ImageDraw, ImageFont
    config=json.loads(args.selection.read_text(encoding='utf-8'))
    if config['verified'] is not False or config['routing_validation_passed'] is not False:
        raise ValueError('Review selections cannot claim field verification')
    args.output.mkdir(parents=True,exist_ok=True)
    font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',17)
    records=[]
    for item in config['cases']:
        source=(args.input_root/item['run']).resolve()
        if not source.is_relative_to(args.input_root.resolve()): raise ValueError('unsafe source')
        if not item['id'].replace('_','').isalnum(): raise ValueError('unsafe output id')
        rows=[json.loads(line) for line in (source/'frames.jsonl').read_text(encoding='utf-8').splitlines()]
        selected=[r for r in rows if item['start_s']<=r['collection']['elapsed_ms']/1000<item['end_s']]
        target=args.output/(item['id']+'.mp4')
        times=[]
        with av.open(str(source/'recording.mp4')) as original, av.open(str(target),'w',options={'movflags':'+faststart'}) as output:
            video=original.streams.video[0]
            original.seek(max(0,int((item['start_s']-2)*av.time_base)),backward=True)
            stream=output.add_stream('libx264',rate=30)
            stream.width=480;stream.height=640;stream.pix_fmt='yuv420p'
            stream.options={'crf':'20','preset':'fast'}
            for frame in original.decode(video):
                if frame.time is None or frame.time<item['start_s']:continue
                if frame.time>=item['end_s']:break
                image=frame.to_image().rotate(-90,expand=True)
                if image.size!=(480,640):raise ValueError('unexpected source video dimensions')
                draw=ImageDraw.Draw(image)
                draw.rectangle((0,0,480,26),fill='black')
                draw.text((5,3),f"REVIEW ONLY | source {item['run'].split('-')[3]} {frame.time:.2f}s",fill='white',font=font)
                new=av.VideoFrame.from_image(image);new.pts=len(times);new.time_base=Fraction(1,30)
                for packet in stream.encode(new):output.mux(packet)
                times.append(frame.time)
            for packet in stream.encode():output.mux(packet)
        if not times:raise ValueError('empty preview')
        with av.open(str(target)) as preview:
            decoded=sum(1 for _ in preview.decode(video=0))
        if decoded!=len(times):raise ValueError('preview frame count mismatch')
        record=dict(item,source_frame_ids=[r['frame_id'] for r in selected],
            source_timestamp_ns=[r['timestamp_ns'] for r in selected],
            original_cpu_frames_sha256=hashlib.sha256((source/'frames.jsonl').read_bytes()).hexdigest(),
            output_file=target.name,output_sha256=hashlib.sha256(target.read_bytes()).hexdigest(),
            source_video_first_time_s=times[0],source_video_last_time_s=times[-1],
            output_duration_s=len(times)/30,decoded_frames=decoded,
            preview_only=True,contains_arcore_tracks=False,replay_eligible=False,
            preview_frame_rate=30,source_variable_frame_timing_preserved=False)
        (args.output/(item['id']+'.source_frames.jsonl')).write_text('\n'.join(json.dumps(r) for r in selected)+'\n',encoding='utf-8')
        records.append(record)
        (args.output/'preview_manifest.json').write_text(json.dumps({'schema':'navi.review_previews.v1','verified':False,'clips':records},ensure_ascii=False,indent=2),encoding='utf-8')
        print(item['id'],decoded,'frames',len(selected),'source CPU frames',flush=True)


if __name__=='__main__':main()
