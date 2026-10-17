import copy
import unittest

from station_vision.interaction.candidate import CandidateMachine
from station_vision.interaction.review import validate, normalize, basic_event
from station_vision.interaction.pipeline import evidence_plan, aggregate
from station_vision.multiframe_review.client import parse_response


def relation(person='P001', target='box', hand='right', distance=.1):
    return {'person_id':person,'target_id':target,'target_type':'parcel','hand':hand,'distance':distance}


class CandidateTests(unittest.TestCase):
    def test_two_people_separate_candidates(self):
        m=CandidateMachine()
        for t in (0,100,200):
            m.update(t,[relation(),relation('P002','box2')])
        c=m.finish(1000)
        self.assertEqual({(x['person_id'],x['target_id']) for x in c},{('P001','box'),('P002','box2')})

    def test_two_hands_one_candidate(self):
        m=CandidateMachine()
        for t in (0,100,200):
            m.update(t,[relation(),relation(hand='left')])
        self.assertEqual(len(m.finish(1000)),1)

    def test_single_frame_and_gaps_do_not_trigger(self):
        m=CandidateMachine()
        for t in (0,500,1000):
            m.update(t,[relation()])
        self.assertEqual(m.finish(1000),[])

    def test_lost_object_preserves_triggered_candidate(self):
        m=CandidateMachine()
        for t in (0,100,200):
            m.update(t,[relation()])
        m.update(800,[])
        c=m.finish(1000)
        self.assertEqual(len(c),1)
        self.assertEqual(c[0]['close_reason'],'observation_lost')
        self.assertEqual(c[0]['active_end_ms'],200)

    def test_no_proximity_no_candidate(self):
        m=CandidateMachine()
        for t in (0,100,200,300):
            m.update(t,[relation(distance=.8)])
        self.assertEqual(m.finish(1000),[])

    def test_dense_evidence_covers_tail_with_bounded_requests(self):
        rows=[{'frame_id':f'f{i:06d}','time_ms':i*1000/30} for i in range(301)]
        c={'id':'c1','active_start_ms':1500,'active_end_ms':7500,'clip_start_ms':0,'clip_end_ms':9000}
        requests=evidence_plan(rows,[c])
        self.assertEqual(len(requests),3)
        self.assertTrue(all(len(r['indices'])+3<=32 for r in requests))
        self.assertEqual(requests[-1]['core_end_ms'],7500)
        active_times=[rows[i]['time_ms'] for i in requests[0]['indices'] if 1500<=rows[i]['time_ms']<=3500]
        self.assertLessEqual(max(b-a for a,b in zip(active_times,active_times[1:])),151)
        self.assertEqual(evidence_plan(rows,[]),[])


class ReviewTests(unittest.TestCase):
    def setUp(self):
        self.frames=[{'frame_id':'f0','time_ms':0,'people':[{'id':'P001'}]},
                     {'frame_id':'f1','time_ms':1000,'people':[{'id':'P002'}]}]
        self.data={'verdict':'not_comparable','observations':['visible'],
                   'segments':[{'person_id':'P001','start_ms':0,'end_ms':1000,'action':'take',
                   'shelf_level':2,'shelf_reason':'最低储物板上方一层','evidence_frame_ids':['f0','f1'],'reason':'held'}]}

    def test_truncated_json_is_not_accepted(self):
        with self.assertRaises(ValueError):
            parse_response({'choices':[{'finish_reason':'length','message':{'content':'{}'}}]})

    def test_small_public_contract_and_shelf(self):
        d=validate(self.data,self.frames,{'hypothesis':'near_only'})
        event=basic_event('e1',d['segments'][0])
        self.assertEqual(set(event),{'id','person_id','start_ms','end_ms','action','shelf_level'})
        self.assertEqual(event['shelf_level'],2)

    def test_fabricated_actor_frame_time_rejected(self):
        for key,value in [('person_id','P999'),('evidence_frame_ids',['f0','fake']),('end_ms',2000),('shelf_level',True)]:
            d=copy.deepcopy(self.data)
            d['segments'][0][key]=value
            with self.assertRaises(ValueError):
                validate(d,self.frames,{'hypothesis':'near_only'})

    def test_unknown_actor_and_shelf_allowed(self):
        self.data['segments'][0].update(person_id=None,shelf_level=None)
        validate(self.data,self.frames,{'hypothesis':'near_only'})

    def test_proximity_comparison_is_owned_by_application(self):
        self.data['verdict']='supported'
        normalized=normalize(self.data,self.frames,{'hypothesis':'near_only'})
        self.assertEqual(self.data['verdict'],'supported')
        self.assertEqual(normalized['verdict'],'not_comparable')
        self.assertEqual(normalized['model_original_verdict'],'supported')
        validate(normalized,self.frames,{'hypothesis':'near_only'})

    def test_only_sub_millisecond_endpoint_rounding_normalized(self):
        self.data['segments'][0]['start_ms']=-.667
        normalized=normalize(self.data,self.frames,{'hypothesis':'near_only'})
        validate(normalized,self.frames,{'hypothesis':'near_only'})
        self.assertEqual(normalized['segments'][0]['start_ms'],0)
        self.data['segments'][0]['start_ms']=-100
        with self.assertRaises(ValueError):
            validate(normalize(self.data,self.frames,{'hypothesis':'near_only'}),self.frames,{'hypothesis':'near_only'})

    def test_one_frame_does_not_prove_take(self):
        self.data['segments'][0]['evidence_frame_ids']=['f0','f0']
        with self.assertRaises(ValueError):
            validate(self.data,self.frames,{'hypothesis':'near_only'})

    def test_actor_must_appear_in_cited_evidence(self):
        self.data['segments'][0].update(action='touch',evidence_frame_ids=['f1'])
        with self.assertRaises(ValueError):
            validate(self.data,self.frames,{'hypothesis':'near_only'})

    def test_actor_disagreement_is_displayed_unknown(self):
        segment=copy.deepcopy(self.data['segments'][0]);segment['person_id']='P002'
        d={'candidate_id':'c1','request_id':'r1','trigger_person_id':'P001','segment':segment,
           'warnings':['actor_disagrees_with_trigger']}
        events,details=aggregate([d])
        self.assertIsNone(events[0]['person_id'])
        self.assertEqual(details[0]['model_person_id'],'P002')

    def test_shelf_disagreement_becomes_unknown_without_losing_model_opinion(self):
        a=copy.deepcopy(self.data['segments'][0]);b=copy.deepcopy(a)
        b['shelf_level']=3
        data=[{'candidate_id':f'c{i}','request_id':f'r{i}','segment':s,'warnings':[]}
              for i,s in enumerate([a,b])]
        events,details=aggregate(data)
        self.assertTrue(all(e['shelf_level'] is None for e in events))
        self.assertEqual({d['model_shelf_level'] for d in details},{2,3})


if __name__=='__main__':
    unittest.main()
