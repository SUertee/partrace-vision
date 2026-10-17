import unittest
from station_vision.hand_object.motion import MotionJudge


class MotionTests(unittest.TestCase):
    def run_sequence(self, centers, hands=None, **kwargs):
        judge = MotionJudge()
        results=[]
        for i, x in enumerate(centers):
            hand = (x+5, 5) if hands is None else hands[i]
            results.append(judge.update(i*.2, [x, 0, x+20, 20], hand,
                                        owner='p1:right', **kwargs))
        return results

    def test_already_carrying_is_not_pickup(self):
        r=self.run_sequence(range(0, 40, 2))
        self.assertIn('co_motion_candidate', [x['label'] for x in r])
        self.assertNotIn('pickup_candidate', [x['label'] for x in r])

    def test_stationary_then_co_motion(self):
        r=self.run_sequence([0]*7+list(range(2, 32, 2)), support_observed=True)
        self.assertIn('pickup_candidate', [x['label'] for x in r])
        self.assertFalse(any(x['verified'] for x in r))

    def test_standing_with_parcel_then_walking_is_not_pickup(self):
        r=self.run_sequence([0]*7+list(range(2,32,2)))
        self.assertIn('co_motion_candidate',[x['label'] for x in r])
        self.assertNotIn('pickup_candidate',[x['label'] for x in r])

    def test_hand_passes_stationary_object(self):
        r=self.run_sequence([0]*20, [(i, 5) for i in range(20)])
        self.assertNotIn('pickup_candidate', [x['label'] for x in r])
        self.assertNotIn('co_motion_candidate', [x['label'] for x in r])

    def test_object_moves_without_hand(self):
        r=self.run_sequence(range(0, 40, 2), [(5, 5)]*20)
        self.assertNotIn('co_motion_candidate', [x['label'] for x in r])

    def test_equal_endpoints_do_not_hide_inconsistent_motion(self):
        r=self.run_sequence([0,2,4,6,8], [(5,5),(17,5),(-1,5),(21,5),(13,5)])
        self.assertEqual(r[-1]['label'],'uncertain')
        self.assertEqual(r[-1]['reason'],'hand_object_motion_mismatch')

    def test_missing_wrist(self):
        r=self.run_sequence(range(20), [None]*20)
        self.assertTrue(all(x['label']=='uncertain' for x in r))

    def test_multiple_people(self):
        r=self.run_sequence(range(20), ambiguous=True)
        self.assertTrue(all(x['reason']=='multiple_people_near_object' for x in r))

    def test_camera_motion(self):
        r=self.run_sequence(range(20), background_displacement=(8, 0))
        self.assertTrue(all(x['reason']=='camera_motion_requires_registration' for x in r))

    def test_gap_invalidates_pickup_baseline(self):
        j=MotionJudge()
        for i in range(7): j.update(i*.2, [0, 0, 20, 20], (5,5), owner='p:r')
        r=j.update(4, [10,0,30,20], (15,5), owner='p:r')
        self.assertEqual(r['reason'], 'collecting_temporal_evidence')
        self.assertFalse(j.stationary_baseline)

    def test_switching_hand_does_not_extend_motion(self):
        j=MotionJudge()
        j.update(0, [0,0,20,20], (5,5), owner='p:r')
        r=j.update(.2, [2,0,22,20], (7,5), owner='p:l')
        self.assertEqual(r['reason'], 'person_or_hand_changed')

    def test_affine_tracker_handles_known_rotation_and_scale(self):
        import cv2
        import numpy as np
        from station_vision.hand_object.tracking import ParcelFlow
        image=np.random.default_rng(12).integers(0,256,(256,256),dtype=np.uint8)
        image=cv2.GaussianBlur(image,(3,3),0)
        matrix=cv2.getRotationMatrix2D((128,128),5,1.08)
        matrix[:,2]+=[3,2]
        next_image=cv2.warpAffine(image,matrix,(256,256))
        tracker=ParcelFlow(image,[64,64,192,192],geometry='affine_corners')
        tracker.update(image,next_image)
        self.assertTrue(tracker.ok,tracker.quality)
        expected=np.array([[64,64],[192,64],[192,192],[64,192]])@matrix[:,:2].T+matrix[:,2]
        np.testing.assert_allclose(tracker.box,np.r_[expected.min(axis=0),expected.max(axis=0)],atol=3)


if __name__=='__main__': unittest.main()
