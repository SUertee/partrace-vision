"""Opt-in integration checks against an isolated real PG+pgvector test database.

Set PARTRACE_TEST_DATABASE_URL to a database whose name ends in _tests. Each test
uses and drops only its own random mvp_test_* schema; no shared tables are reset.
"""

import copy
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from uuid import uuid4

import psycopg
from psycopg import sql
from psycopg.conninfo import conninfo_to_dict
import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator, FormatChecker
from PIL import Image

from backend.app import create_app
from backend.core.settings import Settings
from backend.core.configuration import config_signature
from backend.manage import _schema_body
from backend.core.models import Config

ROOT = Path(__file__).resolve().parents[2]
SPEC = json.loads((ROOT / 'docs/api/openapi.json').read_text())
pytestmark = pytest.mark.integration


def check(response, schema, status=200):
    assert response.status_code == status, response.text
    validator = Draft202012Validator({'$ref': f'#/components/schemas/{schema}', 'components': SPEC['components']}, format_checker=FormatChecker())
    validator.validate(response.json())
    return response.json()


def private_json(path, value):
    path.write_text(json.dumps(value))
    path.chmod(0o600)


@pytest.fixture
def context(tmp_path):
    dsn = os.environ.get('PARTRACE_TEST_DATABASE_URL')
    if not dsn:
        pytest.skip('requires isolated PARTRACE_TEST_DATABASE_URL ending in _tests')
    if not conninfo_to_dict(dsn).get('dbname', '').endswith('_tests'):
        pytest.fail('Refusing integration writes outside a named _tests database')
    schema = 'mvp_test_' + uuid4().hex[:16]
    with psycopg.connect(dsn, autocommit=True) as connection:
        with connection.transaction():
            connection.execute(sql.SQL('CREATE SCHEMA {}').format(sql.Identifier(schema)))
            connection.execute(sql.SQL('SET LOCAL search_path TO {},public').format(sql.Identifier(schema)))
            connection.execute(_schema_body(ROOT / 'docs/database/schema.sql'), prepare=False)
            for user_id, role, status in [('admin', 'admin', 'active'), ('viewer', 'viewer', 'active'), ('disabled', 'admin', 'disabled'), ('other', 'admin', 'active')]:
                connection.execute('INSERT INTO users VALUES(%s,%s,%s,%s,%s)', (user_id, user_id, role, status, datetime.now(timezone.utc)))
    snapshots = tmp_path / 'configs'
    snapshots.mkdir()
    config = Config.model_validate(json.loads((ROOT / 'docs/api/examples/v0.3/config-response.json').read_text())['config']).model_dump(mode='json')
    private_json(snapshots / 'demo_v1.json', config)
    private_json(tmp_path / 'pins.json', {'demo_v1': config_signature(config)})
    tokens = []
    for name in ('admin', 'viewer', 'disabled', 'other', 'readonly', 'nomap', 'restricted', 'empty'):
        tokens.append(dict(token_sha256=hashlib.sha256((name + '-test-secret').encode()).hexdigest(),
                           user_id='admin' if name in ('readonly', 'restricted', 'empty') else name,
                           scopes=['data:read'] if name in ('viewer', 'readonly') else ['data:read', 'data:ingest', 'reviews:write'],
                           camera_ids=[] if name == 'empty' else ['cam_demo_01'],
                           **({'recording_ids': []} if name == 'restricted' else {})))
    private_json(tmp_path / 'auth.json', {'tokens': tokens})
    settings = Settings(_env_file=None, database_url=dsn, database_schema=schema, auth_file=tmp_path/'auth.json', configs_dir=snapshots,
                        config_pins_file=tmp_path/'pins.json', media_root=tmp_path/'media', schema_path=ROOT/'docs/database/schema.sql',
                        ffprobe_path=os.environ.get('PARTRACE_TEST_FFPROBE', 'ffprobe'))
    manifest = json.loads((ROOT / 'docs/api/examples/v0.3/recording-import-mock-request.json').read_text())
    query = dict(recording_id='rec_mock_001', camera_id='cam_demo_01', zone_id='E3', start_offset_ms=0, end_offset_ms=120000)
    review = json.loads((ROOT / 'docs/api/examples/v0.3/review-create-request.json').read_text())
    apps = []
    def client(name='admin'):
        app = create_app(settings=settings)
        apps.append(app)
        return TestClient(app, headers={'Authorization': f'Bearer {name}-test-secret'})
    yield dict(settings=settings, manifest=manifest, query=query, review=review, client=client, tmp=tmp_path, dsn=dsn, schema=schema)
    for app in apps:
        if hasattr(app.state, 'backend'):
            app.state.backend.close()
    with psycopg.connect(dsn, autocommit=True) as connection:
        connection.execute(sql.SQL('DROP SCHEMA {} CASCADE').format(sql.Identifier(schema)))


def test_data_loop_replay_and_service_restart(context):
    with context['client']() as client:
        check(client.get('/api/v1/user'), 'UserResponse')
        check(client.get('/api/v1/configs/demo_v1'), 'ConfigResponse')
        default_zone = dict(context['manifest']['events'][0], event_id='evt_zone_default')
        del default_zone['zone_id']
        null_zone = dict(context['manifest']['events'][0], event_id='evt_zone_null')
        null_zone['zone_id'] = None
        context['manifest']['events'] += [default_zone, null_zone]
        first = check(client.post('/api/v1/recordings', json=context['manifest']), 'ImportResponse', 201)
        replay = check(client.post('/api/v1/recordings', json=context['manifest']), 'ImportResponse')
        assert replay['replayed'] and first['recording'] == replay['recording']
        check(client.get('/api/v1/recordings'), 'RecordingListResponse')
        check(client.get('/api/v1/recordings/rec_mock_001'), 'RecordingResponse')
        assert check(client.get('/api/v1/events', params=context['query']), 'EventsResponse')['query_status'] == 'matched'
        detail = check(client.get('/api/v1/events/evt_mock_001'), 'EventResponse')
        assert detail['observed_points'] == []
        assert detail['event']['shelf_level'] == 3
        assert check(client.get('/api/v1/events', params=context['query']), 'EventsResponse')['events'][0]['shelf_level'] == 3
        assert check(client.get('/api/v1/events/evt_zone_default'), 'EventResponse')['event']['zone_id'] == 'E3'
        assert check(client.get('/api/v1/events/evt_zone_null'), 'EventResponse')['event']['zone_id'] is None
        check(client.get('/api/v1/evidence/evd_mock_001'), 'EvidenceResponse')
        check(client.get('/api/v1/evidence/evd_mock_001/media'), 'ErrorResponse', 404)
        saved = check(client.post('/api/v1/review-records', json=context['review']), 'ReviewResponse', 201)
        assert saved['review']['reviewer_id'] == 'admin'
        assert check(client.post('/api/v1/review-records', json=context['review']), 'ReviewResponse') == saved
    with context['client']() as restarted:
        assert check(restarted.get('/api/v1/review-records/rev_mock_001'), 'ReviewResponse') == saved
        assert len(check(restarted.get('/api/v1/review-records'), 'ReviewListResponse')['reviews']) == 1
        assert restarted.get('/api/v1/agent/runs').status_code == 404
        assert restarted.post('/api/v1/events/search', json={}).status_code == 422
        assert restarted.get('/api/v1/parcels').status_code == 404
        spec = restarted.get('/openapi.json').json()
        assert sum(len(v) for v in spec['paths'].values()) == 21


def test_auth_role_scope_resource_and_revocation(context):
    with context['client']() as client:
        check(client.post('/api/v1/recordings', json=context['manifest']), 'ImportResponse', 201)
        for name, status in [('viewer', 403), ('readonly', 403), ('disabled', 403), ('nomap', 401), ('restricted', 403), ('empty', 403)]:
            with context['client'](name) as denied:
                check(denied.post('/api/v1/recordings', json=context['manifest']), 'ErrorResponse', status)
        with context['client']('viewer') as viewer:
            check(viewer.get('/api/v1/events', params=context['query']), 'EventsResponse')
            check(viewer.post('/api/v1/review-records', json=context['review']), 'ErrorResponse', 403)
        with context['client']('restricted') as restricted:
            check(restricted.get('/api/v1/events/evt_mock_001'), 'ErrorResponse', 403)
            assert check(restricted.get('/api/v1/recordings'), 'RecordingListResponse')['recordings'] == []
        check(client.get('/api/v1/user', headers={'Authorization': 'Bearer unknown'}), 'ErrorResponse', 401)
        check(client.get('/api/v1/user', headers={'Authorization': ''}), 'ErrorResponse', 401)
        registry = json.loads(context['settings'].auth_file.read_text())
        registry['tokens'] = [t for t in registry['tokens'] if t['user_id'] != 'admin']
        private_json(context['settings'].auth_file, registry)
        check(client.get('/api/v1/user'), 'ErrorResponse', 401)


def test_failed_import_does_not_publish_any_partial_facts(context):
    with context['client']() as client:
        check(client.post('/api/v1/recordings', json=context['manifest']), 'ImportResponse', 201)
        conflict = copy.deepcopy(context['manifest'])
        conflict['recording']['recording_id'] = 'another_recording'
        # Reuse an event's global identity: recording insert must roll back too.
        check(client.post('/api/v1/recordings', json=conflict), 'ErrorResponse', 409)
        check(client.get('/api/v1/recordings/another_recording'), 'ErrorResponse', 404)
        assert len(check(client.get('/api/v1/recordings'), 'RecordingListResponse')['recordings']) == 1
        changed = copy.deepcopy(context['manifest'])
        changed['events'][0]['summary'] = 'different'
        check(client.post('/api/v1/recordings', json=changed), 'ErrorResponse', 409)
        assert client.get('/api/v1/events/evt_mock_001').json()['event']['summary'] == context['manifest']['events'][0]['summary']


def test_query_boundaries_and_review_conflicts(context):
    with context['client']() as client:
        check(client.post('/api/v1/recordings', json=context['manifest']), 'ImportResponse', 201)
        for start, end, status, relation in [(0, 1000, 'no_detections', 'full'), (119000, 130000, 'no_detections', 'partial'), (120000, 130000, 'outside_recording', 'outside')]:
            response = check(client.get('/api/v1/events', params=context['query'] | dict(start_offset_ms=start, end_offset_ms=end)), 'EventsResponse')
            assert (response['query_status'], response['recording_range']['relation']) == (status, relation)
        for overrides in [dict(start_offset_ms=-1), dict(start_offset_ms='1.5'), dict(start_offset_ms=100,end_offset_ms=100), dict(unknown='x'), dict(zone_id='missing'), dict(camera_id='wrong'), dict(start='2026-01-01T00:00:00Z')]:
            check(client.get('/api/v1/events', params=context['query'] | overrides), 'ErrorResponse', 422)
        utc_query = {k: v for k,v in context['query'].items() if not k.endswith('_offset_ms')} | dict(start='2026-01-01T00:00:00Z', end='2026-01-01T00:00:01Z')
        assert check(client.get('/api/v1/events', params=utc_query), 'ErrorResponse', 422)['error']['code'] == 'clock_unverified'
        check(client.get('/api/v1/events?recording_id=rec_mock_001&recording_id=other'), 'ErrorResponse', 422)
        check(client.post('/api/v1/review-records', json=context['review'] | {'reviewer_id':'other'}), 'ErrorResponse', 422)
        check(client.post('/api/v1/review-records', json=context['review']), 'ReviewResponse', 201)
        check(client.post('/api/v1/review-records', json=context['review'] | {'decision':'confirmed'}), 'ErrorResponse', 409)
        with context['client']('other') as other:
            check(other.post('/api/v1/review-records', json=context['review']), 'ErrorResponse', 403)


def test_concurrent_idempotent_requests(context):
    with context['client']() as client:
        def send_import(_):
            return client.post('/api/v1/recordings', json=context['manifest'])
        with ThreadPoolExecutor(max_workers=3) as workers:
            results = list(workers.map(send_import, range(3)))
        assert sum(r.status_code == 201 for r in results) == 1
        assert sorted(r.status_code for r in results) == [200,200,201], [r.text for r in results]
        # The advisory lock plus fresh statement snapshot replays the same durable fact.
        check(client.post('/api/v1/recordings', json=context['manifest']), 'ImportResponse')
        def send_review(_):
            return client.post('/api/v1/review-records', json=context['review'])
        with ThreadPoolExecutor(max_workers=3) as workers:
            results = list(workers.map(send_review, range(3)))
        assert sum(r.status_code == 201 for r in results) == 1
        assert sorted(r.status_code for r in results) == [200,200,201], [r.text for r in results]
        assert len(client.get('/api/v1/review-records').json()['reviews']) == 1


def test_real_video_and_image_upload_retrieval_and_media_loss(context, tmp_path):
    import shutil
    ffprobe = context['settings'].ffprobe_path
    if not shutil.which(ffprobe) or not shutil.which('ffmpeg'):
        pytest.skip('requires actual ffprobe and ffmpeg; no probe substitute accepted')
    video = tmp_path/'tiny.mp4'
    subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','color=c=blue:s=64x64:r=10:d=1','-c:v','libx264','-pix_fmt','yuv420p','-y',str(video)],check=True,timeout=30)
    image = io.BytesIO()
    Image.new('RGB',(64,64),'blue').save(image,format='PNG')
    manifest = copy.deepcopy(context['manifest'])
    manifest['recording'].update(source_mode='prerecorded', duration_ms=1000, file_name='tiny.mp4')
    manifest['events'][0].update(detection_source='manual', start_offset_ms=100, end_offset_ms=600)
    manifest['evidence'][0].update(offset_ms=800, file_name='frame.png')
    files = [('files',('tiny.mp4',video.read_bytes(),'video/mp4')), ('files',('frame.png',image.getvalue(),'image/png'))]
    manifest['recording']['started_at'] = '2026-10-16T00:00:00Z'
    with context['client']() as client:
        check(client.get('/api/v1/recordings/rec_mock_001'), 'ErrorResponse',404)
        check(client.post('/api/v1/recordings', data={'manifest':json.dumps(manifest)}, files=files), 'ImportResponse',201)
        utc_query = dict(recording_id='rec_mock_001',camera_id='cam_demo_01',zone_id='E3',start='2026-10-16T00:00:00.200Z',end='2026-10-16T00:00:00.400Z')
        converted = check(client.get('/api/v1/events',params=utc_query),'EventsResponse')
        assert converted['query']['start_offset_ms'] == 200
        assert converted['query_status'] == 'matched'
        before = {str(p) for p in context['settings'].media_root.rglob('*') if p.is_file()}
        conflict = copy.deepcopy(manifest)
        conflict['recording']['recording_id'] = 'another_real_recording'
        conflict['recording']['started_at'] = None
        check(client.post('/api/v1/recordings', data={'manifest':json.dumps(conflict)}, files=files), 'ErrorResponse',409)
        assert before == {str(p) for p in context['settings'].media_root.rglob('*') if p.is_file()}
        check(client.get('/api/v1/recordings/another_real_recording'), 'ErrorResponse',404)
        frame = check(client.get('/api/v1/evidence/evd_mock_001'), 'EvidenceResponse')['evidence']
        assert frame['media_status'] == 'available'
        assert client.get(frame['media_url']).content == image.getvalue()
        detail = check(client.get('/api/v1/events/evt_mock_001'), 'EventResponse')
        assert detail['observed_points'][0]['offset_ms'] == 800  # true context frame outside the event
        review = copy.deepcopy(context['review'])
        review['query'].update(end_offset_ms=1000)
        check(client.post('/api/v1/review-records', json=review), 'ReviewResponse',201)
        oversized_image = io.BytesIO()
        Image.new('1', (4097, 4096)).save(oversized_image, format='PNG')
        stored_frame = next((context['settings'].media_root/'objects').rglob('*.png'))
        # Retention failures after a successful real import must agree across
        # frame metadata, event/review bundles and the actual media endpoint.
        for damaged in (b'', image.getvalue()[:-12], b'not a decodable image', oversized_image.getvalue()):
            stored_frame.write_bytes(damaged)
            missing = check(client.get('/api/v1/evidence/evd_mock_001'), 'EvidenceResponse')['evidence']
            assert missing['evidence_id'] == frame['evidence_id']
            assert missing['media_status'] == 'missing'
            assert missing['media_url'] is None
            assert check(client.get(frame['media_url']), 'ErrorResponse',404)['error']['code'] == 'media_missing'
            for route, schema in [('/api/v1/events/evt_mock_001', 'EventResponse'),
                                  ('/api/v1/review-records/rev_mock_001', 'ReviewResponse')]:
                retained = check(client.get(route), schema)['evidence'][0]
                assert retained['evidence_id'] == frame['evidence_id']
                assert retained['media_status'] == 'missing'
            stored_frame.write_bytes(image.getvalue())
            assert check(client.get('/api/v1/evidence/evd_mock_001'), 'EvidenceResponse')['evidence']['media_status'] == 'available'
            assert client.get(frame['media_url']).content == image.getvalue()
        for path in (context['settings'].media_root/'objects').rglob('*.png'):
            path.unlink()
        assert check(client.get('/api/v1/evidence/evd_mock_001'), 'EvidenceResponse')['evidence']['media_status'] == 'missing'
        check(client.get(frame['media_url']), 'ErrorResponse',404)
        assert check(client.get('/api/v1/review-records/rev_mock_001'), 'ReviewResponse')['evidence'][0]['evidence_id'] == 'evd_mock_001'


@pytest.mark.parametrize('pins', ['null', '[]', '{"demo_v1":"' + '0'*64 + '","demo_v1":"' + '1'*64 + '"}'])
def test_invalid_pins_fail_closed(context, pins):
    context['settings'].config_pins_file.write_text(pins)
    with context['client']() as client:
        assert check(client.get('/api/v1/configs/demo_v1'), 'ErrorResponse',503)['error']['code'] == 'config_unavailable'
