"""Persistent anonymous appearance archive. Existing IDs/images are never replaced."""
import contextlib
from datetime import datetime
import hashlib
import html
import json
import os
from pathlib import Path
import re
import uuid

import numpy as np

from .matching import unit_vector
from ..runtime_parameters import defaults


def digest(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def atomic_json(path,value):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    temp=path.with_name(path.name+'.'+uuid.uuid4().hex+'.tmp')
    try:
        with temp.open('x',encoding='utf-8') as stream:
            json.dump(value,stream,ensure_ascii=False,indent=2);stream.flush();os.fsync(stream.fileno())
        os.replace(temp,path)
    finally:
        if temp.exists():temp.unlink()


@contextlib.contextmanager
def archive_lock(folder):
    folder=Path(folder);folder.mkdir(parents=True,exist_ok=True)
    with (folder/'archive.lock').open('a+b') as stream:
        stream.seek(0,2)
        if stream.tell()==0:stream.write(b'0');stream.flush()
        stream.seek(0)
        if os.name=='nt':
            import msvcrt
            try:msvcrt.locking(stream.fileno(),msvcrt.LK_NBLCK,1)
            except OSError as exc:raise RuntimeError('另一个自动建档进程正在写入档案库，请等待它完成。') from exc
        else:
            import fcntl
            fcntl.flock(stream.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB)
        try:yield
        finally:
            stream.seek(0)
            if os.name=='nt':msvcrt.locking(stream.fileno(),msvcrt.LK_UNLCK,1)
            else:fcntl.flock(stream.fileno(),fcntl.LOCK_UN)


def valid_id(pid):return isinstance(pid,str) and re.fullmatch(r'G[0-9]{4,}',pid) is not None


def resolve_crop_path(value,archive_dir=None):
    """Historical snapshots stay unchanged; locate moved, hash-checked photos."""
    path=Path(value)
    if path.is_file():return path
    parts=path.parts
    if 'auto_archive' in parts:
        index=parts.index('auto_archive');path=Path(*parts[:index],*parts[index+1:])
    if path.is_file():return path
    historical=path.parent/'history'/path.name
    if historical.is_file():return historical
    # An archive copied Windows -> Ubuntu may still contain old absolute paths.
    # Resolve only G-ID photos under THIS archive, never by arbitrary basename.
    if archive_dir is not None:
        folder=Path(archive_dir).resolve()
        parts=str(value).replace('\\','/').split('/')
        if not Path(value).is_absolute() and ':' not in str(value) and '..' not in parts:
            candidate=(folder/Path(*parts)).resolve()
            if candidate.is_relative_to(folder) and candidate.is_file():return candidate
        for i,part in enumerate(parts[:-1]):
            if valid_id(part):
                tail=parts[i:]
                if len(tail) not in (2,3) or (len(tail)==3 and tail[1]!='history') or '..' in tail:continue
                candidate=(folder/Path(*tail)).resolve()
                if candidate.is_relative_to(folder) and candidate.is_file():return candidate
                candidate=(folder/part/'history'/tail[-1]).resolve()
                if candidate.is_relative_to(folder) and candidate.is_file():return candidate
    return path


def read_archive(path,weights_sha256=None):
    data=json.loads(Path(path).read_text(encoding='utf-8-sig'))
    if data.get('format')!='station_person_archive_v1':raise ValueError('Unsupported person archive')
    if weights_sha256 is not None and data.get('weights_sha256')!=weights_sha256:
        raise ValueError('人物档案使用的OSNet权重与当前模型不一致。')
    for pid,profile in data['profiles'].items():
        if not valid_id(pid) or not profile['templates']:raise ValueError('Invalid archive profile')
        for item in profile['templates']:
            vector=unit_vector(item['feature'])
            if len(vector)!=512:raise ValueError('Invalid OSNet feature dimension')
    def relocate(value):
        if isinstance(value,dict):
            for key in ('archive_crop','legacy_crop'):
                if value.get(key) and not Path(value[key]).is_file():
                    candidate=resolve_crop_path(value[key],Path(path).parent)
                    if candidate.is_file() and (not value.get('crop_sha256') or digest(candidate)==value['crop_sha256']):
                        value[key]=str(candidate)
            for v in value.values():relocate(v)
        elif isinstance(value,list):
            for v in value:relocate(v)
    relocate(data)
    return data


def gallery_vectors(data):
    return {pid:[t['feature'] for t in p['templates']] for pid,p in data['profiles'].items()}


class ProfileStore:
    def __init__(self,root,weights_sha256,folder='data/person_enrollment',settings=None):
        self.root=Path(root);self.folder=(self.root/folder).resolve();self.path=self.folder/'archive.json'
        self.legacy=self.root/'data/person_enrollment/profile_registry.json';self.weights=weights_sha256
        self.settings=settings or defaults()['archive_matching']
        self.migrate_flat_layout()
        with archive_lock(self.folder):
            state=self._read();self._import_legacy(state)
            for pid,profile in state['profiles'].items():self._bound_profile(state,pid)
            atomic_json(self.path,state);self._cards(state)

    def migrate_flat_layout(self):
        """Move only this application's old archive; never overwrite user files."""
        expected=(self.root/'data/person_enrollment').resolve();old=expected/'auto_archive'
        if self.folder!=expected or not (old/'archive.json').is_file():return
        if old.is_symlink() or old.resolve().parent!=expected:raise ValueError('Unsafe old archive path')
        with archive_lock(expected),archive_lock(old):
            if self.path.exists():raise RuntimeError('新旧archive.json同时存在，拒绝覆盖。')
            state=read_archive(old/'archive.json',self.weights)
            folders=[p for p in old.iterdir() if p.is_dir()]
            if any(not valid_id(p.name) or p.is_symlink() or p.resolve().parent!=old.resolve() for p in folders):
                raise ValueError('旧档案含未知目录或链接，拒绝自动移动。')
            for p in folders:
                target=expected/p.name
                if target.exists():raise FileExistsError(f'迁移目标已存在，拒绝覆盖: {target}')
            backup=self.root/'.runtime/person_archive_migration'/datetime.now().strftime('%Y%m%d_%H%M%S_%f')
            backup.mkdir(parents=True)
            (backup/'archive.json').write_bytes((old/'archive.json').read_bytes())
            moves={}
            for p in folders:
                target=expected/p.name
                if not target.resolve().is_relative_to(expected):raise ValueError('Unsafe migration target')
                p.rename(target);moves[str(p)]=str(target)
            def rewrite(value):
                if isinstance(value,dict):return {k:rewrite(v) for k,v in value.items()}
                if isinstance(value,list):return [rewrite(v) for v in value]
                if isinstance(value,str):
                    for a,b in moves.items():
                        if value.startswith(a+os.sep):return b+value[len(a):]
                return value
            state=rewrite(state);atomic_json(self.path,state)
            atomic_json(expected/'layout_migration.json',{'old_directory':str(old),'new_directory':str(expected),
                'backup':str(backup),'moves':moves,'ids_unchanged':True})
            (old/'archive.json').unlink()
            if (old/'index.html').is_file():(old/'index.html').rename(backup/'index.html')
        # Only known lock + empty directory are removed, never recursive cleanup.
        (old/'archive.lock').unlink(missing_ok=True)
        if not list(old.iterdir()):old.rmdir()

    def _bound_profile(self,state,pid):
        profile=state['profiles'][pid];pool=profile['templates'];maximum=self.settings['maximum_templates']
        def quality(item):
            q=item.get('quality',{})
            return q.get('selection_quality',min(1,q.get('blur',0)/150)*.45+.3)
        selected=[];remaining=sorted(pool,key=quality,reverse=True)
        while remaining and len(selected)<maximum:
            # Repeated recordings of identical pixels are not new evidence.
            unique=[t for t in remaining if not any(t.get('crop_sha256') and t.get('crop_sha256')==s.get('crop_sha256') for s in selected)]
            if not unique:break
            if selected:
                def rank(t):
                    separation=min(1-float(unit_vector(t['feature'])@unit_vector(s['feature'])) for s in selected)
                    return .85*quality(t)+.15*min(1,separation/.3)
                best=max(unique,key=rank)
            else:best=unique[0]
            remaining.remove(best);selected.append(best)
        changes={}
        folder=self.folder/pid
        if folder.is_symlink() or folder.resolve().parent!=self.folder:raise ValueError('Unsafe profile directory')
        folder.mkdir(exist_ok=True)
        for item in selected:
            value=item.get('archive_crop') or item.get('legacy_crop')
            if not value:continue
            source=resolve_crop_path(value)
            if not source.is_file():continue
            item.setdefault('crop_sha256',digest(source))
            if digest(source)!=item['crop_sha256']:raise ValueError('档案截图哈希变化，拒绝使用')
            destination=folder/source.name
            if source.parent!=folder:
                if destination.exists() and digest(destination)!=digest(source):raise FileExistsError(destination)
                if not destination.exists():destination.write_bytes(source.read_bytes())
                item['archive_crop']=str(destination)
        for item in remaining:
            value=item.get('archive_crop')
            if not value:continue
            source=resolve_crop_path(value)
            if source.is_file() and source.parent==folder:
                historical=folder/'history'/source.name;historical.parent.mkdir(exist_ok=True)
                if historical.parent.is_symlink() or historical.resolve().parent!=folder/'history':raise ValueError('Unsafe history directory')
                if historical.exists():
                    if digest(historical)!=digest(source):raise FileExistsError(historical)
                    # Existing equal history copy: keep the original without overwriting.
                    continue
                source.rename(historical);changes[str(source)]=str(historical);item['archive_crop']=str(historical)
        profile['templates']=selected
        if remaining:profile.setdefault('historical_templates',[]).extend(remaining)
        if changes:
            for occurrence in state['occurrences'].values():
                for item in occurrence.get('keyframes',[]):
                    if item.get('archive_crop') in changes:item['archive_crop']=changes[item['archive_crop']]

    def _cards(self,state):
        cards=[]
        for pid,profile in state['profiles'].items():
            value={'person_id':pid,'possible_duplicate_of':profile.get('possible_duplicate_of'),
                'active_image_count':len(profile['templates']),'history_count':len(profile.get('historical_templates',[])),
                'minimum_three_images_met':len(profile['templates'])>=3,
                'keyframes':[{k:v for k,v in t.items() if k!='feature'} for t in profile['templates']]}
            atomic_json(self.folder/pid/'profile.json',value)
            images=[]
            for t in profile['templates']:
                p=Path(t.get('archive_crop',''))
                if p.is_file() and p.is_relative_to(self.folder):
                    images.append('<img src="'+html.escape(p.relative_to(self.folder).as_posix(),quote=True)+'">')
            note='样本不足三张（历史手工档案）' if len(profile['templates'])<3 else '有效代表图'
            duplicate='；可能与 '+html.escape(profile['possible_duplicate_of'])+' 重复' if profile.get('possible_duplicate_of') else ''
            cards.append(f'<section><h2>{pid}</h2><p>{note} {len(profile["templates"])}张；历史 {len(profile.get("historical_templates",[]))}张{duplicate}</p>'+''.join(images)+f'<p><a href="{pid}/profile.json">档案详情</a></p></section>')
        page='<!doctype html><meta charset="utf-8"><title>人物档案</title><style>body{font:16px/1.7 system-ui;margin:24px;background:#f3f5f8}section{background:white;padding:16px;margin:16px 0}img{max-width:180px;max-height:280px;margin:6px;object-fit:contain}</style><h1>人物档案｜精选代表图</h1><p>只展示默认匹配使用的代表图，历史图片保留。G编号为匿名外观档案，未经自然人身份验证。</p>'+''.join(cards)
        (self.folder/'index.html').write_text(page,encoding='utf-8')

    def _registry(self):
        data=json.loads(self.legacy.read_text(encoding='utf-8')) if self.legacy.exists() else {}
        if not isinstance(data,dict) or any(not valid_id(v) for v in data.values()) or len(set(data.values()))!=len(data):
            raise ValueError('Invalid existing profile registry; refusing to reset IDs')
        return data

    def _read(self):
        state=read_archive(self.path,self.weights) if self.path.exists() else {
            'format':'station_person_archive_v1','architecture':'osnet_x0_5','weights_sha256':self.weights,
            'profiles':{},'occurrences':{},'next_id':1,'legacy_imports':[]}
        reserved=[int(p[1:]) for p in self._registry().values()]
        reserved += [int(p[1:]) for p in state['profiles']]
        reserved += [int(p.name[1:]) for p in self.folder.iterdir() if p.is_dir() and valid_id(p.name)]
        old=self.root/'data/person_enrollment/auto_archive'
        if old.is_dir():reserved += [int(p.name[1:]) for p in old.iterdir() if p.is_dir() and valid_id(p.name)]
        state['next_id']=max(state['next_id'],max(reserved,default=0)+1)
        return state

    def _import_legacy(self,state):
        registry=self._registry()
        files=sorted((self.root/'outputs').glob('person_reid*/gallery.json'),key=lambda p:p.stat().st_mtime,reverse=True)
        for file in files:
            try:data=json.loads(file.read_text(encoding='utf-8'))
            except (ValueError,OSError):continue
            if data.get('weights_sha256')!=self.weights:continue
            for detail in data.get('details',[]):
                pid=detail['person_id'];samples=detail.get('samples',[])
                if pid in state['profiles'] or registry.get(detail['folder'])!=pid or not samples:continue
                if not all((self.root/s['path']).is_file() and digest(self.root/s['path'])==s['sha256'] for s in samples):continue
                vectors=data.get('profiles',{}).get(pid,[])
                if len(vectors)!=len(samples):continue
                templates=[]
                for vector,sample in zip(vectors,samples):
                    unit=unit_vector(vector)
                    if len(unit)!=512:raise ValueError('Legacy gallery feature dimension mismatch')
                    templates.append({'feature':unit.tolist(),'legacy_crop':str(file.parent/sample['crop']),
                        'source_image':sample['path'],'source_image_sha256':sample['sha256'],'quality':sample.get('quality',{})})
                state['profiles'][pid]={'created_by':'legacy_screenshot_import','templates':templates}
                state['legacy_imports'].append({'person_id':pid,'gallery_path':str(file),'gallery_sha256':digest(file)})

    def register(self,source,track,samples,run_name):
        if not re.fullmatch(r'[A-Za-z0-9_-]+',track['track_id']):raise ValueError('Invalid local track ID')
        if len(samples)<3:raise ValueError('Enrollment requires at least three independent keyframes')
        if any(len(unit_vector(s['feature']))!=512 for s in samples):raise ValueError('Invalid OSNet feature dimension')
        key=source['sha256']+':'+source.get('enrollment_recipe_sha256','default')+':'+track['track_id']
        with archive_lock(self.folder):
            state=self._read()
            if key in state['occurrences']:
                return {**state['occurrences'][key],'action':'existing_source_track'}
            features=np.stack([unit_vector(s['feature']) for s in samples])
            scores=[]
            for pid,profile in state['profiles'].items():
                templates=np.stack([unit_vector(t['feature']) for t in profile['templates']])
                # Multiple frames must agree; one accidental maximum cannot merge profiles.
                individual=np.sort(features@templates.T,axis=1)[:,-min(self.settings['top_k_templates'],len(templates)):].mean(axis=1)
                scores.append((float(np.median(individual)),pid,individual))
            scores.sort(key=lambda x:x[0],reverse=True)
            best=scores[0] if scores else None;second=scores[1][0] if len(scores)>1 else -1.
            pid=None;action='created_new_profile_uncertain';possible_duplicate=best[1] if best else None
            reason='insufficient_similarity_or_margin'
            if best and best[0]>=self.settings['reuse_threshold'] and best[0]-second>=self.settings['reuse_margin'] and int((best[2]>=self.settings['frame_threshold']).sum())>=self.settings['minimum_support']:
                pid=best[1];action='reused_existing_profile'
                simultaneous=any(o.get('person_id')==pid and o['source_sha256']==source['sha256'] and o['run_name']==run_name and
                    min(o['end_ms'],track['end_ms'])-max(o['start_ms'],track['start_ms'])>self.settings['simultaneous_overlap_ms']
                    for o in state['occurrences'].values())
                if simultaneous:pid=None;action='created_new_profile_simultaneous_conflict'
                reason='simultaneous_track_conflict' if simultaneous else 'multiple_frames_agree'
            if pid is None:
                if best is None or best[0]<self.settings['possible_duplicate_threshold']:action='created_new_profile';possible_duplicate=None
                pid=f'G{state["next_id"]:04d}';state['next_id']+=1
                registry=self._registry()
                if pid in registry.values() or pid in state['profiles']:raise RuntimeError('ID collision; refusing overwrite')
                # Reserve BEFORE files/state writes: crashes may leave gaps, never reused IDs.
                registry['__auto_profile_'+pid]=pid;atomic_json(self.legacy,registry)
                action='created_new_profile_uncertain' if possible_duplicate else 'created_new_profile'
                state['profiles'][pid]={'created_by':'A_video','templates':[],
                    'possible_duplicate_of':possible_duplicate,'duplicate_human_confirmed':False}
            else:possible_duplicate=None
            occurrence={'person_id':pid,'action':action,'reason':reason,'source_sha256':source['sha256'],
                'source_path':source['path'],'track_id':track['track_id'],'start_ms':track['start_ms'],'end_ms':track['end_ms'],
                'enrollment_recipe_sha256':source.get('enrollment_recipe_sha256'),
                'best_similarity':round(best[0],4) if best else None,'second_similarity':round(second,4),
                'similarity_is_probability':False,'possible_duplicate_of':possible_duplicate,
                'run_name':run_name,'keyframes':[]}
            pending=[]
            for sample in samples:
                record={k:v for k,v in sample.items() if k not in ('png_bytes','feature')}
                folder=self.folder/pid;folder.mkdir(exist_ok=True)
                name=f'{hashlib.sha256(key.encode()).hexdigest()[:24]}_{track["track_id"].replace("-","_")}_{sample["frame_id"]}.png'
                path=folder/name
                with path.open('xb') as stream:stream.write(sample['png_bytes'])
                record['archive_crop']=str(path)
                pending.append({**record,'feature':unit_vector(sample['feature']).tolist()})
                occurrence['keyframes'].append(record)
            state['profiles'][pid]['templates'].extend(pending)
            state['occurrences'][key]=occurrence;self._bound_profile(state,pid)
            occurrence['active_profile_image_count']=len(state['profiles'][pid]['templates'])
            atomic_json(self.path,state);self._cards(state)
            return occurrence
