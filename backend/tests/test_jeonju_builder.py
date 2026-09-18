"""Offline candidate checks, independent from the pinned runtime Graph."""
import importlib.util
from pathlib import Path
import sys
import pytest

pytest.importorskip('osmnx')
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from scripts.build_graph import incline_percent, width_meters
from scripts.build_jeonju_graph import is_walkable, load_walk_graph


def test_units_and_mixed_tags_are_conservative():
    assert incline_percent('-10%')==10
    assert incline_percent('45°')==pytest.approx(100)
    assert incline_percent(['3%','9%'])==9
    assert incline_percent(['3%','up']) is None
    assert width_meters(['180 cm','2 m'])==1.8
    assert width_meters('6 ft')==pytest.approx(1.8288)
    assert width_meters('narrow') is None
    for tags in ({'foot':['yes','no']},{'access':'private'},{'oneway:foot':'yes'}):
        assert not is_walkable({'highway':'footway',**tags})


def test_access_change_and_barrier_nodes_survive_simplification(tmp_path):
    xml=tmp_path/'source.osm'
    xml.write_text('''<osm version="0.6"><node id="1" lat="35.8" lon="127.1"/>
    <node id="2" lat="35.8001" lon="127.1"><tag k="barrier" v="bollard"/></node>
    <node id="3" lat="35.8002" lon="127.1"/><node id="4" lat="35.8003" lon="127.1"/>
    <way id="10"><nd ref="1"/><nd ref="2"/><nd ref="3"/><tag k="highway" v="footway"/><tag k="width" v="180 cm"/></way>
    <way id="11"><nd ref="3"/><nd ref="4"/><tag k="highway" v="footway"/><tag k="foot" v="no"/></way></osm>''',encoding='utf-8')
    graph=load_walk_graph(xml)
    assert 2 in graph and graph.nodes[2]['barrier']=='bollard'
    assert 4 not in graph
    assert graph.number_of_edges()==2
    assert all(a['width']=='180 cm' for _,_,a in graph.edges(data=True))
