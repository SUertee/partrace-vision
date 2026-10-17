"""Whole-shelf reach sessions; wrist projection never assigns a shelf level."""
from dataclasses import dataclass
import math

import cv2
import numpy as np
from .reach import enrich_sessions


@dataclass
class SessionSettings:
    wrist_confidence: float = .35
    enter_ms: float = 300
    switch_ms: float = 800  # Legacy config compatibility; wrist layer switches are no longer used.
    max_start_gap_ms: float = 150
    same_visit_gap_ms: float = 5000
    departure_ms: float = 1000
    missing_ms: float = 750
    margin_px_at_720: float = 10
    minimum_reach_motion_px_at_720: float = 5
    minimum_observations: int = 3
    departure_distance_px_at_720: float = 50
    departure_body_width_ratio: float = .8


def layer_hits(row,person,cfg):
    hits={}
    scale=row.get('width',720)/720
    for zone in row.get('shelf_polygons',[]):
        poly=np.asarray(zone['polygon'],np.float32)
        valid=[]
        for hand,k in (('left',9),('right',10)):
            pt=person['keypoints'][k]
            if pt[2]<cfg.wrist_confidence:
                continue
            distance=cv2.pointPolygonTest(poly,tuple(map(float,pt[:2])),True)
            if distance>=-cfg.margin_px_at_720*scale:
                valid.append((hand,pt,float(distance)))
        if valid:
            hits[zone['id']]={'level':zone.get('level'),'hands':valid,
                'score':sum(max(1.,p[2]+cfg.margin_px_at_720*scale)*p[1][2] for p in valid)}
    return hits


class SessionMachine:
    def __init__(self,settings=None):
        self.cfg=settings or SessionSettings()
        self.people={}
        self.sessions=[]

    def close(self,state,reason,confirmed_at):
        session=state.pop('active',None)
        if session:
            session['end_ms']=round(state['last_active_ms'],3)
            session['end_frame_id']=state['last_active_frame']
            session['closed_at_ms']=round(confirmed_at,3)
            session['close_reason']=reason
            session['boundary_uncertain']=reason in ('person_observation_lost','registration_lost','video_end')
            self.sessions.append(session)
        state.pop('pending',None)

    def update(self,row):
        now=row['time_ms']
        visible={p['id']:p for p in row['people']}
        for pid in set(visible)|set(self.people):
            state=self.people.setdefault(pid,{})
            person=visible.get(pid)
            if person is None or not row.get('registration_valid',False):
                state.pop('pending',None)
                if state.get('active') and now-state['last_visible_ms']>=self.cfg.missing_ms:
                    self.close(state,'person_observation_lost' if person is None else 'registration_lost',now)
                continue
            state['last_visible_ms']=now
            hits=layer_hits(row,person,self.cfg)
            active=state.get('active')
            # Wrist hits are only a WHOLE-SHELF trigger. Foreground wrists can
            # project onto lower shelves while operating on an upper shelf.
            key='whole_shelf' if hits else None
            hands={h[0]:h for hit in hits.values() for h in hit['hands']}
            if key is None:
                state.pop('pending',None)
                if active:
                    # Departure is only based on valid visible body position;
                    # tracker loss is handled separately and remains uncertain.
                    a,b,c,d=person['box']
                    point=((a+c)/2,(b+d)/2)
                    gap=min((max(0.,-cv2.pointPolygonTest(np.asarray(z['polygon'],np.float32),point,True))
                             for z in row.get('shelf_polygons',[])),default=math.inf)
                    far=gap>max(self.cfg.departure_distance_px_at_720*row.get('width',720)/720,(c-a)*self.cfg.departure_body_width_ratio)
                    if far: state.setdefault('far_since',now)
                    else: state.pop('far_since',None)
                    if now-state['last_active_ms']>self.cfg.same_visit_gap_ms:
                        self.close(state,'operation_timeout',now)
                    elif far and now-state['far_since']>=self.cfg.departure_ms:
                        self.close(state,'visible_person_departure',now)
                continue
            state.pop('far_since',None)
            if active:
                state['last_active_ms']=now
                state['last_active_frame']=row['frame_id']
                active['supporting_observation_count']+=1
                active['hands']=sorted(set(active['hands'])|set(hands))
                active['wrist_trigger_regions']=sorted(set(active['wrist_trigger_regions'])|set(hits))
                state.pop('pending',None)
                continue
            pending=state.get('pending')
            if not pending or pending['region_id']!=key or now-pending['last_ms']>self.cfg.max_start_gap_ms:
                pending={'region_id':key,'start_ms':now,'start_frame':row['frame_id'],
                    'last_ms':now,'count':1,'first_points':{},'max_motion':0.}
                state['pending']=pending
            else:
                pending['last_ms']=now
                pending['count']+=1
            # Stabilized wrist movement is a reach cue, not proof of contact.
            matrix=np.linalg.inv(np.asarray(row.get('reference_to_source',np.eye(3)),float))
            for hand,point,_ in hands.values():
                p=cv2.perspectiveTransform(np.asarray(point[:2],np.float32).reshape(1,1,2),matrix).ravel()
                first=pending['first_points'].setdefault(hand,p)
                pending['max_motion']=max(pending['max_motion'],float(np.linalg.norm(p-first)))
            needed=self.cfg.enter_ms
            if (now-pending['start_ms']+1e-5>=needed and pending['count']>=self.cfg.minimum_observations and
                    pending['max_motion']>=self.cfg.minimum_reach_motion_px_at_720*row.get('width',720)/720):
                state['active']={'person_id':pid,'region_id':None,'shelf_level':None,
                    'start_ms':round(pending['start_ms'],3),'start_frame_id':pending['start_frame'],
                    'detected_at_ms':round(now,3),'hands':sorted(hands),'wrist_trigger_regions':sorted(hits),
                    'supporting_observation_count':pending['count'],'trigger':'sustained_whole_shelf_reach',
                    'trigger_is_physical_contact_proof':False}
                state['last_active_ms']=now
                state['last_active_frame']=row['frame_id']
                state.pop('pending',None)

    def finish(self,last_ms):
        for state in self.people.values(): self.close(state,'video_end',last_ms)
        result=sorted(self.sessions,key=lambda s:(s['start_ms'],s['person_id']))
        for i,s in enumerate(result): s['id']=f's{i+1:04d}'
        return result


def detect_sessions(rows,settings=None,parameters=None):
    machine=SessionMachine(settings)
    for row in rows: machine.update(row)
    return enrich_sessions(rows,machine.finish(rows[-1]['time_ms']),parameters) if rows else []


def five_frames(rows,session):
    cfg=session.get('algorithm_parameters',{}).get('evidence',{})
    before,after=cfg.get('before_ms',300),cfg.get('after_ms',300)
    start,end=session['start_ms'],session['end_ms']
    duration=end-start
    reach=session.get('reach_anchor')
    if reach:
        targets=[('before',start-before),('reach_onset',reach['onset_ms']),
                 ('reach_peak',reach['peak_ms']),('withdrawal',reach['withdrawal_ms']),('after',end+after)]
        method='first_approach_peak_withdrawal'
    else:
        # Fallback is explicit; uniform frames do not become invented key actions.
        targets=[('before',start-before),('fallback_25',start+.25*duration),
                 ('fallback_50',start+.5*duration),('fallback_75',start+.75*duration),('after',end+after)]
        method='uniform_fallback_no_reliable_reach'
    times=np.asarray([r['time_ms'] for r in rows])
    result=[]
    for role,target in targets:
        pos=int(np.searchsorted(times,target))
        options={max(0,min(len(rows)-1,pos)),max(0,min(len(rows)-1,pos-1))}
        index=min(options,key=lambda i:(abs(times[i]-target),i))
        result.append({'role':role,'sampling_method':method,'target_ms':round(target,3),'frame_id':rows[index]['frame_id'],
            'source_frame_index':index,'actual_ms':rows[index]['time_ms'],
            'offset_ms':round(rows[index]['time_ms']-target,3),
            'truncated':bool(target<times[0] or target>times[-1])})
    return result
