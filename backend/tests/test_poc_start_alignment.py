"""Injected contract tests. They establish no real-world positioning accuracy."""
from datetime import datetime, timezone

import pytest
from backend.tests.test_jeonju_poc import poc, event, send


def start(c, **kwargs):
    b=c.get('/demo/jeonju').json()
    response=c.post('/route',json={"origin":b['origin'],"destination":b['destination'],
        "profile":"demo_jeonju","alignment_source":"poc_start",**kwargs})
    return b,response


def relative_event(b,r):
    p=event(b,r)
    p['current_position'].update(accuracy_m=None,alignment_source='poc_start',
        relative_tracking_budget_m=.25,calibration_revision='poc-start-test')
    segment=next(s for s in r['segments'] if s['edge_id']=='OSM_E_471373642_95ec0c4e13')
    a,z=segment['geometry'][0],segment['geometry'][-1]
    p['avoidance_upserts'][0].update(source='poc_live_ai',evidence={
        'object_position':{'lat':(a[1]+z[1])/2,'lon':(a[0]+z[0])/2},
        'accuracy_m':None,'relative_tracking_budget_m':.25,'alignment_source':'poc_start',
        'confidence':.9,'persistence_ms':2400,'stationary':True,'corridor_occupied':True,
        'depth_valid':True,'semantics_valid':True,'calibration_revision':'poc-start-test',
        'model_revision':'injected-contract-test'})
    return p


def test_fixed_course_relative_reroute_and_unknown_accuracy(poc):
    b,response=start(poc);assert response.status_code==200,response.text
    r=response.json();assert r['alignment_source']=='poc_start'
    before=poc.get('/graph').json()
    p=relative_event(b,r);response=send(poc,r,p)
    assert response.status_code==200,response.text
    result=response.json()
    assert result['recalculated_route']['alignment_source']=='poc_start'
    assert result['recalculated_route']['distance_m']==153.5
    assert result['avoidances'][0]['evidence']['accuracy_m'] is None
    assert result['avoidances'][0]['field_verified'] is False
    assert poc.get('/graph').json()==before
    assert send(poc,r,p).json()==result


def test_plane_projection_requires_real_plane_provenance(poc):
    b,response=start(poc);r=response.json();p=relative_event(b,r)
    e=p['avoidance_upserts'][0]['evidence']
    e.update(spatial_method='ground_plane_ray',depth_valid=False,ground_plane_valid=True,
             ground_height_m=-1.2,labels=['traffic_cone'])
    assert send(poc,r,p).status_code==200
    b,response=start(poc);r=response.json();p=relative_event(b,r)
    e=p['avoidance_upserts'][0]['evidence']
    e.update(spatial_method='ground_plane_ray',depth_valid=False,ground_plane_valid=True,
             ground_height_m=None,labels=['traffic_cone'])
    assert send(poc,r,p).status_code==422


def test_fixed_course_mode_cannot_be_started_through_compare(poc):
    b=poc.get('/demo/jeonju').json()
    r=poc.post('/route/compare',json={'origin':b['origin'],'destination':b['destination'],
        'profile':'demo_jeonju','alignment_source':'poc_start'})
    assert r.status_code==422 and r.json()['reason_code']=='poc_start_requires_route'


@pytest.mark.parametrize('field,value', [('accuracy_m',.25),('relative_tracking_budget_m',None),('calibration_revision','measured-ref')])
def test_relative_position_cannot_claim_measured_accuracy(poc,field,value):
    b,response=start(poc);r=response.json();p=relative_event(b,r)
    p['current_position'][field]=value
    assert send(poc,r,p).status_code==422


@pytest.mark.parametrize('mutation', ['other_origin','measured_session','unmeasured_source','no_depth','outside','moving','short','no_semantics'])
def test_relative_mode_does_not_skip_validation(poc,mutation):
    if mutation=='other_origin':
        _,r=start(poc,origin={'lat':35.8465,'lon':127.1322})
        assert r.status_code==422;return
    b,response=start(poc,**({'alignment_source':'measured_references'} if mutation=='measured_session' else {}))
    r=response.json();p=relative_event(b,r);item=p['avoidance_upserts'][0];e=item['evidence']
    if mutation=='unmeasured_source':item['source']='contract_test'
    if mutation=='no_depth':e['depth_valid']=False
    if mutation=='outside':e['object_position']['lon']+=.0001
    if mutation=='moving':e['stationary']=False
    if mutation=='short':e['persistence_ms']=100
    if mutation=='no_semantics':e['semantics_valid']=False
    assert send(poc,r,p).status_code==422
