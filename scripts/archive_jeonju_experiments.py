"""Package existing Jeonju evidence without inference, relabeling, or raw-data mutation.

Requires the local five collection bundles and the existing review artifacts.
Only Pillow is needed (the intake tools directory is supported as a fallback).
"""
from __future__ import annotations

import collections
import csv
import hashlib
import html
import json
import math
from pathlib import Path
import re
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/jeonju-experiment-archive-20260919"
INTAKE = ROOT / "artifacts/jeonju/runs/field-intake-20260919"
REVIEW = ROOT / "data/ai-evaluation/runs/jeonju-field-review-20260919"
CONE = ROOT / "data/ai-evaluation/runs/jeonju-cone-20260919"
STILLS = ROOT / "artifacts/jeonju/runs/demo-stills-20260919"
sys.path.insert(0, str(INTAKE / "tools"))
from PIL import Image  # noqa: E402


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def rel(path):
    return path.relative_to(ROOT).as_posix()


def gallery(records, width=230):
    result = ['<table>']
    for start in range(0, len(records), 3):
        result.append('<tr>')
        for item in records[start:start + 3]:
            name = html.escape(item['file'], quote=True)
            caption = html.escape(item['caption'])
            result.append(f'<td valign="top"><a href="{name}"><img src="{name}" width="{width}" alt="{caption}"></a><br>{caption}</td>')
        result.append('</tr>')
    result.append('</table>')
    return '\n'.join(result)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    assets = []
    sources = []
    field_groups = []
    source_rows = {}

    def register(source, target, kind, caption, **extra):
        item = dict(file=target.relative_to(OUT).as_posix(), kind=kind, caption=caption,
                    source=rel(source), source_sha256=digest(source),
                    output_sha256=digest(target), bytes=target.stat().st_size, **extra)
        assets.append(item)
        return item

    def copy(source, destination, kind, caption, **extra):
        target = OUT / destination
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        return register(source, target, kind, caption, **extra)

    directories = sorted(INTAKE.glob('NaVi-collect-*'))
    assert len(directories) == 5
    for number, directory in enumerate(directories, 1):
        manifest_path = directory / 'clip_manifest.json'
        manifest = read_json(manifest_path)
        frames_path = directory / 'frames.jsonl'
        assert digest(frames_path) == manifest['files']['frames.jsonl']
        rows = [json.loads(line) for line in frames_path.read_text(encoding='utf-8').splitlines()]
        source_rows[directory.name] = rows
        archive = ROOT / 'data' / (directory.name + '.zip')
        video = directory / 'recording.mp4'
        video_hash = digest(video)
        assert video_hash == manifest['files']['recording.mp4']
        sources.append(dict(number=number, run=directory.name, zip=rel(archive),
                            zip_sha256=digest(archive), zip_bytes=archive.stat().st_size,
                            video=rel(video), video_sha256=video_hash,
                            duration_s=manifest['recording_duration_ms'] / 1000,
                            frames=len(rows), calibration_status=manifest['calibration_status'],
                            replay_eligible=manifest['replay_eligible']))
        copy(manifest_path, f'records/source-manifests/clip-{number:02}.json',
             'original_collection_manifest', f'촬영 {number} 원본 파일 해시 목록')
        selected = []
        for seconds in range(0, math.ceil(rows[-1]['collection']['elapsed_ms'] / 1000), 5):
            row = min(rows, key=lambda r: abs(r['collection']['elapsed_ms'] / 1000 - seconds))
            source = directory / row['image']
            assert digest(source) == manifest['files'][row['image']]
            elapsed = row['collection']['elapsed_ms'] / 1000
            target = OUT / f'photos/01-field/clip-{number:02}/t{elapsed:07.2f}-f{row["frame_id"]:05}.jpg'
            target.parent.mkdir(parents=True, exist_ok=True)
            with Image.open(source) as picture:
                upright = picture.convert('RGB').rotate(-row['rotation_degrees'], expand=True)
                upright.save(target, quality=95, subsampling=0)
            selected.append(register(source, target, 'original_cpu_frame_view',
                f'촬영 {number} · {elapsed:.2f}초 · 프레임 {row["frame_id"]}',
                run=directory.name, frame_id=row['frame_id'], elapsed_s=elapsed,
                time_basis='collection_elapsed', rotation_clockwise_degrees=row['rotation_degrees'],
                brightness_adjusted=False, overlays_added=False,
                transformation='upright rotation and JPEG quality 95 export'))
        field_groups.append(selected)
        print(f'clip {number}: {len(selected)} photographs', flush=True)

    highlights = []
    still_manifest = read_json(STILLS / 'manifest.json')
    for item in still_manifest['images']:
        highlights.append(copy(STILLS / item['file'], 'photos/02-video-highlights/' + item['file'],
            'original_video_still', item['caption'], video_number=item['source_video_number'],
            time_basis='video_pts', video_time_s=item['actual_video_time_s'],
            actual_app_screenshot=False, overlays_added=False))
    copy(STILLS / 'manifest.json', 'records/video-stills-manifest.json', 'original_metadata', '원본 영상 대표 장면 추출 기록')
    copy(STILLS / 'demo_stills_overview.jpg', 'photos/02-video-highlights/overview.jpg', 'original_still_contact_sheet', '기존 대표 장면 6장')

    review_images = []
    for source in sorted(REVIEW.glob('*.jpg')):
        review_images.append(copy(source, 'photos/03-recorded-detections/' + source.name,
            'recorded_detection_review_contact_sheet', source.stem,
            fresh_inference=False, actual_app_screenshot=False, human_verified=False))
    cone_images = {'initial': [], 'current': []}
    for source in sorted(CONE.glob('*-cones-*.jpg')):
        version = 'current' if '-cones-current-' in source.name else 'initial'
        cone_images[version].append(copy(source, f'photos/04-cone-experiments/{version}/' + source.name,
            'cone_pixel_experiment_contact_sheet', source.stem,
            experiment_version=version, actual_app_screenshot=False,
            current_metrics_apply=version == 'current', human_verified=False))

    app_images = []
    app_runs = []
    for source in sorted((ROOT / 'artifacts/jeonju/runs').glob('20*/screen.png')):
        summary_path = source.parent / 'run_summary.json'
        summary = read_json(summary_path) if summary_path.exists() else {}
        app_images.append(copy(source, f'photos/05-app-history/{source.parent.name}.png',
            'historical_app_screenshot', source.parent.name,
            actual_app_screenshot=True, device=summary.get('device_serial', 'unconfirmed'),
            mode=summary.get('mode', 'unconfirmed'), field_acceptance_verified=False))
    for source in sorted((ROOT / 'artifacts/jeonju/runs').glob('20*/run_summary.json')):
        summary = read_json(source)
        app_runs.append(dict(run=source.parent.name, mode=summary.get('mode'),
                             status=summary.get('status'), error=summary.get('error'),
                             field_acceptance_verified=summary.get('field_acceptance_verified')))
        copy(source, f'records/app-runs/{source.parent.name}.json', 'historical_run_summary', source.parent.name)
    app_images.append(copy(ROOT / 'artifacts/jeonju/runs/collection-smoke-20260919/device-complete.png',
        'photos/05-app-history/s23-collection-interrupted-and-saved.png', 'historical_app_screenshot',
        'S23 사례 수집 중단 후 저장 · 110프레임', actual_app_screenshot=True, mode='Collect',
        field_acceptance_verified=False))

    illustrations = []
    for stem, caption in [('ar_app_illustration', '초기 연속 라인·띠 표현 검토'),
                           ('ar_app_arrows_illustration', '연속 화살표 표현 검토')]:
        illustrations.append(copy(STILLS / (stem + '.png'), 'photos/06-ar-design/' + stem + '.png',
            'ai_generated_design_illustration', caption + ' · 실제 앱 캡처 아님',
            actual_app_screenshot=False, actual_arcore_render=False, field_verified=False))
        copy(STILLS / (stem + '.json'), 'records/ar-design/' + stem + '.json', 'original_metadata', caption + ' 생성 출처')
    for source in sorted(STILLS.glob('*prompt.txt')):
        copy(source, 'records/ar-design/' + source.name, 'historical_generation_prompt', source.stem)

    for name in ['analysis.json', 'detections.csv', 'reviewed_detections.json', 'scene_candidates.json']:
        copy(REVIEW / name, 'records/recorded-detector/' + name, 'recorded_detector_audit', name)
    for name in ['cone_predictions.tsv', 'spatial_diagnostic.tsv', 'course_correspondence.json']:
        copy(CONE / name, 'records/cone/' + name, 'actual_pixel_experiment_record', name)
    # Avoid making portable records depend on the original developer's absolute disk path.
    frame_inputs = list(csv.DictReader((CONE / 'frames.tsv').open(encoding='utf-8'), delimiter='\t'))
    target = OUT / 'records/cone/frames.tsv'
    for row in frame_inputs:
        row['argb'] = f'data/ai-evaluation/runs/jeonju-cone-20260919/{row["run"]}/{row["frame_id"]}.argb'
    with target.open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(frame_inputs[0]), delimiter='\t')
        writer.writeheader(); writer.writerows(frame_inputs)
    register(CONE / 'frames.tsv', target, 'portable_frame_index', 'CPU 픽셀 실험 입력 목록', transformation='absolute ARGB paths made repository-relative; binary buffers remain local')
    for name in ['intake_report.json', 'media_report.json']:
        copy(INTAKE / name, 'records/intake/' + name, 'original_intake_audit', name)
    for source in sorted((REVIEW / 'previews').glob('*')):
        if source.is_file():
            copy(source, 'videos/' + source.name, 'review_video' if source.suffix == '.mp4' else 'review_video_metadata',
                 source.stem, actual_app_screenshot=False, field_acceptance_verified=False)
    for folder in ['poc-start-delivery-20260919', 'hackathon-east-delivery-20260919', 'hackathon-overlay-delivery-20260919']:
        copy(ROOT / 'artifacts/jeonju/runs' / folder / 'verification.json',
             'records/verification/' + folder + '.json', 'historical_build_verification', folder)
    for source in sorted((ROOT / 'docs').glob('jeonju_*.md')):
        copy(source, 'records/notes/' + source.name + '.txt', 'historical_document_snapshot', source.stem)
    for name in ['poc-start-final-build.log', 'poc-start-pytest.xml', 'poc-start-adapter-instrumentation.log',
                 'hackathon-east-final-build.log', 'hackathon-east-instrumentation.log', 'hackathon-overlay-build.log',
                 'cone-detector-test.log', 'cone-live-final-build.log', 'poc-start-diagnostic.log',
                 'poc-start-diagnostic-v2.log']:
        copy(ROOT / 'artifacts/jeonju' / name, 'records/logs/' + name, 'historical_test_log', name)
    for name in ['jeonju_hackathon_course.json', 'poc_start_reference.json']:
        copy(ROOT / 'android/app/src/main/assets' / name, 'records/course/' + name, 'course_configuration_snapshot', name)

    analysis = read_json(REVIEW / 'analysis.json')
    prediction_rows = list(csv.DictReader((CONE / 'cone_predictions.tsv').open(encoding='utf-8'), delimiter='\t'))
    # The TSV also has one `none` row for every frame without a detection.
    predictions = [row for row in prediction_rows if row['label'] == 'traffic_cone']
    diagnostics = list(csv.DictReader((CONE / 'spatial_diagnostic.tsv').open(encoding='utf-8'), delimiter='\t'))
    recorded_counts = collections.Counter(d['label'] for rows in source_rows.values() for r in rows for d in r['collection']['detections'])
    cone_counts = collections.Counter(p['run'] for p in predictions)
    frame_counts = collections.Counter(run for run, frame in sorted({(p['run'], p['frame_id']) for p in predictions}))
    reasons = collections.Counter(r['reason'] for r in diagnostics if int(r['cones']) > 0)
    assert sum(map(len, source_rows.values())) == analysis['total_frames'] == len(frame_inputs)
    assert sum(frame_counts.values()) == sum(reasons.values())
    summary = dict(source_count=len(sources), cpu_frames=analysis['total_frames'],
        duration_s=sum(s['duration_s'] for s in sources), recorded_detection_boxes=sum(recorded_counts.values()),
        recorded_detection_counts=dict(recorded_counts), cone_boxes=len(predictions),
        cone_frames=sum(frame_counts.values()), cone_boxes_by_run=dict(cone_counts),
        cone_frames_by_run=dict(frame_counts), spatial_reasons_on_cone_frames=dict(reasons),
        spatial_observations=sum(int(r['observations']) for r in diagnostics),
        field_photographs=sum(map(len, field_groups)),
        image_files=sum(Path(a['file']).suffix.lower() in ('.png', '.jpg') for a in assets),
        review_video_files=4, archive_action='organize_existing_results_no_new_inference',
        field_acceptance_verified=False, human_verified_ground_truth=False)
    write_json(OUT / 'records/summary.json', summary)
    write_json(OUT / 'records/source_inventory.json', sources)
    write_json(OUT / 'records/app_run_index.json', app_runs)
    write_json(OUT / 'manifest.json', dict(schema='navi.experiment_archive.v1', date='2026-09-19',
        kind_legend={
            'original_cpu_frame_view': '실제 CPU 촬영 프레임의 회전·JPEG 내보내기. 박스 없음.',
            'recorded_detection_review_contact_sheet': '촬영 당시 저장한 탐지 결과의 후처리 시각화.',
            'cone_pixel_experiment_contact_sheet': '실제 픽셀에 대한 콘 규칙 검출 실험의 후처리 시각화.',
            'historical_app_screenshot': '당시 앱 캡처. SelfTest는 현장 AR 실행 증거가 아님.',
            'ai_generated_design_illustration': '설명용 합성 이미지. 실제 앱·AR 캡처가 아님.'},
        source_archives_included=False, model_weights_included=False, apks_included=False,
        secrets_included=False, sources=sources, assets=assets))

    template = (ROOT / 'scripts/jeonju_experiment_archive.md.in').read_text(encoding='utf-8')
    clip_table = '\n'.join(f'| {s["number"]} | `{s["run"].split("-")[3]}` | {s["duration_s"]:.3f}초 | {s["frames"]} | {cone_counts[s["run"]]} / {frame_counts[s["run"]]} |' for s in sources)
    field_gallery = '\n\n'.join(
        f'<details>\n<summary>촬영 {i}: {sources[i-1]["run"]} · 사진 {len(group)}장</summary>\n\n{gallery(group)}\n\n</details>'
        for i, group in enumerate(field_groups, 1))
    review_gallery = '\n\n'.join(
        f'<details>\n<summary>{html.escape(item["caption"])}</summary>\n\n![{item["caption"]}]({item["file"]})\n\n</details>' for item in review_images)
    cone_gallery = '\n\n'.join(
        f'<details>\n<summary>{"현재 규칙" if version == "current" else "초기 탐색 규칙"}: {len(group)}개 시트</summary>\n\n' +
        '\n\n'.join(f'![{a["caption"]}]({a["file"]})' for a in group) + '\n\n</details>'
        for version, group in cone_images.items())
    document_links = '\n'.join(f'- [{Path(a["file"]).name.removesuffix(".txt")}]({a["file"]})' for a in assets if a['kind'] == 'historical_document_snapshot')
    replacements = dict(IMAGE_COUNT=str(summary['image_files']), PHOTO_COUNT=str(summary['field_photographs']),
        CLIP_TABLE=clip_table, HIGHLIGHTS=gallery(highlights), FIELD_GALLERY=field_gallery,
        REVIEW_GALLERY=review_gallery, CONE_GALLERY=cone_gallery, APP_GALLERY=gallery(app_images, 210),
        AR_LINE=f'![초기 연속 라인 설명 이미지]({illustrations[0]["file"]})',
        AR_ARROWS=f'![연속 화살표 설명 이미지]({illustrations[1]["file"]})',
        DOCUMENT_LINKS=document_links)
    for key, value in replacements.items():
        template = template.replace('{{' + key + '}}', value)
    assert not re.search(r'\{\{[A-Z_]+\}\}', template)
    (OUT / 'README.md').write_text(template, encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)


if __name__ == '__main__':
    main()
