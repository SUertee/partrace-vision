"""Quality, temporal separation and appearance diversity for enrollment frames."""
import cv2
import numpy as np

from .encoder import crop_person,crop_quality
from ..runtime_parameters import defaults


def enrollment_candidate(frame,person,others,minimum_height=None,settings=None):
    cfg=settings or defaults()['a_quality']
    crop=crop_person(frame,person.bbox)
    ok,quality=crop_quality(crop,min_height=minimum_height or cfg['min_height'],min_width=cfg['min_width'],min_blur=cfg['min_blur'])
    if not ok:return None,quality
    a,b,c,d=person.bbox;area=max((c-a)*(d-b),1)
    overlap=0.
    for other in others:
        if other.track_id==person.track_id:continue
        x,y,z,w=other.bbox
        overlap=max(overlap,max(0,min(c,z)-max(a,x))*max(0,min(d,w)-max(b,y))/area)
    torso=sum(person.keypoints[k][2]>=cfg['joint_conf'] for k in (5,6,11,12)) if len(person.keypoints)>12 else 0
    if person.score<cfg['min_detection_conf'] or overlap>cfg['max_overlap'] or torso<cfg['minimum_torso_joints']:
        return None,{**quality,'reason':'low_confidence_occlusion_or_torso','overlap':overlap,'torso_joints':torso}
    height,width=frame.shape[:2]
    edge=any((a<=2,b<=2,c>=width-2,d>=height-2))
    sharp=min(1.,quality['blur']/cfg['blur_saturation'])
    size=min(1.,(d-b)/max(height*cfg['size_saturation'],1))
    score=(.45*sharp+.3*size+.25*person.score)*(1-overlap)*(cfg['edge_penalty'] if edge else 1.)
    if score<cfg['minimum_selection_quality']:return None,{**quality,'reason':'low_selection_quality','selection_quality':round(score,4)}
    return crop,{**quality,'reason':None,'occlusion_fraction':round(overlap,3),
        'torso_joints':torso,'touches_frame_edge':edge,'selection_quality':round(score,4)}


def select_keyframes(candidates,minimum=3,maximum=5,min_gap_ms=400,settings=None):
    """Select distinct clear source times; reject inconsistent track majorities.

    View diversity is measured in OSNet space, never labelled as guaranteed
    front/side/back pose. Source frames remain genuine, with no interpolation.
    """
    cfg=settings or defaults()['a_keyframes']
    if settings:minimum,maximum,min_gap_ms=cfg['minimum'],cfg['maximum'],cfg['min_gap_ms']
    if len(candidates)<minimum:return [],'insufficient_quality_frames'
    features=np.stack([np.asarray(c['feature'],np.float32) for c in candidates])
    features/=np.maximum(np.linalg.norm(features,axis=1,keepdims=True),1e-8)
    similarities=features@features.T
    support=(similarities>=cfg['consistency_similarity']).sum(axis=1)
    medoid=int(np.argmax(support))
    inliers=[i for i in range(len(candidates)) if similarities[medoid,i]>=cfg['consistency_similarity']]
    if len(inliers)<minimum or len(inliers)<cfg['consistency_fraction']*len(candidates):return [],'inconsistent_track_appearance'
    ranked=sorted(inliers,key=lambda i:candidates[i]['quality']['selection_quality'],reverse=True)
    chosen=[ranked[0]];span=max(1000,max(c['time_ms'] for c in candidates)-min(c['time_ms'] for c in candidates))
    while len(chosen)<maximum:
        available=[i for i in ranked if i not in chosen and
            all(abs(candidates[i]['time_ms']-candidates[j]['time_ms'])>=min_gap_ms for j in chosen) and
            all(candidates[i]['crop_sha256']!=candidates[j]['crop_sha256'] for j in chosen)]
        if not available:break
        def score(i):
            appearance=float(min(1-similarities[i,j] for j in chosen))
            separation=min(abs(candidates[i]['time_ms']-candidates[j]['time_ms']) for j in chosen)/span
            total=cfg['quality_weight']+cfg['diversity_weight']+cfg['time_weight']
            return (cfg['quality_weight']*candidates[i]['quality']['selection_quality']+cfg['diversity_weight']*min(1,appearance/.3)+cfg['time_weight']*separation)/total
        chosen.append(max(available,key=score))
    if len(chosen)<minimum:return [],'insufficient_distinct_spaced_frames'
    return sorted((candidates[i] for i in chosen),key=lambda c:c['time_ms']),'selected'
