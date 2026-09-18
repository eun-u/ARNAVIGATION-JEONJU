"""Bundle the user-selected east detour without changing the shared Graph or old scope."""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[1]
graph_path = ROOT / 'data/processed/jeonju_accessibility_graph.geojson'
graph = json.loads(graph_path.read_text(encoding='utf-8'))
scope = json.loads((ROOT / 'backend/app/config/jeonju_scope.json').read_text(encoding='utf-8'))
assert hashlib.sha256(graph_path.read_bytes().replace(b'\r\n', b'\n')).hexdigest() == scope['graph_sha256']
features = {f['properties']['edge_id']: f for f in graph['features'] if f['geometry']['type'] == 'LineString'}
approach_id = 'OSM_E_471373639_917a7d9bd3'
blocked_id = 'OSM_E_471373642_95ec0c4e13'
east_id = 'OSM_E_1327522116_93e880e796'
def segment(edge, reverse=False):
    f = features[edge]
    assert f['properties']['highway'] == ['footway'] and not f['properties']['stairs']
    points = f['geometry']['coordinates']
    return {'edge_id': edge, 'physical_segment_id': edge, 'geometry': list(reversed(points)) if reverse else points}
approach = segment(approach_id, True)
shortcut = segment(blocked_id)
east = segment(east_id, True)
assert approach['geometry'][-1] == shortcut['geometry'][0]
assert approach['geometry'][0] == east['geometry'][0]
assert shortcut['geometry'][-1] == east['geometry'][-1]
def coordinate(point):
    return {'lat': point[1], 'lon': point[0]}
result = {
    **{k: scope[k] for k in ('region_id', 'dataset_revision', 'graph_sha256', 'bundle_content_sha256')},
    'scope_revision': 'jbnu-user-east-hackathon-v1', 'graph_revision': 0,
    'profile': 'demo_jeonju', 'alignment_sources': ['poc_start'],
    'origin': coordinate(approach['geometry'][0]),
    'poc_start_forward': coordinate(approach['geometry'][-1]),
    'destination': coordinate(east['geometry'][-1]),
    'origin_node': 'OSM_N12288349033', 'destination_node': 'OSM_N4655264758',
    'segments': [approach, shortcut, east], 'baseline_segments': [approach, shortcut],
    'detour_segment': east,
    'preset_id': 'user-east-return-20260919',
    'route_source': 'user_declared_fixed_course_existing_osm_geometry',
    'trigger_zone': {'center': coordinate(approach['geometry'][-1]), 'radius_m': 12.0},
    'field_crossing_check': 'pending', 'accessibility_verified': False,
    'absolute_alignment_verified': False, 'server_required': False,
    'reference_recording': 'NaVi-collect-20260919-024719-670-dd86e6fb',
    'user_instruction': 'Return north before the obstacle, follow the eastern curved walkway, finish at the southern merge.',
    'trigger_semantics': 'Preset switch after real 2D cone detection or explicit operator action; not autonomous obstacle localization.',
}
out = ROOT / 'android/app/src/main/assets/jeonju_hackathon_course.json'
out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(out.relative_to(ROOT))
