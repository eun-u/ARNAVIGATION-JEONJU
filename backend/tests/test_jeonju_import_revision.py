"""The pinned dataset revision identifies source content, not ZIP packaging bytes."""
import hashlib
from pathlib import Path
import zipfile

import pytest

from scripts.import_jeonju_p0 import ROOT, import_bundle, verified_members


@pytest.fixture
def original_bundle():
    archive=ROOT/'data/NaVi_Jeonju_P0_FINAL_20260918.zip'
    if not archive.is_file():
        pytest.skip('The original handed-off Jeonju bundle is not present in this checkout')
    return archive


def repack(path: Path, files: dict[str, bytes]):
    with zipfile.ZipFile(path,'w',compression=zipfile.ZIP_STORED) as archive:
        for name,content in reversed(list(files.items())):
            archive.writestr('repacked-source/'+name,content)


@pytest.mark.parametrize('change',['replace_member','add_member'])
def test_changed_content_with_valid_checksums_cannot_reuse_pinned_revision(tmp_path,original_bundle,change):
    files,_=verified_members(original_bundle)
    if change=='replace_member':
        # A byte change in source metadata is still a source revision change.
        name=next(n for n in files if n.endswith('.csv'))
        files[name]+=b'\n'
    else:
        files['07_manifest/unversioned_addition.txt']=b'not part of the approved input revision\n'
    checksum_name='07_manifest/checksums.sha256'
    files[checksum_name]=''.join(
        hashlib.sha256(content).hexdigest()+'  '+name+'\n'
        for name,content in sorted(files.items()) if name!=checksum_name
    ).encode('utf-8')
    changed=tmp_path/'valid checksums (different content).zip'
    repack(changed,files)
    # Distinguish revision rejection from CRC or checksum rejection.
    validated,hashes=verified_members(changed)
    assert len(hashes)==len(validated)-1
    output=tmp_path/'empty output'
    output.mkdir()
    with pytest.raises(ValueError,match='dataset_revision_content_mismatch'):
        import_bundle(changed,output)
    assert list(output.iterdir())==[]


def test_repackaged_identical_source_content_keeps_revision(tmp_path,original_bundle):
    files,_=verified_members(original_bundle)
    repackaged=tmp_path/'NaVi copied bundle (1).zip'
    repack(repackaged,files)
    assert hashlib.sha256(repackaged.read_bytes()).digest()!=hashlib.sha256(original_bundle.read_bytes()).digest()
    output=tmp_path/'accepted input'
    graph=ROOT/'data/processed/jeonju_accessibility_graph.geojson'
    graph_before=hashlib.sha256(graph.read_bytes()).hexdigest()
    manifest=import_bundle(repackaged,output)
    assert manifest['dataset_revision']=='jeonju-p0-20260918-1ace3878'
    assert manifest['checksum_verified']==42 and manifest['file_count']==43
    assert manifest['bundle_sha256']==hashlib.sha256(repackaged.read_bytes()).hexdigest()
    assert manifest['bundle_content_sha256']=='397cb7bacaea9cfcda13e03c9991d1919abe753f37c456640c3cfe49e1256ea8'
    assert hashlib.sha256(graph.read_bytes()).hexdigest()==graph_before
    assert (output/'data/processed/jeonju/p0_manifest.json').is_file()
