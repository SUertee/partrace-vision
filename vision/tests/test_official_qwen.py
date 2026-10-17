import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch

from station_vision.multiframe_review.client import CompatibleClient,credential_path,ANKER_BASE,ANKER_MODEL
from station_vision.runtime_parameters import defaults


class OfficialQwenTests(unittest.TestCase):
    def test_main_defaults_use_organizer_gateway_and_model_alias(self):
        settings=defaults()['vlm']
        self.assertEqual(settings['base_url'],ANKER_BASE)
        self.assertEqual(settings['model'],ANKER_MODEL)

    def test_old_and_new_credentials_have_separate_private_files(self):
        self.assertEqual(credential_path(ANKER_BASE).name,'anker-key.dpapi')
        self.assertEqual(credential_path('https://maas.qianwenaiapi.com/compatible-mode/v1').name,'qwen-key.dpapi')

    def test_official_request_disables_router_reasoning_and_keeps_key_out_of_body(self):
        with patch('station_vision.multiframe_review.client.load_key',return_value='synthetic-private-key'):
            client=CompatibleClient(ANKER_BASE,ANKER_MODEL,max_attempts=1)
        response={'model':'qwen3.8-max','choices':[{'finish_reason':'stop','message':{'content':'{}'}}]}
        captured=[]
        def send(request,timeout):
            captured.append(request)
            return io.BytesIO(json.dumps(response).encode())
        with patch.object(client.opener,'open',side_effect=send):client.complete([{'role':'user','content':'JSON'}])
        request=captured[0];body=json.loads(request.data)
        self.assertEqual(request.full_url,ANKER_BASE+'/chat/completions')
        self.assertEqual(body['reasoning_effort'],'none')
        self.assertIs(body['enable_thinking'],False)
        self.assertNotIn('synthetic-private-key',request.data.decode())


if __name__=='__main__':unittest.main()
