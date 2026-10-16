#!/usr/bin/env python3
"""Deploy an already-built People API alongside the existing Aurora API.

Run as root on EC2, after verifying the release image in isolation. No traffic is
switched here. The existing API, grants and data are retained. Credentials never
appear in command arguments or output. The grants input contains digests only.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def docker(*args, input=None):
    return subprocess.check_output(['docker', *args], input=input)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-root', type=Path, required=True)
    parser.add_argument('--grants-file', type=Path, required=True)
    parser.add_argument('--image', required=True)
    parser.add_argument('--source-container', default='partrace-vision-api-1')
    parser.add_argument('--container-name', default='partrace-people-api-20261017')
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise SystemExit('Run as root on the authorized EC2 host.')
    old = json.loads(docker('inspect', args.source_container))[0]
    if subprocess.run(['docker', 'inspect', args.container_name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
        raise SystemExit('Candidate container already exists; inspect it before continuing.')
    root = args.release_root
    backup = root/'private-backup'
    backup.mkdir(mode=0o700)
    state = Path(next(m['Source'] for m in old['Mounts'] if m['Destination'] == '/state'))
    for name in ['auth.json', 'config-pins.json']:
        shutil.copy2(state/name, backup/name)
        (backup/name).chmod(0o600)
    environment = dict(v.split('=', 1) for v in old['Config']['Env'] if '=' in v)
    environment['PARTRACE_SCHEMA_PATH'] = '/app/people-schema/schema.sql'
    environment['PARTRACE_AGENT_ENABLED'] = 'false'
    environment['PYTHONDONTWRITEBYTECODE'] = '1'
    # People processing uses local Vision; do not forward model credentials.
    environment = {k:v for k,v in environment.items() if not k.startswith(('PARTRACE_AGENT_API_', 'PARTRACE_EMBEDDING_API_'))}
    uid, gid = map(int, old['Config']['User'].split(':'))
    with tempfile.NamedTemporaryFile(mode='w', dir=backup, prefix='env-', delete=False) as f:
        env_path = Path(f.name)
        os.chmod(env_path, 0o600)
        for k,v in environment.items():
            if '\n' in v or '\r' in v:
                raise SystemExit('Environment contains unsupported newline; nothing launched.')
            f.write(k+'='+v+'\n')
    common = ['--env-file', str(env_path), '--user', old['Config']['User'],
              '--network', old['HostConfig']['NetworkMode'], '-v', str(state)+':/state',
              '-v', str(root/'docs/api/openapi.json')+':/app/api/openapi.json:ro']
    try:
        inspect_db = b'''import json
from backend.core.settings import Settings
from backend.storage.credentials import connection_class
s=Settings()
with connection_class(s).connect(s.database_url.get_secret_value(),connect_timeout=5) as c:
 c.execute('SET TRANSACTION READ ONLY')
 tables=c.execute("select tablename from pg_tables where schemaname=%s order by tablename",(s.database_schema,)).fetchall()
 result={}
 for (t,) in tables:
  if not t.replace('_','').isalnum(): raise RuntimeError('invalid table')
  result[t]=[r[0] for r in c.execute('select to_jsonb(x) from '+s.database_schema+'.'+t+' x order by to_jsonb(x)::text').fetchall()]
 print(json.dumps(result,sort_keys=True,default=str))
'''
        before = docker('run','--rm','-i',*common,args.image,'python','-',input=inspect_db)
        json.loads(before)
        (backup/'database-before.json').write_bytes(before)
        (backup/'database-before.json').chmod(0o600)
        for command in ['migrate-recording-jobs','migrate-source-recordings']:
            output = docker('run','--rm',*common,args.image,'python','-m','backend.manage',command)
            print(output.decode().strip())
        after = json.loads(docker('run','--rm','-i',*common,args.image,'python','-',input=inspect_db))
        original = json.loads(before)
        for table, rows in original.items():
            actual = after[table]
            if table == 'recordings':
                actual = [{k:v for k,v in row.items() if k != 'publication_status'} for row in actual]
                actual.sort(key=lambda r:json.dumps(r,sort_keys=True))
                rows.sort(key=lambda r:json.dumps(r,sort_keys=True))
            if actual != rows:
                raise SystemExit('Existing rows changed during verification; inspect before starting candidate.')
        payload = json.loads(args.grants_file.read_text())
        registry = json.loads((state/'auth.json').read_text())
        config = payload['config']
        version = config['config_version']
        if not version.replace('_','').isalnum():
            raise SystemExit('Unsupported config name.')
        config_path = state/'configs'/(version+'.json')
        if config_path.exists():
            raise SystemExit('Config version already exists; no existing version overwritten.')
        pins = json.loads((state/'config-pins.json').read_text())
        if version in pins:
            raise SystemExit('Config pin already exists; no existing pin overwritten.')
        for grant in payload['tokens']:
            if any(g['token_sha256'] == grant['token_sha256'] for g in registry['tokens']):
                raise SystemExit('Grant already exists; inspect before retrying.')
            output = docker('run','--rm',*common,args.image,'python','-m','backend.manage','seed-user',
                            '--user-id',grant['user_id'],'--display-name',grant['user_id'],'--role','admin')
            print('Seeded scoped identity:',grant['user_id'])
        config_bytes = json.dumps(config,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()
        pins[version] = hashlib.sha256(config_bytes).hexdigest()
        registry['tokens'].extend(payload['tokens'])
        for path, value in [(config_path,config),(state/'config-pins.json',pins),(state/'auth.json',registry)]:
            temp = path.with_name(path.name+'.people-tmp')
            with open(temp,'x') as out:json.dump(value,out,ensure_ascii=False)
            os.chmod(temp,0o600);os.chown(temp,uid,gid);os.replace(temp,path)
        name = docker('run','-d','--name',args.container_name,'--restart','unless-stopped','--init',*common,
                      '-p','127.0.0.1:8001:8000','--read-only','--tmpfs','/tmp:rw,noexec,nosuid,size=160m,mode=1777',
                      '--cap-drop','ALL','--security-opt','no-new-privileges:true','--memory','512m','--memory-swap','1g',
                      '--pids-limit','64','--log-opt','max-size=5m','--log-opt','max-file=3',args.image)
        print('Candidate started on loopback 8001; existing API and nginx unchanged.')
        print('Preserved pre-existing rows:',{k:len(v) for k,v in original.items()})
    finally:
        env_path.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
