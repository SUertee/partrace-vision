import hashlib
from pathlib import Path
import re
import tempfile
import unittest

from station_vision.service.artifacts import records


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.output=self.root/'output';self.work=self.root/'job/attempt_1'
        self.output.mkdir();self.work.mkdir(parents=True)
    def tearDown(self):self.temp.cleanup()

    def test_A_uses_selected_person_crop_with_source_time_and_stable_ids(self):
        crop=self.output/'crops/A-t1/f000001.png';crop.parent.mkdir(parents=True);crop.write_bytes(b'crop')
        raw=self.output/'evidence/f000001.png';raw.parent.mkdir();raw.write_bytes(b'raw')
        frame={'frame_id':'f000001','time_ms':7539.233,'crop_sha256':hashlib.sha256(b'crop').hexdigest()}
        payload={'direction_events':[{'id':'a0001','track_key':'A-t1','event_type':'enter_zone','start_ms':7000,'end_ms':8000}],
            'profiles':[{'track_id':'A-t1','keyframes':[frame]}]}
        job={'role':'A','recording_id':'recording_A'}
        events,evidence=records(job,self.output,self.work,payload)
        again,second=records(job,self.output,self.work,payload)
        self.assertEqual(events,again);self.assertEqual(evidence,second)
        self.assertRegex(events[0]['event_id'],r'^evt_\d{8}_[0-9a-f]{16}$')
        self.assertEqual(events[0]['recording_id'],'recording_A')
        self.assertEqual(evidence[0]['offset_ms'],7539)
        self.assertEqual(evidence[0]['storage_key'],str(crop))
        self.assertIsNone(evidence[0]['bbox_json'])

    def test_B_uses_annotated_image_and_also_keeps_raw_reference(self):
        image=self.output/'frame.jpg';image.write_bytes(b'annotated')
        raw=self.output/'frame.png';raw.write_bytes(b'raw')
        event={'id':'s0001','shelf_level':3,'start_ms':100,'end_ms':500}
        f={'frame_id':'f000004','actual_ms':300.4,'model_image_path':'frame.jpg','raw_path':'frame.png',
           'model_image_sha256':hashlib.sha256(b'annotated').hexdigest()}
        detail={'session':{'id':'s0001','person_id':'P001'},'review':{'status':'completed','opinion':{'reason':'可见交互'}},'keyframes':[f]}
        events,evidence=records({'role':'B','recording_id':'recording_B'},self.output,self.work,{'events':[event],'details':[detail]})
        self.assertEqual(evidence[0]['storage_key'],str(image));self.assertEqual(evidence[0]['raw_path'],str(raw))
        self.assertEqual(evidence[0]['image_kind'],'annotated_frame');self.assertEqual(evidence[0]['media_status'],'available')
        self.assertEqual(events[0]['track_key'],'P001')

    def test_changed_image_is_not_claimed_available(self):
        image=self.output/'frame.jpg';image.write_bytes(b'changed')
        f={'frame_id':'f1','actual_ms':300,'model_image_path':'frame.jpg','raw_path':'frame.png','model_image_sha256':'0'*64}
        detail={'session':{'id':'s1','person_id':'P1'},'review':{},'keyframes':[f]}
        with self.assertRaises(ValueError):records({'role':'B','recording_id':'r'},self.output,self.work,
            {'events':[{'id':'s1','start_ms':100,'end_ms':500,'shelf_level':None}],'details':[detail]})


if __name__=='__main__':unittest.main()
