"""Pose-based first reach anchors. Estimated palms are cues, not contact proof."""
import cv2
import numpy as np
from ..runtime_parameters import defaults


def palm_cues(row, person, confidence=None):
    """A short forearm extension approximates a hand region, never a measured hand."""
    cfg=row.get('hand_geometry') or defaults()['hand_geometry']
    confidence=cfg['joint_confidence'] if confidence is None else confidence
    result=[]
    points=person['keypoints']
    scale=row.get('width',720)/720
    height=max(1.,person['box'][3]-person['box'][1])
    for hand,e,w in (('left',7,9),('right',8,10)):
        elbow,wrist=np.asarray(points[e],float),np.asarray(points[w],float)
        vector=wrist[:2]-elbow[:2]
        length=float(np.linalg.norm(vector))
        if min(elbow[2],wrist[2])<confidence or not cfg['forearm_min_px_at_720']*scale<=length<=cfg['forearm_max_body_ratio']*height:
            continue
        result.append({'hand':hand,'wrist':wrist[:2].tolist(),
            'palm_estimate':(wrist[:2]+cfg['extension_ratio']*vector).tolist(),
            'uncertainty_segment':[(wrist[:2]+fraction*vector).tolist() for fraction in (cfg['uncertainty_min_ratio'],cfg['uncertainty_max_ratio'])],
            'confidence':float(min(elbow[2],wrist[2])),
            'measured_hand':False})
    return result


def reference_points(row,points):
    matrix=np.linalg.inv(np.asarray(row.get('reference_to_source',np.eye(3)),float))
    return cv2.perspectiveTransform(np.asarray(points,np.float32).reshape(-1,1,2),matrix).reshape(-1,2)


def _median_path(points,times,window_ms=80):
    # Time-based smoothing handles non-30fps sources without inventing samples.
    return np.asarray([np.median(points[np.abs(times-t)<=window_ms],axis=0) for t in times])


def first_reach(rows,session,settings=None):
    """Find the earliest approach/retreat, using geometry only, never reference labels.

    All shelf regions form one target for the motion signal. Layer labels do not
    participate in peak selection. A missing/flat arm trajectory yields no anchor.
    """
    cfg=settings or defaults()['reach']
    start,end=session['start_ms'],session['end_ms']
    streams={'left':[],'right':[]}
    for index,row in enumerate(rows):
        if not start<=row['time_ms']<=end or not row.get('registration_valid'):continue
        person=next((p for p in row['people'] if p['id']==session.get('person_id')),None)
        if person is None or not row.get('shelf_polygons'):continue
        for cue in palm_cues(row,person):
            streams[cue['hand']].append((index,row,cue))
    candidates=[]
    for hand,samples in streams.items():
        if len(samples)<cfg['minimum_samples']:continue
        times=np.asarray([r['time_ms'] for _,r,_ in samples])
        if times[0]-start>cfg['onset_tolerance_ms']:continue
        points=np.asarray([reference_points(r,[c['palm_estimate']])[0] for _,r,c in samples])
        points=_median_path(points,times,cfg['smooth_window_ms'])
        first_row=samples[0][1]
        shelf=np.concatenate([reference_points(first_row,z['polygon']) for z in first_row['shelf_polygons']])
        center=np.mean(shelf,axis=0)
        direction=center-points[0]
        if np.linalg.norm(direction)<5:continue
        direction/=np.linalg.norm(direction)
        progress=(points-points[0])@direction
        scale=first_row.get('width',720)/720
        peak=0;confirmed=False;retreat=None
        for j in range(1,len(samples)):
            if times[j]-times[0]>cfg['search_window_ms']:break
            if progress[j]>progress[peak]:peak=j
            if (progress[peak]>=cfg['minimum_approach_px_at_720']*scale and times[peak]-times[0]>=cfg['minimum_approach_ms'] and
                    times[j]-times[peak]>=cfg['retreat_confirm_ms'] and progress[peak]-progress[j]>=cfg['retreat_distance_px_at_720']*scale):
                # Require a sustained retreat instead of one-frame pose jitter.
                recent=progress[(times>=times[j]-cfg['retreat_smooth_window_ms'])&(times<=times[j])]
                if len(recent)>=2 and np.median(recent)<=progress[peak]-cfg['retreat_median_px_at_720']*scale:
                    confirmed=True;retreat=j;break
        if not confirmed:continue
        if np.max(np.diff(times[:retreat+1]))>cfg['max_observation_gap_ms']:continue
        amplitude=float(progress[peak])
        # Third interior frame should show a changed hand/object state, not only
        # the mathematical turning point. Use the earliest substantial retreat.
        desired=max(cfg['withdrawal_distance_px_at_720']*scale,cfg['withdrawal_amplitude_ratio']*amplitude)
        for j in range(peak+1,len(samples)):
            if times[j]-times[peak]>cfg['withdrawal_window_ms']:break
            if times[j]-times[peak]>=cfg['withdrawal_min_ms'] and progress[peak]-progress[j]>=desired:
                retreat=j;break
        if times[retreat]-times[peak]>cfg['withdrawal_window_ms']:continue
        if np.max(np.diff(times[:retreat+1]))>cfg['max_observation_gap_ms']:continue
        # The leading arm must have reliable joints throughout the reach.
        quality=float(np.median([c['confidence'] for _,_,c in samples[:retreat+1]]))
        candidates.append({'hand':hand,'onset_index':samples[0][0],
            'peak_index':samples[peak][0],'withdrawal_index':samples[retreat][0],
            'onset_ms':float(times[0]),'peak_ms':float(times[peak]),'withdrawal_ms':float(times[retreat]),
            'approach_px_reference':round(amplitude,3),'pose_confidence':round(quality,3),
            'selection_score':amplitude*quality,'confirmed_approach_retreat':True})
    if not candidates:return None
    # Prefer an early, reliable leading arm. Later re-grasps do not displace it.
    earliest=min(c['peak_ms'] for c in candidates)
    close=[c for c in candidates if c['peak_ms']<=earliest+cfg['early_hand_tolerance_ms']]
    chosen=max(close,key=lambda c:c['selection_score'])
    chosen.pop('selection_score')
    return chosen


def operation_layer(rows,session,reach,settings=None):
    """Vote only near the first reach peak, not during foreground carrying.

    Each source frame votes at most once. All three points on the estimated hand
    uncertainty segment must lie inside the SAME manually calibrated region.
    Ambiguous/border points abstain. This is still a 2D algorithm suggestion.
    """
    cfg=settings or defaults()['layer_vote']
    votes={};evidence=[];eligible=0
    if reach:
        for row in rows:
            if abs(row['time_ms']-reach['peak_ms'])>cfg['window_ms'] or not row.get('registration_valid'):continue
            person=next((p for p in row['people'] if p['id']==session.get('person_id')),None)
            if person is None:continue
            cue=next((c for c in palm_cues(row,person) if c['hand']==reach['hand']),None)
            if cue is None:continue
            eligible+=1
            points=[cue['uncertainty_segment'][0],cue['palm_estimate'],cue['uncertainty_segment'][1]]
            fits=[z for z in row.get('shelf_polygons',[]) if all(
                cv2.pointPolygonTest(np.asarray(z['polygon'],np.float32),tuple(map(float,point)),True)>=0
                for point in points)]
            if len(fits)!=1:continue
            z=fits[0];votes[z['id']]=votes.get(z['id'],0)+1
            evidence.append({'frame_id':row['frame_id'],'time_ms':row['time_ms'],
                'region_id':z['id'],'level':z.get('level'),'hand':cue['hand'],
                'wrist':cue['wrist'],'palm_estimate':cue['palm_estimate'],
                'uncertainty_segment':cue['uncertainty_segment']})
    region=max(votes,key=votes.get) if votes else None
    support=votes.get(region,0);fraction=support/max(1,eligible)
    accepted=support>=cfg['minimum_support'] and fraction>=cfg['minimum_fraction']
    level=next((e['level'] for e in evidence if e['region_id']==region),None) if accepted else None
    return {'region_id':region if accepted else None,'shelf_level':level,
        'basis':'first_reach_hand_region_estimate' if accepted else 'ambiguous_or_missing_hand_region',
        'eligible_source_frames':eligible,'supporting_source_frames':support,
        'support_fraction':round(fraction,3),'votes':votes,'evidence':evidence,
        'is_physical_contact_proof':False,'is_calibrated_probability':False,
        'scope':'first_reach_origin_only; other operations require visual review'}


def enrich_sessions(rows,sessions,parameters=None):
    parameters=parameters or defaults()
    for session in sessions:
        session['algorithm_parameters']={k:parameters[k] for k in ('reach','layer_vote','evidence')}
        reach=first_reach(rows,session,parameters['reach'])
        layer=operation_layer(rows,session,reach,parameters['layer_vote'])
        session.update(reach_anchor=reach,layer_evidence=layer,
            region_id=layer['region_id'],shelf_level=layer['shelf_level'],
            shelf_level_basis=layer['basis'],shelf_level_is_physical_contact_proof=False)
    return sessions
