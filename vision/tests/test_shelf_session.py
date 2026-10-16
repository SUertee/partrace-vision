import copy
import io
import unittest
from unittest.mock import patch
import urllib.error

from station_vision.shelf_session.detector import SessionMachine,SessionSettings,five_frames
from station_vision.shelf_session.review import validate,model_session,messages
from station_vision.shelf_session.evaluation import evaluate
from station_vision.multiframe_review.client import CompatibleClient
from station_vision.shelf_session.reach import enrich_sessions,operation_layer


def observation(t,x=30,y=60,people=('P001',),two_hands=False):
    persons=[]
    for pid in people:
        points=[[0,0,0] for _ in range(17)]
        if x is not None:
            points[9]=[x,y,1.]
            if two_hands:points[10]=[x+5,y,1.]
        persons.append({'id':pid,'box':[10,20,140,240],'keypoints':points})
    return {'frame_id':f'f{int(t/100):06d}','index':int(t/100),'time_ms':t,'width':720,
        'people':persons,'registration_valid':True,
        'shelf_polygons':[{'id':'z2','level':2,'polygon':[[0,0],[100,0],[100,100],[0,100]]},
                          {'id':'z3','level':3,'polygon':[[0,110],[100,110],[100,210],[0,210]]}]}


class SessionTests(unittest.TestCase):
    def machine(self):return SessionMachine(SessionSettings(enter_ms=200))

    def test_long_operation_is_one_event_with_two_hands(self):
        m=self.machine()
        for t in range(0,20000,100):m.update(observation(t,30+min(t/100,30),two_hands=True))
        events=m.finish(19900)
        self.assertEqual(len(events),1)
        self.assertEqual(events[0]['start_ms'],0)
        self.assertEqual(set(events[0]['hands']),{'left','right'})

    def test_brief_departure_and_return_remain_same_visit(self):
        m=self.machine()
        for t in range(0,1000,100):m.update(observation(t,30+t/100))
        for t in range(1000,1500,100):m.update(observation(t,None))
        for t in range(1500,2000,100):m.update(observation(t,50+t/200))
        self.assertEqual(len(m.finish(1900)),1)

    def test_brief_neighbour_layer_projection_does_not_split(self):
        m=self.machine()
        for t in range(0,1000,100):m.update(observation(t,30+t/100))
        for t in range(1000,1500,100):m.update(observation(t,30+(t-1000)/100,y=160))
        for t in range(1500,2000,100):m.update(observation(t,40+t/100))
        e=m.finish(1900)
        self.assertEqual(len(e),1);self.assertIsNone(e[0]['shelf_level'])

    def test_wrist_projection_switch_does_not_invent_layer_or_fragment_visit(self):
        m=self.machine()
        for t in range(0,1000,100):m.update(observation(t,30+t/100))
        for t in range(1000,2200,100):m.update(observation(t,30+(t-1000)/100,y=160))
        e=m.finish(2100)
        self.assertEqual(len(e),1)
        self.assertIsNone(e[0]['shelf_level'])
        self.assertEqual(set(e[0]['wrist_trigger_regions']),{'z2','z3'})

    def test_missing_person_preserves_last_observed_end_and_uncertainty(self):
        m=self.machine()
        for t in range(0,1000,100):m.update(observation(t,30+t/100))
        for t in range(1000,2000,100):m.update(observation(t,people=()))
        e=m.finish(1900)[0]
        self.assertEqual(e['end_ms'],900)
        self.assertEqual(e['close_reason'],'person_observation_lost')
        self.assertTrue(e['boundary_uncertain'])

    def test_two_people_are_not_merged(self):
        m=self.machine()
        for t in range(0,1000,100):m.update(observation(t,30+t/100,people=('P001','P002')))
        self.assertEqual({s['person_id'] for s in m.finish(900)},{'P001','P002'})

    def test_stationary_hand_is_not_a_reach(self):
        m=self.machine()
        for t in range(0,1000,100):m.update(observation(t,30))
        self.assertEqual(m.finish(900),[])

    def test_five_frames_use_source_times_and_offsets(self):
        rows=[observation(t) for t in range(0,6100,100)]
        f=five_frames(rows,{'start_ms':1000,'end_ms':5000})
        self.assertEqual([x['actual_ms'] for x in f],[700,2000,3000,4000,5300])
        self.assertEqual(len({x['frame_id'] for x in f}),5)
        self.assertTrue(all(x['offset_ms']==0 for x in f))

    def test_short_edge_clip_does_not_invent_distinct_frames(self):
        f=five_frames([observation(0),observation(100)],{'start_ms':0,'end_ms':50})
        self.assertTrue(f[0]['truncated']);self.assertTrue(f[-1]['truncated'])
        self.assertLess(len({x['frame_id'] for x in f}),5)
        self.assertEqual(f[0]['sampling_method'],'uniform_fallback_no_reliable_reach')

    def test_review_rejects_invented_frames_and_invalid_score(self):
        rows=[observation(t) for t in range(0,1000,100)]
        frames=five_frames(rows,{'start_ms':200,'end_ms':600})
        data={'person_id':'P001','interaction_detected':True,'pickup_detected':True,'pickup_score':.8,
            'shelf_level':2,'evidence_frame_ids':[frames[0]['frame_id'],frames[-1]['frame_id']],
            'reason':'before and after','shelf_reason':'manual layer','limitations':[]}
        s={'person_id':'P001','shelf_level':2}
        self.assertFalse(validate(data,s,frames,rows)['score_calibrated'])
        for change in ({'pickup_score':80},{'evidence_frame_ids':['fake']},{'person_id':'P999'},
                       {'pickup_detected':True,'evidence_frame_ids':[frames[0]['frame_id']]}):
            with self.assertRaises(ValueError):validate({**data,**change},s,frames,rows)

    def test_single_review_does_not_retry_billable_post(self):
        with patch('station_vision.multiframe_review.client.load_key',return_value='synthetic-key'):
            client=CompatibleClient('https://example.invalid/v1','test',max_attempts=1)
        error=urllib.error.HTTPError('https://example.invalid',429,'quota',{},io.BytesIO(b'quota'))
        with patch.object(client.opener,'open',side_effect=error) as call:
            with self.assertRaisesRegex(RuntimeError,'429'):client.complete([])
        self.assertEqual(call.call_count,1)

    def test_layer_error_is_not_hidden_by_time_matching_or_model_conflict(self):
        s={'id':'s1','person_id':'P001','shelf_level':2,'start_ms':0,'end_ms':1000}
        g={**s,'id':'g1','shelf_level':3,'pickup_times_ms':[500]}
        ref={'provenance':'test','sessions':[g]}
        result={'s1':{'opinion':{'pickup_detected':None,'shelf_level':None,'model_shelf_level':3}}}
        value=evaluate(ref,[s],result,[])
        self.assertEqual(value['matched_sessions'],1)
        self.assertFalse(value['boundary_matches'][0]['shelf_level_correct'])
        self.assertTrue(value['review_cases'][0]['model_shelf_level_correct'])
        self.assertFalse(value['review_cases'][0]['final_shelf_level_correct'])
        self.assertEqual(value['pickup_case_counts']['positive_unknown'],1)
        self.assertEqual(value['correct_fraction_including_abstentions'],0)

    def test_control_ground_truth_and_reason_are_not_exposed_to_model(self):
        source={'id':'negative_case','person_id':'P001','region_id':'z2','shelf_level':2,'start_ms':0,'end_ms':1000,
                'expected_pickup':False,'reason':'human reference answer','pickup_detected':False,'reference_id':'g1'}
        sanitized=model_session(source)
        self.assertEqual(set(sanitized),{'person_id','region_id','shelf_level','start_ms','end_ms'})
        self.assertNotIn('reason',sanitized)
        self.assertNotIn('expected_pickup',sanitized)

    def test_entire_five_image_payload_excludes_reference_answers(self):
        import json
        from pathlib import Path
        session={'id':'negative_case','person_id':'P001','region_id':'z2','shelf_level':2,'start_ms':200,'end_ms':600,
                 'expected_pickup':False,'reason':'secret reference judgement'}
        rows=[observation(t) for t in range(0,1000,100)]
        frames=five_frames(rows,session)
        for f in frames:f['model_image_path']='ignored.jpg'
        with patch('station_vision.shelf_session.review.image_part',return_value={'type':'image_url','image_url':{'url':'data:image/jpeg;base64,AA=='}}):
            payload=messages(session,frames,rows,Path('.'))
        text=json.dumps(payload)
        self.assertNotIn('expected_pickup',text)
        self.assertNotIn('secret reference judgement',text)
        self.assertNotIn('negative_case',text)
        self.assertEqual(sum(x.get('type')=='image_url' for x in payload[1]['content']),5)


class ReachAnchorTests(unittest.TestCase):
    def rows(self):
        rows=[]
        for t in range(0,2100,100):
            x=200-110*min(t,700)/700 if t<=700 else 90+110*min(t-700,700)/700
            row=observation(t,x,110)
            row['people'][0]['box']=[0,0,300,500]
            row['people'][0]['keypoints'][7]=[x+100,210,1.]
            row['shelf_polygons']=[{'id':'lower','level':2,'polygon':[[0,100],[100,100],[100,220],[0,220]]},
                                   {'id':'upper','level':3,'polygon':[[0,0],[100,0],[100,90],[0,90]]}]
            rows.append(row)
        return rows

    def session(self):return {'id':'s1','person_id':'P001','start_ms':0,'end_ms':2000}

    def test_wrist_lower_than_operation_does_not_assign_lower_layer(self):
        rows=self.rows();s=enrich_sessions(rows,[self.session()])[0]
        self.assertEqual(s['shelf_level'],3)
        self.assertEqual(s['region_id'],'upper')
        self.assertFalse(s['shelf_level_is_physical_contact_proof'])
        self.assertTrue(s['reach_anchor']['confirmed_approach_retreat'])
        self.assertGreaterEqual(s['layer_evidence']['supporting_source_frames'],3)

    def test_foreground_carrying_height_cannot_reassign_origin_layer(self):
        rows=self.rows()
        for row in rows[15:]:
            row['people'][0]['keypoints'][9][1]=200
            row['people'][0]['keypoints'][7][1]=200
        s=enrich_sessions(rows,[self.session()])[0]
        self.assertEqual(s['shelf_level'],3)

    def test_missing_elbow_abstains_instead_of_using_wrist_layer(self):
        rows=self.rows()
        for row in rows:row['people'][0]['keypoints'][7][2]=0
        s=enrich_sessions(rows,[self.session()])[0]
        self.assertIsNone(s['shelf_level'])
        self.assertIsNone(s['reach_anchor'])
        self.assertIn('fallback',five_frames(rows,s)[1]['role'])

    def test_hand_uncertainty_crossing_region_border_abstains(self):
        rows=self.rows();s=enrich_sessions(rows,[self.session()])[0]
        for row in rows:
            row['shelf_polygons'][1]['polygon']=[[0,0],[100,0],[100,65],[0,65]]
        value=operation_layer(rows,s,s['reach_anchor'])
        self.assertIsNone(value['shelf_level'])

    def test_adaptive_three_frames_cover_early_approach_peak_and_retreat(self):
        rows=self.rows();s=enrich_sessions(rows,[self.session()])[0]
        frames=five_frames(rows,s)
        self.assertEqual([f['role'] for f in frames],['before','reach_onset','reach_peak','withdrawal','after'])
        self.assertEqual(frames[2]['actual_ms'],700)
        self.assertGreater(frames[3]['actual_ms'],frames[2]['actual_ms'])
        self.assertLess(frames[3]['actual_ms'],1500)
        changed=copy.deepcopy(rows)
        for row in changed:row['parcels']=[{'id':'irrelevant'}]
        self.assertEqual(five_frames(rows,s),five_frames(changed,s))

    def test_observation_gap_cannot_become_a_continuous_reach(self):
        rows=self.rows()
        rows=[r for r in rows if r['time_ms'] not in (400,500,600)]
        s=enrich_sessions(rows,[self.session()])[0]
        self.assertIsNone(s['reach_anchor'])

    def test_other_persons_hand_cannot_assign_target_layer(self):
        rows=self.rows()
        for row in rows:
            other=copy.deepcopy(row['people'][0]);other['id']='P002'
            row['people'][0]['keypoints'][7][2]=0
            row['people'].append(other)
        s=enrich_sessions(rows,[self.session()])[0]
        self.assertIsNone(s['reach_anchor'])


if __name__=='__main__':unittest.main()
