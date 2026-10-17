import copy
import json
from pathlib import Path
import tempfile
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request,urlopen
from http.server import ThreadingHTTPServer

from station_vision.shelf_calibration.manual import validate_zones,calibrated_config,verify_scene_calibration,CalibrationSession,handler_for


def zones():
    return [{'id':'manual_01','level':2,'polygon':[[10,20],[200,20],[200,150],[10,150]]}]


class ManualShelfTests(unittest.TestCase):
    def test_saves_levels_and_unknown_without_mutating_base(self):
        base={'video':'v.mp4','florence_klt':{'enabled':True}}
        original=copy.deepcopy(base)
        meta={'width':720,'height':1280,'reference_frame_index':0,'source_sha256':'x'}
        result=calibrated_config(base,meta,zones())
        self.assertEqual(base,original)
        self.assertEqual(result['shelf_zones'][0]['level'],2)
        self.assertEqual(result['shelf_calibration']['source'],'manual')
        unknown=zones();unknown[0]['level']=None
        self.assertIsNone(calibrated_config(base,meta,unknown)['shelf_zones'][0]['level'])

    def test_invalid_empty_crossed_and_outside_regions_are_rejected(self):
        invalid=[[],[{'id':'manual_01','level':1,'polygon':[[0,0],[200,200],[0,200],[200,0]]}]]
        bad=zones();bad[0]['polygon'][0][0]=-1;invalid.append(bad)
        bad=zones();bad[0]['level']=True;invalid.append(bad)
        for value in invalid:
            with self.assertRaises(ValueError):validate_zones(value,720,1280)

    def test_nonfirst_reference_cannot_silently_enter_first_frame_pipeline(self):
        meta={'width':720,'height':1280,'reference_frame_index':120,'source_sha256':'x'}
        with self.assertRaises(ValueError):calibrated_config({},meta,zones())

    def test_http_save_creates_new_configs_and_rejects_foreign_origin(self):
        # Save integration is isolated from real project annotations and source videos.
        root=Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory(dir=root/'.runtime',prefix='manual_shelf_test_') as folder:
            work=Path(folder)
            source=work/'video.bin';source.write_bytes(b'fixed test source')
            from station_vision.shelf_calibration.manual import digest
            session=object.__new__(CalibrationSession)
            session.root=work;session.output=work/'outputs';session.output.mkdir()
            session.base={'video':'video.bin','florence_klt':{'enabled':True}}
            session.metadata={'source_path':str(source),'source_sha256':digest(source),'width':720,'height':1280,
                              'reference_frame_index':0,'run_name':'test_save'}
            session.initial=[];session.lock=threading.Lock();session.save_count=0
            session.write_html=lambda:None
            server=ThreadingHTTPServer(('127.0.0.1',0),handler_for(session))
            thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            url=f'http://127.0.0.1:{server.server_port}'
            payload=json.dumps({'zones':zones()}).encode()
            try:
                request=Request(url+'/save',data=payload,headers={'Content-Type':'application/json','Origin':url})
                first=json.load(urlopen(request));second=json.load(urlopen(request))
                self.assertNotEqual(first['config_path'],second['config_path'])
                self.assertEqual(json.loads(Path(first['config_path']).read_text(encoding='utf8'))['shelf_zones'][0]['level'],2)
                self.assertTrue(Path(first['config_path']).is_relative_to(work/'configs/interaction'))
                saved=json.loads(Path(first['config_path']).read_text(encoding='utf8'))
                verify_scene_calibration(saved,source,720,1280)
                source.write_bytes(b'changed test source')
                with self.assertRaises(ValueError):verify_scene_calibration(saved,source,720,1280)
                wrong=Request(url+'/save',data=payload,headers={'Content-Type':'application/json','Origin':'https://example.com'})
                with self.assertRaises(HTTPError) as error:urlopen(wrong)
                self.assertEqual(error.exception.code,403)
            finally:
                server.shutdown();server.server_close();thread.join()


if __name__=='__main__':unittest.main()
