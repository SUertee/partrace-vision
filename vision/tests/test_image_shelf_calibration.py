import base64
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import cv2
import numpy as np

from station_vision.shelf_calibration.image_reference import decode_reference,prepare_registration
from station_vision.shelf_calibration.manual import verify_scene_calibration
from station_vision.shelf_session.observer import observe


def reference(seed=42):
    # Static texture plus large shapes survives video compression and translation.
    rng=np.random.default_rng(seed)
    image=rng.integers(0,255,(480,640,3),dtype=np.uint8)
    cv2.rectangle(image,(80,80),(560,400),(60,200,90),4)
    ok,data=cv2.imencode('.png',image)
    assert ok
    raw=data.tobytes()
    return image,{'video':None,'camera_id':'B','shelf_numbering':'bottom_up','imgsz':640,
        'shelf_zones':[{'id':'manual_01','level':3,'polygon':[[100,100],[300,100],[300,220],[100,220]]}],
        'shelf_calibration':{'source':'manual_image','width':640,'height':480,'reference_frame_index':None,
            'reference_image':{'mime_type':'image/png','data_base64':base64.b64encode(raw).decode(),
                'sha256':hashlib.sha256(raw).hexdigest(),'source_kind':'image','source_time_ms':None}}}


class ImageShelfTests(unittest.TestCase):
    def test_portable_json_roundtrip_and_legacy_route_rejection(self):
        image,cfg=reference();cfg=json.loads(json.dumps(cfg))
        decoded,raw,digest=decode_reference(cfg)
        self.assertTrue(np.array_equal(image,decoded))
        verify_scene_calibration(cfg,Path('not_needed.mp4'),640,480,allow_image=True)
        with self.assertRaisesRegex(ValueError,'run_shelf_session_test'):
            verify_scene_calibration(cfg,Path('not_needed.mp4'),640,480)

    def test_hash_dimensions_and_outside_region_rejected(self):
        _,cfg=reference()
        invalid=[]
        bad=copy.deepcopy(cfg);bad['shelf_calibration']['reference_image']['sha256']='wrong';invalid.append(bad)
        bad=copy.deepcopy(cfg);bad['shelf_calibration']['width']=320;invalid.append(bad)
        bad=copy.deepcopy(cfg);bad['shelf_zones'][0]['polygon'][0][0]=-1;invalid.append(bad)
        bad=copy.deepcopy(cfg);bad['shelf_calibration']['reference_image']['data_base64']='%%%';invalid.append(bad)
        for bad in invalid:
            with self.assertRaises(ValueError):decode_reference(bad)

    def test_external_reference_maps_known_translation_not_first_frame_identity(self):
        image,cfg=reference();original=copy.deepcopy(cfg)
        reg,zones,info=prepare_registration(cfg,640,480)
        shifted=cv2.warpAffine(image,np.float32([[1,0,16],[0,1,9]]),(640,480))
        _,matrix,quality=reg.process(shifted)
        self.assertTrue(quality['valid'])
        pt=cv2.perspectiveTransform(np.float32([[[100,100]]]),matrix)[0,0]
        self.assertLess(np.linalg.norm(pt-[116,109]),2)
        self.assertEqual(cfg,original)
        self.assertEqual(info['reference_kind'],'external_image')

    def test_different_scene_does_not_receive_valid_regions(self):
        _,cfg=reference();other,_=reference(99)
        reg,zones,info=prepare_registration(cfg,640,480)
        self.assertFalse(reg.process(other)[2]['valid'])

    def test_uniform_resize_and_aspect_mismatch(self):
        image,cfg=reference()
        reg,zones,_=prepare_registration(cfg,1280,960)
        self.assertEqual(zones[0]['polygon'][0],[200,200])
        self.assertTrue(reg.process(cv2.resize(image,(1280,960)))[2]['valid'])
        with self.assertRaisesRegex(ValueError,'宽高比'):
            prepare_registration(cfg,1280,720)

    def test_observer_accepts_image_json_and_preserves_video_frame_coordinates(self):
        image,cfg=reference()
        root=Path(__file__).resolve().parents[1]
        (root/'.runtime').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root/'.runtime',prefix='shelf_image_') as folder:
            work=Path(folder);source=work/'B.avi';out=work/'out';out.mkdir()
            writer=cv2.VideoWriter(str(source),cv2.VideoWriter_fourcc(*'MJPG'),10,(640,480))
            self.assertTrue(writer.isOpened())
            shifted=cv2.warpAffine(image,np.float32([[1,0,16],[0,1,9]]),(640,480))
            for _ in range(3):writer.write(shifted)
            writer.release();cfg['video']=str(source)
            with patch('station_vision.shelf_session.observer.PoseTracker') as tracker:
                tracker.return_value.process.return_value=[]
                _,rows,metrics=observe(root,cfg,out,'cpu')
            self.assertEqual(len(rows),3)
            self.assertEqual(metrics['registration_valid_frames'],3)
            point=np.array(rows[0]['shelf_polygons'][0]['polygon'][0])
            self.assertLess(np.linalg.norm(point-[116,109]),3)
            self.assertEqual(rows[0]['frame_id'],'f000000')
            self.assertTrue((out/'shelf_reference.png').exists())
            self.assertEqual(metrics['calibration_reference']['reference_kind'],'external_image')


if __name__=='__main__':unittest.main()
