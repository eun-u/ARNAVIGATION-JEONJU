"""Contract evidence only. These injected observations are never AI demo evidence."""
from datetime import datetime, timedelta, timezone
from pathlib import Path
import hashlib
import json
import zipfile

import pytest
from fastapi.testclient import TestClient
from app.main import create_app, PROJECT_ROOT
from app.poc_scope import project
from app.schemas import Coordinate


@pytest.fixture
def poc(tmp_path):
    app=create_app(PROJECT_ROOT/"data/processed/jeonju_accessibility_graph.geojson",tmp_path/"poc.db")
    with TestClient(app) as client:
        yield client


def initial(c):
    b=c.get('/demo/jeonju').json()
    r=c.post('/route',json={"origin":b['origin'],"destination":b['destination'],"profile":"demo_jeonju"})
    assert r.status_code==200,r.text
    return b,r.json()


def event(b,r,**changes):
    now=datetime.now(timezone.utc).isoformat()
    payload={k:b[k] for k in ("region_id","dataset_revision","scope_revision","graph_sha256","graph_revision")}
    payload.update(event_id="contract-0001",expected_route_revision=r['route_revision'],reason="contract test only",
        current_position={**b['origin'],"timestamp":now,"accuracy_m":0.25,"calibration_revision":"test-only"},
        avoidance_upserts=[{"edge_id":"OSM_E_471373642_95ec0c4e13","observation_id":"test-observation","frame_id":"test-frame",
            "source":"contract_test","observed_at":now}])
    payload.update(changes)
    return payload


def send(c,r,p):
    return c.post('/route/sessions/'+r['session_id']+'/reroute',json=p)


def test_baseline_detour_idempotency_and_isolation(poc):
    b,r=initial(poc); _,other=initial(poc)
    before=poc.get('/graph').json()
    p=event(b,r); first=send(poc,r,p)
    assert first.status_code==200,first.text
    a=first.json()
    assert r['distance_m']==130.7 and a['recalculated_route']['distance_m']==153.5
    assert a['route_revision']==2 and a['recalculated_route']['route_revision']==2
    assert send(poc,r,p).json()==a
    assert poc.get('/route/sessions/'+other['session_id']).json()['route']['distance_m']==130.7
    assert poc.get('/graph').json()==before
    assert all(not v['human_reviewed'] and not v['field_verified'] for v in a['avoidances'])
    assert send(poc,r,{**p,'reason':'changed payload'}).json()['reason_code']=='event_id_payload_conflict'


@pytest.mark.parametrize('field,value,code',[
    ('scope_revision','bad','scope_revision_mismatch'),('dataset_revision','bad','dataset_revision_mismatch'),
    ('graph_sha256','bad','graph_sha256_mismatch'),('expected_route_revision',0,'stale_route_revision'),
    ('graph_revision',99,'graph_revision_mismatch'),('current_position',None,'current_position_required')])
def test_bad_revision_rejected(poc,field,value,code):
    b,r=initial(poc); p=event(b,r,**{field:value})
    assert send(poc,r,p).json()['reason_code']==code


def test_current_mid_edge_origin_is_not_endpoint(poc):
    b,r=initial(poc); geometry=r['segments'][0]['geometry']
    a,z=geometry[0],geometry[1]
    p=event(b,r)
    p['current_position'].update(lat=(a[1]+z[1])/2,lon=(a[0]+z[0])/2)
    response=send(poc,r,p)
    assert response.status_code==200,response.text
    rr=response.json()['recalculated_route']
    assert rr['origin_node']=='P0_CURRENT'
    assert rr['distance_m']<153.5
    assert abs(rr['calculated_origin']['lat']-p['current_position']['lat'])<1e-8


def test_mid_impacted_edge_retreat_uses_only_measured_safe_half(poc):
    b,r=initial(poc);p=event(b,r)
    geometry=r['segments'][1]['geometry']
    a,z=geometry[0],geometry[-1]
    def at(f):return {'lat':a[1]+(z[1]-a[1])*f,'lon':a[0]+(z[0]-a[0])*f}
    p['current_position'].update(at(.22))
    p['avoidance_upserts'][0].update(source='live_ai',evidence={
        'object_position':at(.7),'accuracy_m':.25,'confidence':.9,'persistence_ms':2400,
        'stationary':True,'corridor_occupied':True,'calibration_revision':'test-only','model_revision':'contract-only'})
    response=send(poc,r,p)
    assert response.status_code==200,response.text
    rr=response.json()['recalculated_route']
    assert rr['origin_node']=='P0_CURRENT'
    assert rr['segments'][0]['partial_avoidance_escape'] is True
    assert rr['segments'][0]['geometry'][-1]==a
    assert project(Coordinate(**at(.7)),rr['segments'][0]['geometry']).distance_m>.75
    # No teleportation to either end when the user is too close to the object.
    _,other=initial(poc);p=event(b,other)
    p['current_position'].update(at(.68))
    p['avoidance_upserts'][0]['evidence']={'object_position':at(.7),'accuracy_m':.25}
    assert send(poc,other,p).json()['status']=='no_accessible_route'


def test_cannot_reset_avoidance_by_reusing_session_id(poc):
    b,r=initial(poc);send(poc,r,event(b,r))
    response=poc.post('/route',json={'origin':b['origin'],'destination':b['destination'],
        'profile':'demo_jeonju','session_id':r['session_id']})
    assert response.status_code==409


def test_event_and_avoidance_survive_server_restart(tmp_path):
    graph=PROJECT_ROOT/'data/processed/jeonju_accessibility_graph.geojson';db=tmp_path/'persistent.db'
    with TestClient(create_app(graph,db)) as c:
        b,r=initial(c);p=event(b,r);first=send(c,r,p).json()
    with TestClient(create_app(graph,db)) as c:
        assert send(c,r,p).json()==first
        follow=event(b,r,event_id='after-restart',expected_route_revision=2,avoidance_upserts=[])
        assert send(c,r,follow).json()['recalculated_route']['distance_m']==153.5


def test_outside_and_stale_position_never_snap(poc):
    b,r=initial(poc);p=event(b,r)
    p['current_position']['lat']+=0.002
    assert send(poc,r,p).json()['reason_code']=='position_outside_poc'
    p=event(b,r);p['current_position']['timestamp']=(datetime.now(timezone.utc)-timedelta(seconds=8)).isoformat()
    assert send(poc,r,p).json()['reason_code']=='position_stale'


def test_no_route_and_expiry_does_not_reopen(poc):
    b,r=initial(poc);p=event(b,r)
    p['avoidance_upserts'][0]['edge_id']=r['edge_ids'][0]
    result=send(poc,r,p).json()
    assert result['status']=='no_accessible_route' and result['reason_code']=='no_route_within_poc'
    db=poc.app.state.database
    state=db.poc_state(r['session_id'])
    for a in state['avoidances'].values():a['expires_at']='2000-01-01T00:00:00+00:00'
    with db.connection:
        db.connection.execute('UPDATE poc_session_state SET avoidances_json=? WHERE session_id=?',(json.dumps(state['avoidances']),r['session_id']))
    p=event(b,r,event_id='contract-0002',expected_route_revision=2,avoidance_upserts=[])
    a=send(poc,r,p).json()
    assert a['status']=='no_accessible_route' and a['avoidances'][0]['status']=='stale_unconfirmed'
    p=event(b,r,event_id='contract-0003',expected_route_revision=3,avoidance_upserts=[],
        avoidance_clearances=[{'edge_id':r['edge_ids'][0], 'observation_id':'explicit-clearance-test',
            'frame_id':'clearance-contract-frame', 'observed_at':datetime.now(timezone.utc).isoformat(), 'source':'contract_test'}])
    assert send(poc,r,p).json()['recalculated_route']['distance_m']==130.7


def test_strict_profile_unknown_and_scope_enforcement(poc):
    b,_=initial(poc)
    a=poc.post('/route',json={"origin":b['origin'],"destination":b['destination'],"profile":"wheelchair"})
    assert a.status_code==404 and a.json()['reason_code']=='no_route_within_poc'
    b['origin']['lat']+=0.002
    assert poc.post('/route',json={"origin":b['origin'],"destination":b['destination'],"profile":"demo_jeonju"}).json()['reason_code']=='position_outside_poc'


def test_ai_cannot_use_boolean_depth_only(poc):
    b,r=initial(poc);p=event(b,r);p['avoidance_upserts'][0]['source']='live_ai'
    p['avoidance_upserts'][0]['evidence']={'depthAvailable':True}
    assert send(poc,r,p).json()['reason_code']=='insufficient_spatial_evidence'


def test_archive_path_traversal_rejected_before_extraction(tmp_path):
    from scripts.import_jeonju_p0 import verified_members
    bad=tmp_path/'bad.zip'
    with zipfile.ZipFile(bad,'w') as z:z.writestr('../escape','bad')
    with pytest.raises(ValueError,match='unsafe_member'):verified_members(bad)


def measured_event(b, r, source='live_ai'):
    """Synthetic contract fixture, never counted as a real model observation."""
    p=event(b,r)
    a,z=r['segments'][1]['geometry'][0],r['segments'][1]['geometry'][-1]
    p['avoidance_upserts'][0].update(source=source,evidence={
        'object_position':{'lat':(a[1]+z[1])/2,'lon':(a[0]+z[0])/2},
        'accuracy_m':.25,'confidence':.9,'persistence_ms':2400,
        'stationary':True,'corridor_occupied':True,'calibration_revision':'test-only',
        'model_revision':'synthetic-contract-fixture'})
    return p


@pytest.mark.parametrize('source',['live_ai','replay_ai'])
def test_observation_duplicate_and_out_of_order_rejected_without_renewing_ttl(poc,source):
    b,r=initial(poc);p=measured_event(b,r,source)
    first=send(poc,r,p).json()
    assert first['route_revision']==2
    duplicate={**p,'event_id':'another-event-id','expected_route_revision':2}
    response=send(poc,r,duplicate)
    assert response.status_code==409 and response.json()['reason_code']=='duplicate_observation'
    old=json.loads(json.dumps(duplicate));old['event_id']='out-of-order-event'
    item=old['avoidance_upserts'][0];item['frame_id']='older-frame'
    item['observed_at']=(datetime.fromisoformat(item['observed_at'])-timedelta(seconds=.5)).isoformat()
    response=send(poc,r,old)
    assert response.status_code==409 and response.json()['reason_code']=='observation_out_of_order'
    state=poc.app.state.database.poc_state(r['session_id'])
    assert state['route_revision']==2
    assert state['avoidances'][item['edge_id']]['expires_at']==first['avoidances'][0]['expires_at']
    assert poc.app.state.database.poc_event(r['session_id'],duplicate['event_id']) is None
    # A later frame from the same track is a renewal, not a duplicate.
    newer=json.loads(json.dumps(duplicate));newer['event_id']='new-frame-renewal'
    item=newer['avoidance_upserts'][0];item['frame_id']='new-frame'
    item['observed_at']=(datetime.fromisoformat(item['observed_at'])+timedelta(milliseconds=20)).isoformat()
    response=send(poc,r,newer)
    assert response.status_code==200,response.text
    assert response.json()['route_revision']==3


def test_clearance_requires_explicit_source_and_new_sustained_spatial_evidence(poc):
    b,r=initial(poc);p=measured_event(b,r);first=send(poc,r,p).json()
    edge=p['avoidance_upserts'][0]['edge_id']
    legacy=event(b,r,event_id='opaque-clearance-id',expected_route_revision=2,avoidance_upserts=[],
        avoidance_removes=[edge],clearance_observation_id='unverifiable-id')
    response=send(poc,r,legacy)
    assert response.status_code==422 and response.json()['reason_code']=='clearance_evidence_required'
    synthetic_clear=event(b,r,event_id='contract-cannot-clear-live',expected_route_revision=2,avoidance_upserts=[],
        avoidance_clearances=[{'edge_id':edge,'observation_id':'contract-clearance','frame_id':'contract-clearance-frame',
            'source':'contract_test','observed_at':datetime.now(timezone.utc).isoformat()}])
    response=send(poc,r,synthetic_clear)
    assert response.status_code==409 and response.json()['reason_code']=='clearance_source_mismatch'
    clearance={'edge_id':edge,'observation_id':'sustained-clear-corridor','frame_id':'clearance-frame',
        'source':'live_ai','observed_at':(datetime.fromisoformat(p['avoidance_upserts'][0]['observed_at'])+timedelta(milliseconds=40)).isoformat(),
        'evidence':{'observed_position':p['avoidance_upserts'][0]['evidence']['object_position'],
            'accuracy_m':.25,'confidence':.9,'persistence_ms':0,'corridor_clear':True,
            'depth_valid':True,'semantics_valid':True,'calibration_revision':'test-only',
            'model_revision':'synthetic-contract-fixture'}}
    clear=event(b,r,event_id='measured-clearance',expected_route_revision=2,avoidance_upserts=[],avoidance_clearances=[clearance])
    response=send(poc,r,clear)
    assert response.status_code==422 and response.json()['reason_code']=='insufficient_clearance_evidence'
    assert poc.app.state.database.poc_state(r['session_id'])['avoidances'][edge]['status']=='active'
    clearance['evidence']['persistence_ms']=2200
    obstacle_point=clearance['evidence']['observed_position']
    a,z=r['segments'][1]['geometry'][0],r['segments'][1]['geometry'][-1]
    clearance['evidence']['observed_position']={'lat':a[1]+(z[1]-a[1])*.2,'lon':a[0]+(z[0]-a[0])*.2}
    response=send(poc,r,clear)
    assert response.status_code==422 and response.json()['reason_code']=='clearance_does_not_cover_obstacle'
    clearance['evidence']['observed_position']=obstacle_point
    response=send(poc,r,clear)
    assert response.status_code==200,response.text
    a=response.json()
    assert a['route_revision']==3 and a['recalculated_route']['distance_m']==130.7
    assert a['avoidances'][0]['status']=='removed'
    assert a['avoidances'][0]['clearance']['source']=='live_ai'
    assert not a['avoidances'][0]['human_reviewed'] and not a['avoidances'][0]['field_verified']
    assert send(poc,r,clear).json()==a


def test_contract_upsert_cannot_downgrade_measured_avoidance(poc):
    b,r=initial(poc);p=measured_event(b,r);send(poc,r,p)
    contract=event(b,r,event_id='contract-cannot-replace-live',expected_route_revision=2)
    response=send(poc,r,contract)
    assert response.status_code==409 and response.json()['reason_code']=='observation_source_mismatch'
    saved=poc.app.state.database.poc_state(r['session_id'])
    assert saved['route_revision']==2
    assert next(iter(saved['avoidances'].values()))['source']=='live_ai'


def test_observation_duplicate_is_rejected_after_server_restart(tmp_path):
    graph=PROJECT_ROOT/'data/processed/jeonju_accessibility_graph.geojson';db=tmp_path/'observations.db'
    with TestClient(create_app(graph,db)) as c:
        b,r=initial(c);p=measured_event(b,r,'replay_ai');first=send(c,r,p).json()
    with TestClient(create_app(graph,db)) as c:
        assert send(c,r,p).json()==first
        p['event_id']='new-id-after-restart';p['expected_route_revision']=2
        response=send(c,r,p)
        assert response.status_code==409 and response.json()['reason_code']=='duplicate_observation'


def test_numeric_accuracy_is_normalized_without_server_error(poc):
    b,r=initial(poc);p=measured_event(b,r)
    p['avoidance_upserts'][0]['evidence']['accuracy_m']='0.25'
    response=send(poc,r,p)
    assert response.status_code==200,response.text


def test_conflict_exception_carries_current_revisions(poc):
    from app.poc_scope import PocContractError
    from app.schemas import SessionRerouteRequest
    b,r=initial(poc);send(poc,r,event(b,r))
    p=event(b,r,event_id='stale-version-request')
    response=send(poc,r,p)
    assert response.status_code==409
    assert response.json()['current_route_revision']==2
    assert response.json()['current_graph_revision']==b['graph_revision']
    with pytest.raises(PocContractError) as exc:
        poc.app.state.route_service.reroute_session(r['session_id'],SessionRerouteRequest.model_validate(p))
    assert exc.value.current_route_revision==2 and exc.value.current_graph_revision==b['graph_revision']
