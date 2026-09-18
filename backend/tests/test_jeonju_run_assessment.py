"""Constructed log fixtures test the assessor only, never actual AI/field evidence."""
import json

import pytest

from scripts.assess_jeonju_run import ROOT, assess, load


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


def fixture_run(tmp_path, *, negative=False):
    scope = load(ROOT / 'backend/app/config/jeonju_scope.json')
    model = load(ROOT / 'android/feature/ai-perception/src/main/assets/model_manifest.json')
    binding = {k: scope[k] for k in ('region_id', 'dataset_revision', 'scope_revision', 'graph_sha256')}
    edge = scope['allowed_edge_ids'][0]
    app = {'status': 'replay_executed_acceptance_pending', 'run_id': 'parser-fixture-only', 'input_mode': 'Replay',
           'clip_id': 'C02' if negative else 'C01', 'model_loading_verified': True, 'frames': 2,
           'reroutes': 0 if negative else 1, 'open_frame_leases': 0, 'backend_p95_ms': 40}
    host = {'run_id': app['run_id'], 'mode': 'Replay', **binding}
    events = [
        {'type': 'model_loaded', 'model_sha256': model['sha256'], 'model_revision': model['model_revision'], 'evidence_type': 'synthetic_model_smoke_only'},
        {'type': 'started', 'session_id': 'session-fixture', 'user_start_count': 1, 'obstacle_user_actions': 0},
        {'type': 'frame', 'frame_id': 1, 'timestamp_ns': 1_000_000_000, 'inference_ms': 90, 'detection_labels': ['bicycle']},
        {'type': 'frame', 'frame_id': 2, 'timestamp_ns': 3_200_000_000, 'inference_ms': 120, 'detection_labels': ['bicycle']},
    ]
    if not negative:
        events += [
            {'type': 'observation', 'frame_id': 2, 'observation_id': 'observation-fixture', 'edge_id': edge, 'decision': 'temporary_avoidance', 'labels': ['bicycle']},
            {'type': 'reroute_requested', 'event_id': 'event-fixture', 'expected_route_revision': 1, **binding,
             'avoidance_upserts': [{'frame_id': '2', 'observation_id': 'observation-fixture', 'edge_id': edge, 'source': 'replay_ai', 'evidence': {'labels': ['bicycle']}}]},
            {'type': 'guidance_snapshot_dispatched', 'route_revision': 2, 'map_revision': 2, 'ar_revision': 2, 'tts_revision': 2, 'render_or_audio_completion_verified': True},
            {'type': 'tts_started', 'utterance_id': 'session-fixture:2', 'current_snapshot': 'session-fixture:2'},
            {'type': 'route_applied', 'frame_id': 2, 'observation_id': 'observation-fixture', 'event_id': 'event-fixture',
             'edge_id': edge, 'session_id': 'session-fixture', 'route_revision': 2, 'status': 'recalculated',
             'state_dispatch_ms': 14, 'reaction_ms': 2450,
             'recalculated_route': {**binding, 'session_id': 'session-fixture', 'route_revision': 2}},
            {'type': 'tts_finished', 'utterance_id': 'session-fixture:2'},
        ]
    for i, event in enumerate(events):
        event.update(run_id=app['run_id'], input_mode=app['input_mode'], execution_epoch_ms=1_000_000 + i * 100)
    evaluation = {'schema_version': 1, 'clip_id': app['clip_id'], 'expected_reroutes': app['reroutes'],
                  'expected_edge_ids': [] if negative else [edge], 'expected_labels': ['bicycle']}
    directory = tmp_path / 'run'
    evaluation_path = tmp_path / 'evaluation-only' / 'expected.json'
    def save():
        write(directory / 'run_summary.json', host)
        write(directory / 'app/run_summary.json', app)
        (directory / 'app/events.jsonl').write_text(''.join(json.dumps(e) + '\n' for e in events), encoding='utf-8')
        write(evaluation_path, evaluation)
    save()
    return directory, evaluation_path, events, app, host, evaluation, save


def test_matching_chain_is_partial_and_dispatch_does_not_prove_rendering(tmp_path):
    directory, evaluation, *_ = fixture_run(tmp_path)
    result = assess(directory, evaluation)
    assert result['evidence_status'] == 'verified_partial'
    assert result['automatic_mapping_chain_verified'] and result['evaluation_matched']
    assert result['checks']['tts_completion']['status'] == 'passed'
    assert result['checks']['render_completion']['status'] == 'pending'
    assert result['metrics']['inference_p95_ms'] == 120
    assert result['metrics']['state_dispatch_p95_ms'] == 14
    assert result['whole_project_acceptance'] == 'not_issued' and not result['field_acceptance_verified']
    assert 'integrated_10minute_crash_ANR_resource_soak' in result['missing_acceptance_gates']


@pytest.mark.parametrize('defect', ['missing_frame', 'wrong_edge', 'contract', 'synthetic_frame', 'stale_tts', 'graph_hash', 'model_hash', 'open_lease', 'mixed_mode', 'manual_action'])
def test_corrupted_or_nonautomatic_evidence_is_rejected(tmp_path, defect):
    directory, evaluation, events, app, host, _, save = fixture_run(tmp_path)
    find = lambda kind: next(e for e in events if e['type'] == kind)
    if defect == 'missing_frame':
        find('route_applied')['frame_id'] = 999
    elif defect == 'wrong_edge':
        find('observation')['edge_id'] = 'not-the-mapped-edge'
    elif defect == 'contract':
        find('reroute_requested')['avoidance_upserts'][0]['source'] = 'contract_test'
    elif defect == 'synthetic_frame':
        find('frame')['synthetic'] = True
    elif defect == 'stale_tts':
        find('tts_started')['current_snapshot'] = 'session-fixture:3'
    elif defect == 'graph_hash':
        host['graph_sha256'] = 'wrong'
    elif defect == 'model_hash':
        find('model_loaded')['model_sha256'] = 'wrong'
    elif defect == 'open_lease':
        app['open_frame_leases'] = 1
    elif defect == 'mixed_mode':
        find('frame')['input_mode'] = 'Live'
    else:
        find('started')['obstacle_user_actions'] = 1
    save()
    result = assess(directory, evaluation)
    assert result['evidence_status'] == 'rejected'
    assert not result['automatic_mapping_chain_verified']


def test_negative_scene_requires_real_inference_and_explicit_expected_labels(tmp_path):
    directory, evaluation, events, app, _, expected, save = fixture_run(tmp_path, negative=True)
    result = assess(directory, evaluation)
    assert result['evidence_status'] == 'verified_partial' and result['evaluation_matched']
    assert not result['automatic_mapping_chain_verified']
    expected['expected_labels'] = ['person']
    save()
    result = assess(directory, evaluation)
    assert not result['evaluation_matched']
    assert result['checks']['evaluation']['reason'] == 'expected_object_label_evidence_missing'
    events[:] = [e for e in events if e['type'] != 'frame']; app['frames'] = 0
    save()
    result = assess(directory, evaluation)
    assert result['evidence_status'] == 'input_pending' and not result['evaluation_matched']


def test_explicit_time_window_and_edge_expectations_are_not_inferred(tmp_path):
    directory, evaluation, _, _, _, expected, save = fixture_run(tmp_path)
    expected['time_window_ms'] = [2000, 3000]
    save()
    assert assess(directory, evaluation)['evaluation_matched']
    expected['time_window_ms'] = [0, 100]
    save()
    assert assess(directory, evaluation)['evidence_status'] == 'rejected'
    del expected['expected_edge_ids']
    save()
    assert assess(directory, evaluation)['checks']['evaluation']['reason'] == 'invalid_evaluation_contract_no_expectations_were_inferred'


def test_idempotent_request_retry_is_not_a_second_reroute(tmp_path):
    directory, evaluation, events, _, _, _, save = fixture_run(tmp_path)
    events.append(dict(next(e for e in events if e['type'] == 'reroute_requested')))
    save()
    result = assess(directory, evaluation)
    assert result['evidence_status'] == 'verified_partial' and result['evaluation_matched']
    assert result['metrics']['applied_reroutes'] == 1


def test_render_submission_with_matching_revision_is_not_display_completion(tmp_path):
    directory, evaluation, events, _, _, _, save = fixture_run(tmp_path)
    event = dict(events[-1])
    event.update(type='guidance_render_submitted', session_id='session-fixture', route_revision=2,
                 frame_interval_ms=34, render_work_ms=4, draw_submitted=True)
    events.append(event)
    save()
    result = assess(directory, evaluation)
    assert result['checks']['render_submission']['status'] == 'passed'
    assert result['checks']['render_completion']['status'] == 'pending'
    assert result['metrics']['render_frame_interval_p95_ms'] == 34


def test_no_route_is_not_a_successful_detour_expectation(tmp_path):
    directory, evaluation, events, _, _, expected, save = fixture_run(tmp_path)
    result = next(e for e in events if e['type'] == 'route_applied')
    result['status'] = 'no_accessible_route'; result['recalculated_route'] = None
    expected['expected_route_status'] = 'recalculated'
    save()
    assert assess(directory, evaluation)['evidence_status'] == 'rejected'
    expected['expected_route_status'] = 'no_accessible_route'
    save()
    assert assess(directory, evaluation)['evaluation_matched']


def test_selftest_and_tts_dispatch_cannot_be_counted_as_ai_or_audio_completion(tmp_path):
    directory, evaluation, events, app, host, _, save = fixture_run(tmp_path)
    events[:] = [e for e in events if e['type'] != 'tts_finished']
    save()
    assert assess(directory, evaluation)['checks']['tts_completion']['status'] == 'pending'
    events[:] = [e for e in events if e['type'] in ('model_loaded', 'guidance_snapshot_dispatched')]
    app.update(input_mode='SelfTest', frames=0, reroutes=0, status='model_loading_verified')
    host['mode'] = 'SelfTest'
    for e in events: e['input_mode'] = 'SelfTest'
    save()
    result = assess(directory)
    assert result['evidence_status'] == 'input_pending'
    assert not result['automatic_mapping_chain_verified']


def test_missing_evidence_remains_pending_and_does_not_create_input(tmp_path):
    directory = tmp_path / 'nonexistent-run'
    result = assess(directory)
    assert result['evidence_status'] == 'input_pending'
    assert not directory.exists()
