"""A gallery IDs stay distinct from local tracks; legacy fact digests stay stable."""
import copy
import hashlib
import json

import psycopg
from psycopg import sql
import pytest

from backend.core.models import RecordingManifest
from backend.manage import ManagementError, migrate_event_person_id
from backend.services.facts import canonical
from backend.tests.test_api_postgres import context, check

pytestmark = pytest.mark.integration


def test_gallery_identity_roundtrip_and_immutable_fact(context):
    manifest = copy.deepcopy(context['manifest'])
    manifest['events'][0].update(person_id='G0007', track_key='P003')
    with context['client']() as client:
        check(client.post('/api/v1/recordings', json=manifest), 'ImportResponse', 201)
        event = check(client.get('/api/v1/events/evt_mock_001'), 'EventResponse')['event']
        assert event['person_id'] == 'G0007' and event['track_key'] == 'P003'
        assert client.post('/api/v1/recordings', json=manifest).status_code == 200
        changed = copy.deepcopy(manifest)
        changed['events'][0]['person_id'] = 'G0008'
        assert client.post('/api/v1/recordings', json=changed).status_code == 409
    with psycopg.connect(context['dsn']) as connection:
        with pytest.raises(psycopg.errors.CheckViolation):
            connection.execute(sql.SQL('UPDATE {}.events SET person_id=%s').format(sql.Identifier(context['schema'])), ('G0008',))


def test_unknown_null_preserves_pre_field_manifest_digest(context):
    legacy = copy.deepcopy(context['manifest'])
    for event in legacy['events']:event.pop('person_id', None)
    serialized = RecordingManifest.model_validate(legacy).model_dump(mode='json')
    assert all('person_id' not in event for event in serialized['events'])
    before = hashlib.sha256(canonical({'manifest': serialized, 'files': {}})).hexdigest()
    with context['client']() as client:
        check(client.post('/api/v1/recordings', json=legacy), 'ImportResponse', 201)
        explicit_null = copy.deepcopy(legacy)
        explicit_null['events'][0]['person_id'] = None
        assert RecordingManifest.model_validate(explicit_null).model_dump(mode='json') == serialized
        assert client.post('/api/v1/recordings', json=explicit_null).status_code == 200
        assert client.get('/api/v1/events/evt_mock_001').json()['event']['person_id'] is None
    with psycopg.connect(context['dsn']) as connection:
        actual = connection.execute(sql.SQL('SELECT manifest_sha256 FROM {}.recordings').format(sql.Identifier(context['schema']))).fetchone()[0]
        assert actual == before


@pytest.mark.parametrize('value', ['P003', 'unknown', '', 'G123', 'G0001\n', ' G0001', 123, True])
def test_invalid_identity_rejected_without_publication(context, value):
    manifest = copy.deepcopy(context['manifest'])
    manifest['events'][0]['person_id'] = value
    with context['client']() as client:
        assert client.post('/api/v1/recordings', json=manifest).status_code == 422
        assert client.get('/api/v1/recordings').json()['recordings'] == []


def test_additive_migration_keeps_old_rows_null_and_replay(context):
    with context['client']() as client:
        check(client.post('/api/v1/recordings', json=context['manifest']), 'ImportResponse', 201)
    with psycopg.connect(context['dsn']) as connection:
        connection.execute(sql.SQL('ALTER TABLE {}.events DROP COLUMN person_id').format(sql.Identifier(context['schema'])))
        before = connection.execute(sql.SQL('SELECT * FROM {}.events').format(sql.Identifier(context['schema']))).fetchall()
    migrate_event_person_id(context['settings'])
    with psycopg.connect(context['dsn']) as connection:
        columns = [r[0] for r in connection.execute("SELECT column_name FROM information_schema.columns WHERE table_schema=%s AND table_name='events' AND column_name<>'person_id' ORDER BY ordinal_position", (context['schema'],))]
        query = sql.SQL('SELECT {} FROM {}.events').format(sql.SQL(',').join(map(sql.Identifier, columns)), sql.Identifier(context['schema']))
        assert connection.execute(query).fetchall() == before
        assert connection.execute(sql.SQL('SELECT person_id FROM {}.events').format(sql.Identifier(context['schema']))).fetchone()[0] is None
    with pytest.raises(ManagementError, match='already exists'):
        migrate_event_person_id(context['settings'])
    with context['client']() as client:
        assert client.post('/api/v1/recordings', json=context['manifest']).status_code == 200
