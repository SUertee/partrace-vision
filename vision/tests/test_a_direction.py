import unittest

from station_vision.reid.direction import movement_events
from station_vision.runtime_parameters import defaults


def observations(xs, times=None, track='A-t1', profile='G0001'):
    return [{'source_frame_index': i, 'time_ms': times[i] if times else i*100,
             'people': [{'track_id': track, 'profile_id': profile,
                         'box': [x*1000-20, 10, x*1000+20, 150]}]} for i,x in enumerate(xs)]


class DirectionTests(unittest.TestCase):
    def settings(self):
        return {**defaults()['a_direction'], 'smooth_window_ms': 0}

    def test_left_to_right_true_is_entry_without_crossing_middle(self):
        events,_=movement_events(observations([.1,.2,.3,.4]),1000,self.settings(),True)
        self.assertEqual([e['event_type'] for e in events],['enter_zone'])
        self.assertEqual(events[0]['end_x_ratio'],.4)

    def test_right_to_left_true_is_exit(self):
        events,_=movement_events(observations([.9,.8,.7,.6]),1000,self.settings(),True)
        self.assertEqual([e['event_type'] for e in events],['leave_zone'])

    def test_false_reverses_meaning_not_observed_direction(self):
        rows=observations([.1,.2,.3,.4])
        events,_=movement_events(rows,1000,self.settings(),False)
        self.assertEqual(events[0]['event_type'],'leave_zone')
        self.assertEqual(events[0]['direction'],'left_to_right')

    def test_turning_back_can_produce_entry_then_exit(self):
        events,_=movement_events(observations([.2,.3,.4,.5,.6,.5,.4,.3,.2]),1000,self.settings(),True)
        self.assertEqual([e['event_type'] for e in events],['enter_zone','leave_zone'])

    def test_vertical_movement_does_not_imply_entry(self):
        rows=observations([.4]*8)
        for i,row in enumerate(rows):
            row['people'][0]['box'][1]+=i*30;row['people'][0]['box'][3]+=i*30
        self.assertEqual(movement_events(rows,1000,self.settings(),True)[0],[])

    def test_jitter_and_single_frame_spike_do_not_imply_entry(self):
        self.assertEqual(movement_events(observations([.4,.41,.39,.4,.42]),1000,self.settings(),True)[0],[])
        self.assertEqual(movement_events(observations([.9,.2,.2,.2,.2]),1000,defaults()['a_direction'],True)[0],[])

    def test_no_bridge_across_tracking_gap(self):
        rows=observations([.2,.2,.2,.8,.8,.8],[0,100,200,2000,2100,2200])
        self.assertEqual(movement_events(rows,1000,self.settings(),True)[0],[])

    def test_separate_people_keep_separate_directions(self):
        rows=observations([.2,.3,.4,.5])
        other=observations([.8,.7,.6,.5],track='A-t2',profile='G0002')
        for a,b in zip(rows,other):a['people']+=b['people']
        events,_=movement_events(rows,1000,self.settings(),True)
        self.assertEqual({(e['person_id'],e['event_type']) for e in events},{('G0001','enter_zone'),('G0002','leave_zone')})

    def test_identity_conflict_is_unknown(self):
        rows=observations([.2,.3,.4,.5]);rows[-1]['people'][0]['profile_id']='G0002'
        self.assertIsNone(movement_events(rows,1000,self.settings(),True)[0][0]['person_id'])

    def test_flag_must_be_boolean(self):
        with self.assertRaises(ValueError):movement_events([],1000,self.settings(),'false')


if __name__=='__main__':unittest.main()
