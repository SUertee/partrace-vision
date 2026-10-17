"""Minimal OpenAI-compatible transport. Never persists authorization or image payloads."""
import ctypes
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request


ANKER_BASE='https://ai-router-cn-pub.anker-in.com'
ANKER_MODEL='hackathon/v_model/qwen-max'


def credential_path(base_url=None):
    host=urllib.parse.urlparse(base_url or ANKER_BASE).hostname
    name='anker-key.dpapi' if host=='ai-router-cn-pub.anker-in.com' else 'qwen-key.dpapi'
    return Path(os.environ.get('LOCALAPPDATA',''))/'StationVision'/name


def load_key(base_url=None):
    value=os.environ.get('STATION_VLM_API_KEY')
    if value: return value
    if os.name!='nt': raise RuntimeError('Set STATION_VLM_API_KEY')
    path=credential_path(base_url)
    if not path.exists():
        raise RuntimeError('No private API credential configured. Run .\\setup_qwen_key.ps1 '
                           'in the same terminal, then retry with a new RunName.')
    class Blob(ctypes.Structure):
        _fields_=[('size',ctypes.c_ulong),('data',ctypes.POINTER(ctypes.c_ubyte))]
    data=bytes.fromhex(path.read_text(encoding='ascii').strip())
    buffer=ctypes.create_string_buffer(data)
    source=Blob(len(data),ctypes.cast(buffer,ctypes.POINTER(ctypes.c_ubyte)))
    target=Blob()
    if not ctypes.windll.crypt32.CryptUnprotectData(ctypes.byref(source),None,None,None,None,0,ctypes.byref(target)):
        raise RuntimeError('Cannot decrypt current-user API credential')
    try: return ctypes.string_at(target.data,target.size).decode('utf-16-le')
    finally: ctypes.windll.kernel32.LocalFree(target.data)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args): return None


class CompatibleClient:
    def __init__(self,base_url,model,timeout=180,max_attempts=3):
        parsed=urllib.parse.urlparse(base_url)
        if parsed.scheme!='https' or parsed.username or parsed.password or parsed.query:
            raise ValueError('An HTTPS API base without embedded credentials is required')
        self.base=base_url.rstrip('/')
        self.model=model
        self.key=load_key(base_url)
        self.timeout=timeout
        if max_attempts<1: raise ValueError('max_attempts must be positive')
        self.max_attempts=max_attempts
        self.opener=urllib.request.build_opener(NoRedirect)

    def complete(self,messages,max_tokens=3500,structured=True):
        body={'model':self.model,'messages':messages,'temperature':0,'max_tokens':max_tokens,
              'stream':False,'enable_thinking':False}
        if urllib.parse.urlparse(self.base).hostname=='ai-router-cn-pub.anker-in.com':
            body['reasoning_effort']='none'
        if structured:body['response_format']={'type':'json_object'}
        started=time.perf_counter()
        for attempt in range(self.max_attempts):
            req=urllib.request.Request(self.base+'/chat/completions',
                data=json.dumps(body,ensure_ascii=False).encode('utf-8'),
                headers={'Authorization':'Bearer '+self.key,'Content-Type':'application/json'},method='POST')
            try:
                with self.opener.open(req,timeout=self.timeout) as response:
                    result=json.load(response)
                # Credentials should never be echoed by a server, but redact defensively.
                result=json.loads(json.dumps(result,ensure_ascii=False).replace(self.key,'[REDACTED]'))
                return result,round(time.perf_counter()-started,3)
            except urllib.error.HTTPError as exc:
                detail=exc.read(3000).decode('utf-8','replace').replace(self.key,'[REDACTED]')
                if exc.code in (429,502,503,504) and attempt<self.max_attempts-1:
                    time.sleep(2*(attempt+1));continue
                raise RuntimeError(f'API HTTP {exc.code}: {detail}') from None
            except (urllib.error.URLError,TimeoutError) as exc:
                # Do not automatically repeat an uncertain, possibly billable POST.
                raise RuntimeError('API transport failure: '+type(exc).__name__) from None


def parse_response(response):
    choices=response.get('choices',[])
    if not choices: raise ValueError('API returned no choices')
    choice=choices[0]
    if choice.get('finish_reason') not in ('stop',None):
        raise ValueError('Incomplete model response: '+str(choice.get('finish_reason')))
    text=choice.get('message',{}).get('content')
    if not isinstance(text,str): raise ValueError('No textual JSON response')
    text=text.strip()
    if text.startswith('```'):
        lines=text.splitlines()
        if lines[-1].strip()!='```': raise ValueError('Unclosed JSON block')
        text='\n'.join(lines[1:-1])
    return json.loads(text)
