"""Manually initialized parcel tracking and background displacement measurements."""
import cv2
import numpy as np


class ParcelFlow:
    """Short-lived texture tracking; never re-identifies a lost parcel."""
    def __init__(self, gray, box, geometry='translation'):
        self.box=np.asarray(box, dtype=float)
        if geometry not in ('translation','affine_corners'): raise ValueError('Unknown tracking geometry')
        self.geometry=geometry
        a,b,c,d=self.box
        self.corners=np.array([[a,b],[c,b],[c,d],[a,d]],dtype=float)
        self.initial_area=(c-a)*(d-b)
        mask=np.zeros_like(gray)
        x1,y1,x2,y2=np.rint(box).astype(int)
        mask[max(0,y1+3):y2-3,max(0,x1+3):x2-3]=255
        self.points=cv2.goodFeaturesToTrack(gray, 100, .01, 4, mask=mask, blockSize=5)
        self.initial_points=0 if self.points is None else len(self.points)
        self.ok=self.initial_points>=6
        self.quality={'tracked_points':self.initial_points, 'retained_fraction':1., 'median_fb_error':0.}
        self.quality['loss_reason']=None if self.ok else 'insufficient_initial_texture_points'

    def fail(self, reason, **details):
        self.ok=False
        self.quality.update(loss_reason=reason, **details)

    def update(self, previous, current):
        if not self.ok: return
        new, status, _=cv2.calcOpticalFlowPyrLK(previous, current, self.points, None,
                                             winSize=(21,21), maxLevel=3)
        if new is None:
            self.fail('forward_flow_failed')
            return
        back, reverse, _=cv2.calcOpticalFlowPyrLK(current, previous, new, None,
                                               winSize=(21,21), maxLevel=3)
        if back is None:
            self.fail('backward_flow_failed')
            return
        errors=np.linalg.norm(self.points.reshape(-1,2)-back.reshape(-1,2), axis=1)
        valid=(status.ravel()>0)&(reverse.ravel()>0)&(errors<1.5)
        p=self.points.reshape(-1,2)[valid]
        q=new.reshape(-1,2)[valid]
        if len(p)<6:
            self.fail('too_few_forward_backward_consistent_points',tracked_points=len(p))
            return
        transform, inliers=cv2.estimateAffinePartial2D(p,q,method=cv2.RANSAC,ransacReprojThreshold=2)
        if transform is None:
            self.fail('robust_affine_fit_failed')
            return
        inliers=inliers.ravel().astype(bool)
        ratio=float(inliers.sum()/max(self.initial_points,1))
        scale=float(np.hypot(transform[0,0],transform[0,1]))
        if inliers.sum()<6 or ratio<.25:
            self.fail('insufficient_retained_inliers',tracked_points=int(inliers.sum()),retained_fraction=ratio,estimated_scale=scale)
            return
        minimum,maximum=(.8,1.25) if self.geometry=='affine_corners' else (.95,1.05)
        if not minimum<=scale<=maximum:
            self.fail('per_frame_scale_guard',tracked_points=int(inliers.sum()),retained_fraction=ratio,estimated_scale=scale)
            return
        # Move the center using the robust affine map; retain initial box dimensions.
        # The prototype does not estimate parcel rotation or dimensions in depth.
        if self.geometry=='affine_corners':
            corners=self.corners@transform[:,:2].T+transform[:,2]
            area_ratio=abs(cv2.contourArea(corners.astype(np.float32)))/self.initial_area
            if not .15<=area_ratio<=6:
                self.fail('cumulative_area_guard',area_ratio=area_ratio)
                return
            self.corners=corners
            self.box=np.r_[corners.min(axis=0),corners.max(axis=0)]
        else:
            center=(self.box[:2]+self.box[2:])/2
            moved=transform[:,:2]@center+transform[:,2]
            delta=moved-center
            self.box+=np.tile(delta,2)
        height,width=current.shape
        visible=max(0,min(width,self.box[2])-max(0,self.box[0]))*max(0,min(height,self.box[3])-max(0,self.box[1]))
        area=(self.box[2]-self.box[0])*(self.box[3]-self.box[1])
        if visible/max(area,1)<.75:
            self.fail('object_out_of_view',visible_fraction=float(visible/max(area,1)))
            return
        self.points=q[inliers].reshape(-1,1,2).astype(np.float32)
        self.quality={'tracked_points':int(inliers.sum()),'retained_fraction':round(ratio,3),
                      'median_fb_error':round(float(np.median(errors[valid][inliers])),3),'loss_reason':None}


def background_motion(previous, current, people, parcels):
    mask=np.full_like(previous,255)
    for box in [p['box'] for p in people]+[p.box for p in parcels]:
        a,b,c,d=np.rint(box).astype(int)
        cv2.rectangle(mask,(max(0,a-20),max(0,b-20)),(c+20,d+20),0,-1)
    p=cv2.goodFeaturesToTrack(previous,250,.02,15,mask=mask)
    if p is None or len(p)<20: return None
    q,s,_=cv2.calcOpticalFlowPyrLK(previous,current,p,None)
    if q is None: return None
    good=s.ravel()>0
    if good.sum()<20: return None
    return np.median((q-p).reshape(-1,2)[good],axis=0)
