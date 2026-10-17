import hashlib
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest

import cv2
import numpy as np

from station_vision.reid.keyframes import select_keyframes,enrollment_candidate
from station_vision.reid.profile_store import ProfileStore,read_archive,atomic_json,digest
from station_vision.reid.session_identity import resolve_session_identities,event_person_id


def vector(cosine=1.):
    v=np.zeros(512,np.float32);v[0]=cosine;v[1]=np.sqrt(1-cosine*cosine)
    return v.tolist()


def samples(cosine=1.):
    result=[]
    for i in range(3):
        _,data=cv2.imencode('.png',np.full((10,10,3),i*30,np.uint8))
        result.append(dict(feature=vector(cosine),frame_id=f'f{i:06d}',time_ms=i*500,
            crop_sha256=hashlib.sha256(data).hexdigest(),png_bytes=data.tobytes(),
            quality={'selection_quality':.8}))
    return result


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.registry=self.root/'data/person_enrollment/profile_registry.json'
    def tearDown(self):self.temp.cleanup()
    def register(self,store,source='a',track='A-t1',cosine=1.,start=0,end=2000):
        return store.register({'sha256':source*64,'path':source+'.mp4'},
            dict(track_id=track,start_ms=start,end_ms=end),samples(cosine),'test')
    def test_reserved_ids_and_rerun_never_overwrite(self):
        atomic_json(self.registry,{'old':'G0007'})
        folder=self.root/'data/person_enrollment/auto_archive/G0010';folder.mkdir(parents=True)
        (folder/'keep.png').write_bytes(b'untouched')
        store=ProfileStore(self.root,'weights');first=self.register(store)
        self.assertEqual(first['person_id'],'G0011')
        before={str(p):digest(p) for p in store.folder.rglob('*.png')}
        replay=self.register(store)
        self.assertEqual(replay['action'],'existing_source_track')
        self.assertEqual(before,{str(p):digest(p) for p in store.folder.rglob('*.png')})
        self.assertEqual(json.loads(self.registry.read_text())['old'],'G0007')
        self.assertEqual(read_archive(store.path)['next_id'],12)
    def test_strong_match_reuses_old_profile_new_source(self):
        store=ProfileStore(self.root,'weights');a=self.register(store)
        b=self.register(store,source='b')
        self.assertEqual(a['person_id'],b['person_id'])
        self.assertEqual(b['action'],'reused_existing_profile')
        data=read_archive(store.path)['profiles'][a['person_id']]
        self.assertEqual(len(data['templates']),3)  # Same pixels from another source do not add representatives.
        self.assertEqual(len(data['historical_templates']),3)
        self.assertTrue(Path(data['historical_templates'][0]['archive_crop']).is_file())
    def test_ambiguous_match_creates_incrementing_possible_duplicate(self):
        store=ProfileStore(self.root,'weights');a=self.register(store)
        b=self.register(store,source='b',cosine=.70)
        self.assertEqual(b['person_id'],'G0002')
        self.assertEqual(b['possible_duplicate_of'],a['person_id'])
        self.assertEqual(b['action'],'created_new_profile_uncertain')
    def test_simultaneous_similar_people_cannot_merge(self):
        store=ProfileStore(self.root,'weights');a=self.register(store)
        b=self.register(store,track='A-t2')
        self.assertNotEqual(a['person_id'],b['person_id'])
        self.assertEqual(b['reason'],'simultaneous_track_conflict')
    def test_new_recipe_rechecks_appearance_instead_of_trusting_track_number(self):
        store=ProfileStore(self.root,'weights');first=self.register(store)
        later=store.register({'sha256':'a'*64,'path':'a.mp4','enrollment_recipe_sha256':'new'},
            dict(track_id='A-t1',start_ms=0,end_ms=2000),samples(0.),'another_run')
        self.assertNotEqual(first['person_id'],later['person_id'])
    def test_different_run_same_source_can_reuse_after_recipe_change(self):
        store=ProfileStore(self.root,'weights');first=self.register(store)
        later=store.register({'sha256':'a'*64,'path':'a.mp4','enrollment_recipe_sha256':'new'},
            dict(track_id='A-t1',start_ms=0,end_ms=2000),samples(),'another_run')
        self.assertEqual(first['person_id'],later['person_id'])
        self.assertIsNone(later['possible_duplicate_of'])
    def test_registry_corruption_refuses_id_reset(self):
        atomic_json(self.registry,{'old':'not-an-id'})
        with self.assertRaises(ValueError):ProfileStore(self.root,'weights')
    def test_weights_and_dimension_validation(self):
        store=ProfileStore(self.root,'weights');self.register(store)
        with self.assertRaises(ValueError):read_archive(store.path,'other')
        data=read_archive(store.path);data['profiles']['G0001']['templates'][0]['feature']=[1.,0.]
        atomic_json(store.path,data)
        with self.assertRaises(ValueError):read_archive(store.path)
    def test_legacy_import_requires_original_hash(self):
        atomic_json(self.registry,{'person_001':'G0001','person_002':'G0002'})
        raw=self.root/'portrait.png';raw.write_bytes(b'photo')
        gallery={'weights_sha256':'weights','profiles':{'G0001':[vector()],'G0002':[vector()]},
            'details':[{'person_id':p,'folder':f'person_00{i}',
                'samples':[{'path':str(raw),'sha256':digest(raw) if i==1 else 'bad','crop':'crop.png'}]}
                for i,p in enumerate(('G0001','G0002'),1)]}
        atomic_json(self.root/'outputs/person_reid_old/gallery.json',gallery)
        store=ProfileStore(self.root,'weights');data=read_archive(store.path)
        self.assertEqual(set(data['profiles']),{'G0001'})
        self.assertEqual(self.register(store,cosine=0.)['person_id'],'G0003')


class KeyframeTests(unittest.TestCase):
    def test_distinct_time_and_quality(self):
        candidates=samples()+samples()
        selected,reason=select_keyframes(candidates)
        self.assertEqual(reason,'selected');self.assertEqual(len(selected),3)
        self.assertEqual([s['time_ms'] for s in selected],[0,500,1000])
    def test_inconsistent_track_rejected(self):
        candidates=[]
        for i in range(5):
            s=samples()[0];v=np.zeros(512);v[i]=1
            s.update(feature=v.tolist(),time_ms=i*500,crop_sha256=str(i));candidates.append(s)
        self.assertEqual(select_keyframes(candidates)[1],'inconsistent_track_appearance')
    def test_overlapping_people_rejected(self):
        rng=np.random.default_rng(3);frame=rng.integers(0,255,(300,200,3),dtype=np.uint8)
        person=SimpleNamespace(bbox=[20,20,100,250],track_id='one',score=.9,keypoints=[[0,0,.9]]*17)
        other=SimpleNamespace(bbox=person.bbox,track_id='two')
        self.assertIsNone(enrollment_candidate(frame,person,[person,other])[0])
        self.assertIsNotNone(enrollment_candidate(frame,person,[person])[0])


class SessionIdentityTests(unittest.TestCase):
    def resolve(self,ids):
        rows=[dict(frame_id=f'f{i}',time_ms=i*300,people=[dict(id='P001',
            profile_id=p,identity_feature_observed=True,identity_similarity=.8)]) for i,p in enumerate(ids)]
        session=dict(person_id='P001',start_ms=0,end_ms=5000)
        resolve_session_identities(rows,[session]);return session
    def test_requires_independent_support_and_coverage(self):
        self.assertEqual(event_person_id(self.resolve([None,None,'G0001','G0001','G0001']),{}),'G0001')
        self.assertIsNone(event_person_id(self.resolve([None,None,None,'G0001','G0001']),{}))
        self.assertIsNone(event_person_id(self.resolve([None]*5+['G0001']*3),{}))
    def test_conflicts_unknown_and_model_actor_mismatch(self):
        self.assertIsNone(event_person_id(self.resolve(['G0001']*3+['G0002']*3),{}))
        session=self.resolve(['G0001']*3)
        self.assertIsNone(event_person_id(session,{'person_id':'P002'}))
        self.assertEqual(session['person_id'],'P001')
        self.assertEqual(event_person_id({'person_id':'P001'},{}),'P001')
    def test_six_field_public_event_keeps_local_model_actor(self):
        from station_vision.shelf_session.pipeline import basic_and_details
        from station_vision.shelf_session.review import model_session
        session=self.resolve(['G0001']*3)
        session.update(id='s0001',shelf_level=3)
        events,_=basic_and_details([session],{'s0001':[]},{'s0001':{'status':'completed',
            'opinion':{'person_id':'P001','interaction_detected':True,'shelf_level':3}}})
        self.assertEqual(set(events[0]),{'id','person_id','start_ms','end_ms','action','shelf_level'})
        self.assertEqual(events[0]['person_id'],'G0001')
        prompt_data=model_session(session)
        self.assertEqual(prompt_data['person_id'],'P001');self.assertNotIn('identity',prompt_data)


if __name__=='__main__':unittest.main()
