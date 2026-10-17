import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from station_vision.interaction import pipeline
from station_vision.interaction.cache import load_observations
from station_vision.multiframe_review.client import load_key


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.config={'video':'source.mp4','shelf_numbering':'bottom_up'}
        (self.root/'scene.json').write_text(json.dumps(self.config),encoding='utf-8')
        (self.root/'source.mp4').write_bytes(b'unchanged source bytes')
        self.fake_entry=self.root/'station_vision/interaction/pipeline.py'

    def arguments(self,*extra):
        return ['pipeline','--config','scene.json','--run-name','trial',*extra]

    def observe(self,root,config,output,device):
        rows=[{'frame_id':f'f{i:06d}','index':i,'time_ms':i*100.,
               'timestamp_basis':'decoder_media_pts','people':[]} for i in range(3)]
        (output/'observations.jsonl').write_text('\n'.join(json.dumps(row) for row in rows)+'\n',encoding='utf-8')
        pipeline.save_json(output/'people.json',{})
        pipeline.save_json(output/'candidates.json',[])
        info={'width':720,'height':1280,'fps':10.,'duration_ms':300.,
              'timestamp_basis_counts':{'decoder_media_pts':3}}
        return root/'source.mp4',rows,[],info

    def failed_export(self):
        with patch.object(pipeline,'__file__',str(self.fake_entry)), \
             patch.object(sys,'argv',self.arguments()), \
             patch.object(pipeline,'observe',side_effect=self.observe), \
             patch.object(pipeline,'extract',side_effect=RuntimeError('simulated evidence failure')):
            with self.assertRaisesRegex(RuntimeError,'simulated evidence failure'):
                pipeline.main()
        return self.root/'outputs/trial'

    def test_missing_credential_fails_before_observing_or_creating_output(self):
        with patch.object(pipeline,'__file__',str(self.fake_entry)), \
             patch.object(sys,'argv',self.arguments('--review')), \
             patch.object(pipeline,'CompatibleClient',side_effect=RuntimeError('credential unavailable')), \
             patch.object(pipeline,'observe') as observer:
            with self.assertRaisesRegex(RuntimeError,'credential unavailable'):
                pipeline.main()
        observer.assert_not_called()
        self.assertFalse((self.root/'outputs/trial').exists())

    def test_failed_evidence_stage_keeps_completed_observations_reusable(self):
        prior=self.failed_export()
        summary=json.loads((prior/'summary.json').read_text(encoding='utf-8'))
        self.assertEqual(summary['status'],'failed')
        self.assertTrue(summary['observations_completed'])
        source,rows,candidates,info=load_observations(self.root,prior,self.config)
        self.assertEqual(len(rows),3)
        self.assertEqual(info['fps'],10)
        self.assertEqual(source,self.root/'source.mp4')

    def test_changed_source_or_config_cannot_reuse_checkpoint(self):
        prior=self.failed_export()
        with self.assertRaisesRegex(ValueError,'identical config'):
            load_observations(self.root,prior,{**self.config,'imgsz':640})
        (self.root/'source.mp4').write_bytes(b'different video')
        with self.assertRaisesRegex(ValueError,'source hash'):
            load_observations(self.root,prior,self.config)

    def test_truncated_cache_cannot_reuse_checkpoint(self):
        prior=self.failed_export()
        (prior/'observations.jsonl').write_text('{}\n',encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'cache file changed'):
            load_observations(self.root,prior,self.config)

    def test_existing_run_is_preserved_before_loading_models(self):
        prior=self.root/'outputs/trial'
        prior.mkdir(parents=True)
        sentinel=prior/'keep.txt'
        sentinel.write_text('preserve',encoding='utf-8')
        with patch.object(pipeline,'__file__',str(self.fake_entry)), \
             patch.object(sys,'argv',self.arguments('--review')), \
             patch.object(pipeline,'CompatibleClient') as client, \
             patch.object(pipeline,'observe') as observer:
            with self.assertRaisesRegex(FileExistsError,'new RunName'):
                pipeline.main()
        client.assert_not_called()
        observer.assert_not_called()
        self.assertEqual(sentinel.read_text(encoding='utf-8'),'preserve')

    @unittest.skipUnless(os.name=='nt','Windows current-user DPAPI')
    def test_hidden_setup_and_python_decryption_are_compatible(self):
        root=Path(pipeline.__file__).resolve().parents[2]
        env={key:value for key,value in os.environ.items() if key.upper()!='PSMODULEPATH'}
        env.update(LOCALAPPDATA=str(self.root),TASK_TEST_KEY='synthetic-test-value')
        env.pop('STATION_VLM_API_KEY',None)
        # A PowerShell 7 parent can export an incompatible module search path
        # to Windows PowerShell 5. Let the child build its own standard path.
        command="function Read-Host { ConvertTo-SecureString $env:TASK_TEST_KEY -AsPlainText -Force }; & './setup_qwen_key.ps1'"
        for executable in ('powershell','pwsh'):
            path=shutil.which(executable)
            if not path:
                continue
            with self.subTest(shell=executable):
                result=subprocess.run([path,'-NoProfile','-Command',command],cwd=root,
                                      env=env,capture_output=True,text=True,timeout=30)
                self.assertEqual(result.returncode,0,result.stdout+result.stderr)
                self.assertNotIn(env['TASK_TEST_KEY'],result.stdout+result.stderr)
        with patch.dict(os.environ,env,clear=True):
            self.assertEqual(load_key(),env['TASK_TEST_KEY'])
        store=self.root/'StationVision/anker-key.dpapi'
        self.assertNotIn(env['TASK_TEST_KEY'],store.read_text(encoding='ascii'))


if __name__=='__main__':
    unittest.main()
