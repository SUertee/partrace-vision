"""Read-only B association and conservative local-track -> archive event mapping."""
import numpy as np

from .encoder import OSNetEncoder,crop_person,crop_quality
from .matching import GalleryMatcher
from .profile_store import read_archive,gallery_vectors,digest
from ..runtime_parameters import defaults


class BIdentity:
    def __init__(self,root,archive_path,device,parameters=None):
        self.parameters=parameters or defaults()
        self.encoder=OSNetEncoder(root,device,weights=self.parameters['osnet']['weights'])
        self.data=read_archive(archive_path,self.encoder.sha256)
        self.sha256=digest(archive_path);self.path=str(archive_path)
        matching={k:v for k,v in self.parameters['b_matching'].items() if k!='feature_fps'}
        self.matcher=GalleryMatcher(gallery_vectors(self.data),**matching)
        self.last_features={}

    def observe(self,raw,people,time_ms):
        crops=[];keys=[];features={}
        for p in people:
            if time_ms-self.last_features.get(p['id'],-1e9)<1000/self.parameters['b_matching']['feature_fps']-1e-5:continue
            self.last_features[p['id']]=time_ms
            crop=crop_person(raw,p['box']);ok,_=crop_quality(crop,**self.parameters['b_quality'])
            if ok:crops.append(crop);keys.append(p['id'])
        vectors=self.encoder.encode(crops);features.update(zip(keys,vectors))
        states=self.matcher.update(time_ms,[{'track_id':p['id'],'feature':features.get(p['id'])} for p in people])
        for p in people:
            state=states[p['id']];matched=state['status']=='matched'
            p.update(profile_id=state['person_id'] if matched else None,identity_status=state['status'],
                identity_feature_observed=p['id'] in features,
                identity_similarity=self.matcher.states[p['id']].score,identity_similarity_is_probability=False)


def resolve_session_identities(rows,sessions,settings=None):
    cfg=settings or defaults()['b_session']
    for session in sessions:
        observations=[]
        for row in rows:
            if not session['start_ms']<=row['time_ms']<=session['end_ms']:continue
            p=next((p for p in row['people'] if p['id']==session['person_id']),None)
            if p is not None and p.get('identity_feature_observed'):
                observations.append((row,p))
        ids={p['profile_id'] for _,p in observations if p.get('profile_id')}
        supporting=[{'frame_id':r['frame_id'],'time_ms':r['time_ms'],'profile_id':p['profile_id'],
            'similarity':p.get('identity_similarity')} for r,p in observations if p.get('profile_id')]
        coverage=len(supporting)/max(1,len(observations))
        profile=next(iter(ids)) if len(ids)==1 and len(supporting)>=cfg['minimum_support'] and coverage>=cfg['minimum_coverage'] else None
        session['identity']={'local_person_id':session['person_id'],'profile_id':profile,
            'status':'matched' if profile else ('conflict' if len(ids)>1 else 'unmatched'),
            'independent_matched_observations':len(supporting),'matched_feature_coverage':round(coverage,3),'evidence':supporting,
            'identity_human_verified':False,'similarity_is_probability':False,'thresholds':cfg}
    return sessions


def event_person_id(session,opinion):
    local=opinion.get('person_id',session['person_id'])
    if local!=session['person_id']:return None
    return session['identity']['profile_id'] if 'identity' in session else local
