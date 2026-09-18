"""Verify the portable evidence package without requiring the local source videos."""
import collections
import csv
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
ARCHIVE = ROOT / 'docs/jeonju-experiment-archive-20260919'


def main():
    manifest = json.loads((ARCHIVE / 'manifest.json').read_text(encoding='utf-8'))
    summary = json.loads((ARCHIVE / 'records/summary.json').read_text(encoding='utf-8'))
    for item in manifest['assets']:
        path = (ARCHIVE / item['file']).resolve()
        assert path.is_relative_to(ARCHIVE.resolve()), item['file']
        with path.open('rb') as stream:
            assert hashlib.file_digest(stream, 'sha256').hexdigest() == item['output_sha256'], item['file']
        assert path.stat().st_size == item['bytes'], item['file']
    text = (ARCHIVE / 'README.md').read_text(encoding='utf-8')
    links = re.findall(r'(?:href|src)="([^"]+)"', text) + re.findall(r'\]\(([^)]+)\)', text)
    for link in links:
        if not link.startswith(('#', 'http')):
            assert (ARCHIVE / link).exists(), link
    with (ARCHIVE / 'records/cone/cone_predictions.tsv').open(encoding='utf-8') as stream:
        predictions = [r for r in csv.DictReader(stream, delimiter='\t') if r['label'] == 'traffic_cone']
    assert len(predictions) == summary['cone_boxes'] == 143
    assert len({(r['run'], r['frame_id']) for r in predictions}) == summary['cone_frames'] == 129
    with (ARCHIVE / 'records/recorded-detector/detections.csv').open(encoding='utf-8') as stream:
        assert sum(1 for _ in csv.DictReader(stream)) == summary['recorded_detection_boxes'] == 1437
    kinds = collections.Counter(item['kind'] for item in manifest['assets']
                                if Path(item['file']).suffix in ('.jpg', '.png'))
    assert sum(kinds.values()) == summary['image_files'] == 204
    assert kinds['original_cpu_frame_view'] == summary['field_photographs'] == 153
    assert not manifest['secrets_included'] and not manifest['apks_included']
    result = dict(assets_checked=len(manifest['assets']), sha256_matches=True,
                  relative_links_exist=True, statistics_reconciled=True, image_kinds=dict(kinds),
                  field_acceptance_verified=False)
    (ARCHIVE / 'records/archive_validation.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
