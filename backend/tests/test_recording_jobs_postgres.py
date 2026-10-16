"""Real isolated PostgreSQL job/lease/publication checks; actual media tools are required."""

import copy
from concurrent.futures import ThreadPoolExecutor
import io
import json
import shutil
import subprocess

from PIL import Image
import psycopg
from psycopg import sql
import pytest

from backend.manage import ManagementError, migrate_recording_jobs, migrate_source_recordings, retry_recording_job
from backend.tests.test_api_postgres import context, check, private_json

pytestmark = pytest.mark.integration
PREFIX = '/api/v1/recording-jobs'


@pytest.fixture
def jobs_context(context):
    auth = context['settings'].auth_file
    credentials = json.loads(auth.read_text())
    for entry in credentials['tokens']:
        if entry['user_id'] in {'admin', 'other'} and 'data:ingest' in entry['scopes']:
            entry['scopes'].append('vision:work')
    private_json(auth, credentials)
    return context


@pytest.fixture
def raw_video(jobs_context, tmp_path):
    probe = jobs_context['settings'].ffprobe_path
    if not shutil.which(probe) or not shutil.which('ffmpeg'):
        pytest.skip('requires actual ffprobe and ffmpeg; media validation is never faked')
    path = tmp_path / 'source.mp4'
    subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'color=c=blue:s=64x64:r=10:d=1',
                    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-y', str(path)], check=True, timeout=30)
    return path.read_bytes()


def metadata(job_id='people_1', **changes):
    return dict(job_id=job_id, camera_id='cam_demo_01', config_version='demo_v1', sdk_event_id='sdk_' + job_id,
                duration_ms=1000, sdk_timestamp_ms=None) | changes


def upload(client, video, value=None):
    return client.post(PREFIX, data={'manifest': json.dumps(value or metadata())}, files={'video': ('source.mp4', video, 'video/mp4')})


def source_upload(client, video, value=None, **overrides):
    return client.post('/api/v1/recordings', data={'mode': 'source', 'manifest': json.dumps(value or metadata())},
                       files=overrides.get('files', [('files', ('source.mp4', video, 'video/mp4'))]))


def pending(client, job_id='people_1'):
    recording = check(client.get('/api/v1/recordings/' + job_id), 'RecordingResponse')['recording']
    assert recording['publication_status'] == 'pending' and recording['manifest_sha256'] is None
    return recording


def result(context, job_id='people_1'):
    value = copy.deepcopy(context['manifest'])
    value['recording'].update(recording_id=job_id, source_mode='sdk', started_at=None, duration_ms=1000, file_name='source.mp4')
    value['events'][0].update(event_id='event_' + job_id, detection_source='automatic', start_offset_ms=100, end_offset_ms=800)
    value['evidence'][0].update(evidence_id='frame_' + job_id, event_id='event_' + job_id, offset_ms=300, file_name='frame.png')
    image = io.BytesIO()
    Image.new('RGB', (64, 64), 'blue').save(image, format='PNG')
    return value, [('files', ('frame.png', image.getvalue(), 'image/png'))]


def lease_headers(claim):
    return {'X-Lease-Token': claim['lease_token']}


def expire(context, job_id='people_1'):
    with psycopg.connect(context['dsn']) as connection:
        connection.execute(sql.SQL("UPDATE {}.recording_jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE job_id=%s").format(sql.Identifier(context['schema'])), (job_id,))


def test_reject_unauthorized_before_parsing_upload_or_claim(jobs_context):
    with jobs_context['client']('viewer') as client:
        assert client.post(PREFIX, content=b'not multipart').status_code == 403
        assert client.post(PREFIX + '/claim', json={'worker_id': 'worker'}).status_code == 403
    with jobs_context['client']('readonly') as client:
        assert client.post(PREFIX, content=b'not multipart').status_code == 403


def test_raw_intake_replay_conflict_and_restart(jobs_context, raw_video):
    with jobs_context['client']() as client:
        first = check(upload(client, raw_video), 'RecordingJobUploadResponse', 202)
        assert first['job']['status'] == 'queued'
        assert first['job']['recording_id'] == 'people_1'
        assert first['job']['sdk_timestamp_ms'] is None
        assert 'storage_key' not in first['job']
        assert check(client.get('/api/v1/recordings'), 'RecordingListResponse')['recordings'] == []
        replay = check(upload(client, raw_video), 'RecordingJobUploadResponse')
        assert replay['replayed'] and replay['job'] == first['job']
        assert upload(client, raw_video, metadata(sdk_timestamp_ms=1)).status_code == 409
        assert upload(client, raw_video, metadata('same_event_other_id', sdk_event_id='sdk_people_1')).status_code == 409
        assert upload(client, b'not video', metadata('bad_video')).status_code == 422
        assert upload(client, raw_video, metadata('wrong_camera', camera_id='cam_other')).status_code == 403
        check(client.post('/api/v1/recordings', json=jobs_context['manifest']), 'ImportResponse', 201)
        assert upload(client, raw_video, metadata('rec_mock_001')).status_code == 409
    with jobs_context['client']() as restarted:
        restored = check(restarted.get(PREFIX + '/people_1'), 'RecordingJobResponse')['job']
        assert restored == first['job']
        with jobs_context['client']('restricted') as denied:
            assert denied.get(PREFIX + '/people_1').status_code == 403


def test_native_opaque_event_id_upload_is_preserved_and_idempotent(jobs_context, raw_video):
    value = metadata(sdk_event_id='native/event@123|x')
    with jobs_context['client']() as client:
        first = check(upload(client, raw_video, value), 'RecordingJobUploadResponse', 202)
        assert first['job']['sdk_event_id'] == value['sdk_event_id']
        replay = check(upload(client, raw_video, value), 'RecordingJobUploadResponse')
        assert replay['replayed'] and replay['job'] == first['job']
        assert upload(client, raw_video, value | {'job_id': 'changed_job'}).status_code == 409
        assert upload(client, raw_video, value | {'sdk_event_id': value['sdk_event_id'] + '/different'}).status_code == 409
    with jobs_context['client']() as restarted:
        restored = check(restarted.get(PREFIX + '/people_1'), 'RecordingJobResponse')['job']
        assert restored['sdk_event_id'] == value['sdk_event_id']


def test_leases_heartbeat_expiry_reclaim_and_failure(jobs_context, raw_video):
    with jobs_context['client']() as client:
        upload(client, raw_video)
        first = check(client.post(PREFIX + '/claim', json={'worker_id': 'worker_1'}), 'RecordingJobClaimResponse')
        assert first['job']['attempts'] == 1
        assert client.get(PREFIX + '/people_1/media', headers=lease_headers(first)).content == raw_video
        assert client.get(PREFIX + '/people_1/media').status_code == 409
        assert check(client.post(PREFIX + '/claim', json={'worker_id': 'worker_2'}), 'RecordingJobClaimResponse')['job'] is None
        heart = check(client.post(PREFIX + '/people_1/heartbeat', json={}, headers=lease_headers(first)), 'RecordingJobResponse')
        assert heart['job']['lease_expires_at'] >= first['job']['lease_expires_at']
        expire(jobs_context)
        assert client.post(PREFIX + '/people_1/heartbeat', json={}, headers=lease_headers(first)).status_code == 409
        second = check(client.post(PREFIX + '/claim', json={'worker_id': 'worker_2'}), 'RecordingJobClaimResponse')
        assert second['job']['attempts'] == 2 and second['lease_token'] != first['lease_token']
        assert client.post(PREFIX + '/people_1/fail', json={'code': 'worker_failed'}, headers=lease_headers(first)).status_code == 409
        failed = check(client.post(PREFIX + '/people_1/fail', json={'code': 'worker_failed'}, headers=lease_headers(second)), 'RecordingJobResponse')
        assert failed['job']['status'] == 'failed'
        assert check(client.post(PREFIX + '/people_1/fail', json={'code': 'worker_failed'}, headers=lease_headers(second)), 'RecordingJobResponse') == failed
        assert upload(client, raw_video).json()['job']['status'] == 'failed'
        assert client.post(PREFIX + '/claim', json={'worker_id': 'worker_3'}).json()['job'] is None
        retry_recording_job(jobs_context['settings'], 'people_1')
        assert client.get(PREFIX + '/people_1').json()['job']['status'] == 'queued'
        assert client.post(PREFIX + '/people_1/fail', json={'code': 'worker_failed'}, headers=lease_headers(second)).status_code == 409
        retried = client.post(PREFIX + '/claim', json={'worker_id': 'worker_3'}).json()
        assert retried['job']['attempts'] == 3


def test_complete_publishes_whole_bundle_and_job_atomically(jobs_context, raw_video):
    with jobs_context['client']() as client:
        upload(client, raw_video)
        claim = client.post(PREFIX + '/claim', json={'worker_id': 'worker'}).json()
        manifest, files = result(jobs_context)
        wrong = copy.deepcopy(manifest)
        wrong['recording']['started_at'] = '2026-10-16T00:00:00Z'
        assert client.post(PREFIX + '/people_1/complete', data={'manifest': json.dumps(wrong)}, files=files, headers=lease_headers(claim)).status_code == 422
        assert client.post(PREFIX + '/people_1/complete', data={'manifest': json.dumps(manifest)}, files=[('files', ('frame.png', b'bad image', 'image/png'))], headers=lease_headers(claim)).status_code == 422
        pending(client)
        completed = check(client.post(PREFIX + '/people_1/complete', data={'manifest': json.dumps(manifest)}, files=files, headers=lease_headers(claim)), 'RecordingJobCompleteResponse', 201)
        assert completed['job']['status'] == 'completed'
        assert completed['recording']['started_at'] is None
        assert completed['recording']['publication_status'] == 'published'
        assert completed['event_count'] == 1 and completed['evidence_count'] == 1
        replay = check(client.post(PREFIX + '/people_1/complete', data={'manifest': json.dumps(manifest)}, files=files, headers=lease_headers(claim)), 'RecordingJobCompleteResponse')
        assert replay['replayed'] and replay['job'] == completed['job']
        assert len(list(jobs_context['settings'].media_root.rglob('*.mp4'))) == 1
        frame = check(client.get('/api/v1/evidence/frame_people_1'), 'EvidenceResponse')['evidence']
        assert client.get(frame['media_url']).content == files[0][1][1]
        assert client.post(PREFIX + '/people_1/fail', json={'code': 'late_failure'}, headers=lease_headers(claim)).status_code == 409
        with pytest.raises(ManagementError, match='failed job'):
            retry_recording_job(jobs_context['settings'], 'people_1')
        # The old complete-package endpoint cannot seize a raw job identity.
        legacy = copy.deepcopy(jobs_context['manifest'])
        legacy['recording']['recording_id'] = 'people_1'
        assert client.post('/api/v1/recordings', json=legacy).status_code == 409


def test_concurrent_claims_and_stale_completion_are_fenced(jobs_context, raw_video):
    with jobs_context['client']() as client:
        upload(client, raw_video)
        def claim(index):
            return client.post(PREFIX + '/claim', json={'worker_id': f'worker_{index}'}).json()
        with ThreadPoolExecutor(max_workers=2) as workers:
            claims = list(workers.map(claim, range(2)))
        assert sum(value['job'] is not None for value in claims) == 1
        old = next(value for value in claims if value['job'] is not None)
        expire(jobs_context)
        new = claim(3)
        manifest, files = result(jobs_context)
        assert client.post(PREFIX + '/people_1/complete', data={'manifest': json.dumps(manifest)}, files=files, headers=lease_headers(old)).status_code == 409
        pending(client)
        assert client.post(PREFIX + '/people_1/complete', data={'manifest': json.dumps(manifest)}, files=files, headers=lease_headers(new)).status_code == 201


def test_completion_rechecks_current_grants_after_media_validation(jobs_context, raw_video, monkeypatch):
    with jobs_context['client']() as client:
        upload(client, raw_video)
        claim = client.post(PREFIX + '/claim', json={'worker_id': 'worker'}).json()
        manifest, files = result(jobs_context)
        store = client.app.state.backend.media
        prepare = store.prepare
        auth_path = jobs_context['settings'].auth_file
        credentials = json.loads(auth_path.read_text())
        def revoke_after_prepare(*args, **kwargs):
            prepared = prepare(*args, **kwargs)
            revoked = copy.deepcopy(credentials)
            for entry in revoked['tokens']:
                if entry['user_id'] == 'admin':
                    entry['scopes'] = [scope for scope in entry['scopes'] if scope != 'data:ingest']
            private_json(auth_path, revoked)
            return prepared
        monkeypatch.setattr(store, 'prepare', revoke_after_prepare)
        response = client.post(PREFIX + '/people_1/complete', data={'manifest': json.dumps(manifest)}, files=files, headers=lease_headers(claim))
        assert response.status_code == 403
        pending(client)
        assert client.get(PREFIX + '/people_1').json()['job']['status'] == 'processing'
        assert not list(jobs_context['settings'].media_root.rglob('*.png'))


def test_sql_conflict_rolls_back_recording_and_job_transition(jobs_context, raw_video):
    with jobs_context['client']() as client:
        existing = copy.deepcopy(jobs_context['manifest'])
        existing['events'][0]['event_id'] = 'event_people_1'
        existing['evidence'][0]['event_id'] = 'event_people_1'
        check(client.post('/api/v1/recordings', json=existing), 'ImportResponse', 201)
        upload(client, raw_video)
        claim = client.post(PREFIX + '/claim', json={'worker_id': 'worker'}).json()
        manifest, files = result(jobs_context)
        response = client.post(PREFIX + '/people_1/complete', data={'manifest': json.dumps(manifest)}, files=files, headers=lease_headers(claim))
        assert response.status_code == 409
        pending(client)
        assert client.get(PREFIX + '/people_1').json()['job']['status'] == 'processing'
        assert not list(jobs_context['settings'].media_root.rglob('*.png'))


def test_lease_expiring_during_validation_cannot_publish(jobs_context, raw_video, monkeypatch):
    with jobs_context['client']() as client:
        upload(client, raw_video)
        claim = client.post(PREFIX + '/claim', json={'worker_id': 'worker'}).json()
        manifest, files = result(jobs_context)
        store = client.app.state.backend.media
        prepare = store.prepare
        def expire_after_prepare(*args, **kwargs):
            prepared = prepare(*args, **kwargs)
            expire(jobs_context)
            return prepared
        monkeypatch.setattr(store, 'prepare', expire_after_prepare)
        response = client.post(PREFIX + '/people_1/complete', data={'manifest': json.dumps(manifest)}, files=files, headers=lease_headers(claim))
        assert response.status_code == 409
        pending(client)
        assert not list(jobs_context['settings'].media_root.rglob('*.png'))


def test_additive_migration_preserves_facts_and_refuses_repeat(jobs_context):
    with jobs_context['client']() as client:
        check(client.post('/api/v1/recordings', json=jobs_context['manifest']), 'ImportResponse', 201)
    with psycopg.connect(jobs_context['dsn']) as connection:
        connection.execute(sql.SQL('DROP TABLE {}.recording_jobs').format(sql.Identifier(jobs_context['schema'])))
    migrate_recording_jobs(jobs_context['settings'])
    with jobs_context['client']() as client:
        assert client.get('/api/v1/recordings/rec_mock_001').status_code == 200
    with pytest.raises(ManagementError, match='already exists'):
        migrate_recording_jobs(jobs_context['settings'])


def test_source_recordings_upload_replay_cross_entry_and_publish(jobs_context, raw_video):
    with jobs_context['client']() as client:
        first = check(source_upload(client, raw_video), 'SourceRecordingResponse', 202)
        assert first['recording'] == pending(client)
        assert first['job']['status'] == 'queued'
        assert first['recording']['recording_id'] == first['job']['job_id']
        assert first['recording']['started_at'] is None
        assert set(first) == {'api_version', 'recording', 'job', 'replayed'}
        assert 'storage_key' not in first['recording']
        replay = check(source_upload(client, raw_video), 'SourceRecordingResponse')
        assert replay['replayed'] and replay['recording'] == first['recording']
        compatibility = check(upload(client, raw_video), 'RecordingJobUploadResponse')
        assert compatibility['replayed'] and 'recording' not in compatibility
        assert source_upload(client, raw_video, metadata(duration_ms=900)).status_code in {409, 422}
        assert source_upload(client, raw_video, metadata('same_event', sdk_event_id='sdk_people_1')).status_code == 409
        assert client.get('/api/v1/recordings/same_event').status_code == 404
        claim = client.post(PREFIX + '/claim', json={'worker_id': 'worker'}).json()
        manifest, files = result(jobs_context)
        completed = check(client.post(PREFIX + '/people_1/complete', data={'manifest': json.dumps(manifest)}, files=files,
                                      headers=lease_headers(claim)), 'RecordingJobCompleteResponse', 201)
        assert completed['recording']['publication_status'] == 'published'
        assert completed['recording']['created_at'] == first['recording']['created_at']
        assert len(check(client.get('/api/v1/recordings'), 'RecordingListResponse')['recordings']) == 1
        replay = check(source_upload(client, raw_video), 'SourceRecordingResponse')
        assert replay['replayed'] and replay['recording'] == completed['recording']
        assert replay['job']['status'] == 'completed'
        assert len(list(jobs_context['settings'].media_root.rglob('*.mp4'))) == 1
    with jobs_context['client']() as restarted:
        assert check(restarted.get('/api/v1/recordings/people_1'), 'RecordingResponse')['recording'] == completed['recording']


def test_pending_facts_fail_before_region_clock_coverage_or_models(jobs_context, raw_video):
    from backend.tests.test_agent_api_postgres import client_for, question
    with jobs_context['client']() as client:
        # Compatibility intake has the same source-first persistence guarantee.
        upload(client, raw_video)
        pending(client)
        query = jobs_context['query'] | {'recording_id': 'people_1', 'zone_id': 'unknown_zone'}
        utc_query = {key: value for key, value in query.items() if not key.endswith('_offset_ms')}
        utc_query |= {'start': '2026-10-16T00:00:00Z', 'end': '2026-10-16T00:00:01Z'}
        for scope in (query, utc_query):
            response = check(client.get('/api/v1/events', params=scope), 'ErrorResponse', 409)
            assert response['error']['code'] == 'recording_not_ready'
            response = check(client.post('/api/v1/events/search', json={'query_text': '有人取件', 'filter': scope, 'top_k': 5}),
                             'ErrorResponse', 409)
            assert response['error']['code'] == 'recording_not_ready'
        assert check(client.get('/api/v1/recordings'), 'RecordingListResponse')['recordings'] == []
        assert check(client.get('/api/v1/review-records'), 'ReviewListResponse')['reviews'] == []
        response = check(client.get('/api/v1/review-records', params={'recording_id': 'people_1'}), 'ErrorResponse', 409)
        assert response['error']['code'] == 'recording_not_ready'
        with jobs_context['client']('restricted') as denied:
            assert denied.get('/api/v1/recordings/people_1').status_code == 403
            assert denied.get('/api/v1/events', params=query).status_code == 403
    with client_for(jobs_context) as agent:
        question(agent)
        response = check(agent.post('/api/v1/agent/plans', json={'input_message_id': 'question_api', 'scope': query}),
                         'ErrorResponse', 409)
        assert response['error']['code'] == 'recording_not_ready'
        plan = {'query': query, 'query_text': '查找货架活动', 'steps': ['查询']}
        response = check(agent.post('/api/v1/agent/runs', json={'run_id': 'pending_run', 'input_message_id': 'question_api', 'plan': plan}),
                         'ErrorResponse', 409)
        assert response['error']['code'] == 'recording_not_ready'
        assert agent.get('/api/v1/agent/runs/pending_run').status_code == 404


def test_source_upload_strict_parts_and_unauthorized_before_body(jobs_context, raw_video):
    with jobs_context['client']('viewer') as denied:
        assert denied.post('/api/v1/recordings', content=b'unparsed body').status_code == 403
    with jobs_context['client']() as client:
        for files in ([('files', ('other.mp4', raw_video, 'video/mp4'))],
                      [('files', ('source.mp4', raw_video, 'video/mp4')), ('files', ('extra.mp4', raw_video, 'video/mp4'))],
                      [('files', ('source.mp4', raw_video, 'video/mp4')), ('files', ('source.mp4', raw_video, 'video/mp4'))]):
            assert source_upload(client, raw_video, files=files).status_code == 422
        assert client.post('/api/v1/recordings', data={'mode': 'unknown', 'manifest': json.dumps(metadata())},
                           files={'files': ('source.mp4', raw_video, 'video/mp4')}).status_code == 422
        assert source_upload(client, b'invalid media').status_code == 422
        assert client.get(PREFIX + '/people_1').status_code == 404
        assert client.get('/api/v1/recordings/people_1').status_code == 404
        assert not list(jobs_context['settings'].media_root.rglob('*.mp4'))


def legacy_source_schema(context):
    """Recreate pre-002 columns only inside this test's private random schema."""
    with psycopg.connect(context['dsn']) as connection:
        connection.execute(sql.SQL('SET LOCAL search_path TO {},public').format(sql.Identifier(context['schema'])))
        connection.execute('ALTER TABLE recordings DISABLE TRIGGER recording_keep_history')
        connection.execute("DELETE FROM recordings WHERE publication_status='pending'")
        connection.execute('ALTER TABLE recordings ENABLE TRIGGER recording_keep_history')
        connection.execute('ALTER TABLE recordings DROP COLUMN publication_status CASCADE')
        connection.execute('ALTER TABLE recordings ALTER COLUMN manifest_sha256 SET NOT NULL')
        connection.execute("ALTER TABLE recordings ADD CONSTRAINT ck_recording_manifest CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$')")


def test_source_migration_preserves_published_jobs_and_raw_media(jobs_context, raw_video):
    with jobs_context['client']() as client:
        published = check(client.post('/api/v1/recordings', json=jobs_context['manifest']), 'ImportResponse', 201)['recording']
        upload(client, raw_video, metadata('completed_1'))
        completed_claim = client.post(PREFIX + '/claim', json={'worker_id': 'worker'}).json()
        manifest, files = result(jobs_context, 'completed_1')
        completed = check(client.post(PREFIX + '/completed_1/complete', data={'manifest': json.dumps(manifest)}, files=files,
                                      headers=lease_headers(completed_claim)), 'RecordingJobCompleteResponse', 201)
        upload(client, raw_video)
        claimed = client.post(PREFIX + '/claim', json={'worker_id': 'worker'}).json()
        upload(client, raw_video, metadata('queued_2'))
        before = {job_id: client.get(PREFIX + '/' + job_id).json()['job'] for job_id in ('people_1', 'queued_2')}
        media_paths = {str(path): path.read_bytes() for path in jobs_context['settings'].media_root.rglob('*.mp4')}
    legacy_source_schema(jobs_context)
    migrate_source_recordings(jobs_context['settings'])
    with jobs_context['client']() as client:
        assert client.get('/api/v1/recordings/rec_mock_001').json()['recording'] == published
        assert client.get('/api/v1/recordings/completed_1').json()['recording'] == completed['recording']
        assert client.get(PREFIX + '/completed_1').json()['job'] == completed['job']
        for job_id in before:
            assert client.get(PREFIX + '/' + job_id).json()['job'] == before[job_id]
            assert pending(client, job_id)['created_at'] == before[job_id]['created_at']
        assert client.get(PREFIX + '/people_1/media', headers=lease_headers(claimed)).content == raw_video
        assert {str(path): path.read_bytes() for path in jobs_context['settings'].media_root.rglob('*.mp4')} == media_paths
        assert upload(client, raw_video).json()['replayed']
    with pytest.raises(ManagementError, match='already exists'):
        migrate_source_recordings(jobs_context['settings'])


def test_source_migration_inconsistent_completed_rolls_back(jobs_context, raw_video):
    with jobs_context['client']() as client:
        upload(client, raw_video)
        client.post(PREFIX + '/claim', json={'worker_id': 'worker'})
    legacy_source_schema(jobs_context)
    with psycopg.connect(jobs_context['dsn']) as connection:
        connection.execute(sql.SQL("UPDATE {}.recording_jobs SET status='completed',result_sha256=%s,event_count=0,evidence_count=0,lease_expires_at=NULL,finished_at=clock_timestamp() WHERE job_id='people_1'").format(sql.Identifier(jobs_context['schema'])), ('a' * 64,))
    with pytest.raises(ManagementError, match='rolled back'):
        migrate_source_recordings(jobs_context['settings'])
    with psycopg.connect(jobs_context['dsn']) as connection:
        assert not connection.execute("SELECT 1 FROM information_schema.columns WHERE table_schema=%s AND table_name='recordings' AND column_name='publication_status'", (jobs_context['schema'],)).fetchone()
