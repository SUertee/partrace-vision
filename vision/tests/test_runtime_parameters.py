from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from station_vision.runtime_parameters import defaults,documented_config,validate_parameters,SPECS
from station_vision.reid.profile_store import ProfileStore,read_archive,digest,resolve_crop_path
from station_vision.reid.keyframes import select_keyframes
from station_vision.reid.session_identity import resolve_session_identities
from station_vision.shelf_session.detector import five_frames
from station_vision.shelf_session.reach import palm_cues
from station_vision.shelf_session.reach import enrich_sessions
from test_auto_enrollment import samples


class RuntimeParametersTests(unittest.TestCase):
    def test_documented_values_match_defaults_and_all_numeric_keys_have_help(self):
        config=documented_config();self.assertEqual(validate_parameters(config),defaults())
        for group,settings in SPECS.items():
            for key in settings:
                self.assertTrue(all(config['_help'][group][key].get(k) is not None
                    for k in ('range','increase','decrease','meaning','unit')))
    def test_reject_invalid_and_typo_before_running_models(self):
        for group,key,value in [('a_keyframes','maximum',6),('b_matching','threshold',float('nan')),
            ('interaction','enter_ms',-1),('reach','minimum_samples',True),('evidence','befor_ms',100)]:
            cfg=defaults();cfg[group][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):validate_parameters(cfg)
    def test_inconsistent_pairs_rejected(self):
        for group,key,value in [('a_video','feature_fps',30),('a_tracker','imgsz',641),
            ('a_tracker','track_low_thresh',.8),('hand_geometry','extension_ratio',.9),
            ('b_matching','retain_threshold',.9)]:
            cfg=defaults();cfg[group][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):validate_parameters(cfg)
    def test_config_changes_keyframe_count(self):
        cfg=defaults()['a_keyframes'];rows=[]
        for i in range(10):
            s=samples()[0];s.update(time_ms=i*500,crop_sha256=str(i));s['quality']['selection_quality']=.95-i*.01
            rows.append(s)
        self.assertEqual(len(select_keyframes(rows,settings=cfg)[0]),5)
        cfg['maximum']=3
        self.assertEqual(len(select_keyframes(rows,settings=cfg)[0]),3)
    def test_evidence_context_is_used(self):
        rows=[dict(time_ms=i*100,frame_id=f'f{i}') for i in range(21)]
        s=dict(start_ms=500,end_ms=1500,algorithm_parameters={'evidence':{'before_ms':100,'after_ms':500}})
        frames=five_frames(rows,s)
        self.assertEqual(frames[0]['actual_ms'],400);self.assertEqual(frames[-1]['actual_ms'],2000)
    def test_hand_extension_used_in_algorithm_and_visualization(self):
        points=[[0,0,1.]]*17;points[7]=[0,100,1.];points[9]=[100,100,1.]
        points[8]=[0,0,0];points[10]=[0,0,0]
        person={'box':[0,0,200,500],'keypoints':points}
        row={'width':720,'hand_geometry':defaults()['hand_geometry']}
        a=palm_cues(row,person)[0]['palm_estimate'][0]
        row['hand_geometry']['extension_ratio']=.5
        b=palm_cues(row,person)[0]['palm_estimate'][0]
        self.assertEqual(a,140);self.assertEqual(b,150)
    def test_session_coverage_tuning_changes_public_eligibility(self):
        rows=[dict(frame_id=f'f{i}',time_ms=i*100,people=[dict(id='P001',identity_feature_observed=True,
            profile_id='G0001' if i>3 else None)]) for i in range(9)]
        s={'person_id':'P001','start_ms':0,'end_ms':800}
        resolve_session_identities(rows,[s]);self.assertIsNone(s['identity']['profile_id'])
        cfg=defaults()['b_session'];cfg['minimum_coverage']=.5
        resolve_session_identities(rows,[s],cfg);self.assertEqual(s['identity']['profile_id'],'G0001')
    def test_interaction_enter_time_changes_detection(self):
        from test_shelf_session import observation
        from station_vision.shelf_session.detector import SessionSettings,detect_sessions
        rows=[observation(t,30+t/100) for t in range(0,1000,100)]
        cfg=defaults()
        self.assertEqual(len(detect_sessions(rows,SessionSettings(**cfg['interaction']),cfg)),1)
        cfg['interaction']['enter_ms']=1500
        self.assertEqual(detect_sessions(rows,SessionSettings(**cfg['interaction']),cfg),[])
    def test_reach_and_layer_parameters_change_abstention(self):
        from test_shelf_session import ReachAnchorTests
        fixture=ReachAnchorTests();cfg=defaults()
        self.assertEqual(enrich_sessions(fixture.rows(),[fixture.session()],cfg)[0]['shelf_level'],3)
        cfg['layer_vote']['minimum_support']=20
        self.assertIsNone(enrich_sessions(fixture.rows(),[fixture.session()],cfg)[0]['shelf_level'])
        cfg=defaults();cfg['reach']['minimum_approach_px_at_720']=200
        self.assertIsNone(enrich_sessions(fixture.rows(),[fixture.session()],cfg)[0]['reach_anchor'])
    def test_registration_parameters_are_used(self):
        from station_vision.registration import FrameRegistration
        cfg=defaults()['registration'];cfg.update(orb_features=2500,match_ratio=.6)
        registration=FrameRegistration(enabled=True,**cfg)
        self.assertEqual(registration.orb.getMaxFeatures(),2500)
        self.assertEqual(registration.match_ratio,.6)
    def test_vlm_output_limit_is_forwarded_without_network(self):
        from station_vision.shelf_session.pipeline import review_once
        class FakeClient:
            def complete(self,messages,max_tokens):self.limit=max_tokens;return {'model':'fake'},.01
        client=FakeClient();cfg=defaults()['vlm'];cfg['max_tokens']=1234
        frames=[{'frame_id':f'f{i}'} for i in range(5)]
        messages=[{}, {'content':[{'type':'image_url'}]*5}]
        with tempfile.TemporaryDirectory() as temp,patch('station_vision.shelf_session.pipeline.messages',return_value=messages), \
            patch('station_vision.shelf_session.pipeline.parse_response',return_value={}), \
            patch('station_vision.shelf_session.pipeline.validate',return_value={}):
            result=review_once(client,{'id':'s1','person_id':'P001'},frames,[],Path(temp),settings=cfg)
        self.assertEqual(client.limit,1234);self.assertEqual(result['status'],'completed')


class LayoutMigrationTests(unittest.TestCase):
    def test_flatten_preserves_ids_pixels_and_historical_snapshot_resolution(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);old=ProfileStore(root,'w',folder='data/person_enrollment/auto_archive')
            a=old.register({'sha256':'a'*64,'path':'a.mp4'},dict(track_id='A-t1',start_ms=0,end_ms=2000),samples(),'old')
            pictures={Path(f['archive_crop']).name:digest(f['archive_crop']) for f in a['keyframes']}
            new=ProfileStore(root,'w');state=read_archive(new.path)
            self.assertEqual(set(state['profiles']),{'G0001'})
            self.assertEqual(new.folder,root/'data/person_enrollment')
            self.assertFalse((new.folder/'auto_archive').exists())
            for frame in a['keyframes']:
                moved=resolve_crop_path(frame['archive_crop'])
                self.assertEqual(digest(moved),pictures[moved.name])
            self.assertTrue((new.folder/'G0001/profile.json').is_file())
    def test_migration_collision_never_overwrites(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);old=ProfileStore(root,'w',folder='data/person_enrollment/auto_archive')
            old.register({'sha256':'a'*64,'path':'a.mp4'},dict(track_id='A-t1',start_ms=0,end_ms=2000),samples(),'old')
            destination=root/'data/person_enrollment/G0001';destination.mkdir();(destination/'user.txt').write_text('keep')
            with self.assertRaises(FileExistsError):ProfileStore(root,'w')
            self.assertEqual((destination/'user.txt').read_text(),'keep')
            self.assertTrue(old.path.is_file())


if __name__=='__main__':unittest.main()
