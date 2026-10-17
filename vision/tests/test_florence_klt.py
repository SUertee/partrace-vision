import unittest
import numpy as np

from station_vision.florence.tracking import FlorenceKLTBank
from station_vision.florence.shelf import ShelfState
from station_vision.florence.observer import actor_crop, source_boxes, related_shelf_changes


def detection(box):
    return {'box': box, 'label': 'the box', 'task': 'held', 'priority': 0, 'person_hint': 'P001'}


class FlorenceKLTTests(unittest.TestCase):
    def setUp(self):
        self.gray = np.random.default_rng(42).integers(0, 256, (240, 320), dtype=np.uint8)

    def test_localization_and_correction_never_become_displacement_evidence(self):
        bank = FlorenceKLTBank()
        bank.correct(self.gray, [detection([40, 50, 90, 110])], 0)
        first = bank.observations(0)[0]
        self.assertFalse(first['valid'])
        self.assertFalse(first['motion_observed'])
        for t in (33, 66, 100):
            bank.advance(self.gray, self.gray, t)
        self.assertTrue(bank.observations(100)[0]['motion_observed'])
        bank.correct(self.gray, [detection([42, 50, 92, 110])], 100)
        corrected = bank.observations(100)[0]
        self.assertEqual(first['id'], corrected['id'])
        self.assertEqual(corrected['source'], 'florence')
        self.assertFalse(corrected['motion_observed'])
        self.assertIsNone(corrected['score'])

    def test_stale_tracks_expire_even_when_texture_still_matches(self):
        bank = FlorenceKLTBank(max_age_ms=100)
        bank.correct(self.gray, [detection([40, 50, 90, 110])], 0)
        bank.advance(self.gray, self.gray, 101)
        self.assertEqual(bank.observations(101), [])
        self.assertEqual(bank.log[-1]['reason'], 'localization_age_expired')

    def test_ambiguous_overlap_stays_invalid_until_clear_redetection(self):
        bank = FlorenceKLTBank()
        bank.correct(self.gray, [detection([40, 50, 90, 110]), detection([55, 50, 105, 110])], 0)
        self.assertEqual(len(bank.tracks), 2)
        bank.correct(self.gray, [detection([47.5, 50, 97.5, 110])], 50)
        self.assertTrue(all(row['ambiguous'] for row in bank.observations(50)))
        bank.advance(self.gray, self.gray, 83)
        self.assertTrue(all(not row['valid'] and row['ambiguous'] for row in bank.observations(83)))

    def test_reacquisition_of_lost_texture_requires_fresh_history(self):
        bank = FlorenceKLTBank()
        bank.correct(self.gray, [detection([40, 50, 90, 110])], 0)
        pid = next(iter(bank.tracks))
        bank.tracks[pid]['flow'].fail('test_occlusion')
        bank.correct(self.gray, [detection([40, 50, 90, 110])], 100)
        row = bank.observations(100)[0]
        self.assertEqual(row['id'], pid)
        self.assertTrue(row['identity_reset'])
        self.assertFalse(row['valid'])

    def test_source_gap_retires_ids_instead_of_stitching_across_missing_frames(self):
        bank = FlorenceKLTBank()
        bank.correct(self.gray, [detection([40, 50, 90, 110])], 0)
        bank.advance(self.gray, self.gray, 1000, 'source_time_gap')
        self.assertEqual(bank.observations(1000), [])


class ShelfStateTests(unittest.TestCase):
    def setUp(self):
        self.zones = [{'id': 's', 'level': None, 'polygon': [[0,0],[99,0],[99,99],[0,99]]}]
        self.image = np.full((100, 100, 3), 80, np.uint8)
        self.mask = np.full((100, 100), 255, np.uint8)

    def test_occlusion_and_invalid_registration_do_not_claim_removal(self):
        state = ShelfState(self.zones)
        state.update(self.image, self.mask, 0, 'f0', True)
        changed = self.image.copy(); changed[20:60, 20:60] = 200
        mask = self.mask.copy(); mask[15:65, 15:65] = 0
        row = state.update(changed, mask, 500, 'f1', True)[0]
        self.assertEqual(row['status'], 'stable')
        invalid = state.update(changed, self.mask, 600, 'f2', False)[0]
        self.assertEqual(invalid['status'], 'unknown')
        self.assertIsNone(invalid['change_fraction'])
        self.assertIsNone(invalid['shelf_level_hint'])
        self.assertNotIn('action', invalid)

    def test_uniform_brightness_change_is_compensated(self):
        state = ShelfState(self.zones)
        state.update(self.image, self.mask, 0, 'f0', True)
        row = state.update(self.image + 35, self.mask, 300, 'f1', True)[0]
        self.assertEqual(row['status'], 'stable')

    def test_change_requires_persistence_and_reference_refreshes_after_settling(self):
        state = ShelfState(self.zones)
        state.update(self.image, self.mask, 0, 'f0', True)
        changed = self.image.copy(); changed[20:60, 20:60] = 200
        self.assertEqual(state.update(changed, self.mask, 100, 'f1', True, ['s'])[0]['status'], 'change_pending')
        self.assertEqual(state.update(changed, self.mask, 350, 'f2', True, ['s'])[0]['status'], 'appearance_changed')
        state.update(changed, self.mask, 2000, 'f3', True)
        self.assertEqual(state.update(changed, self.mask, 2100, 'f4', True)[0]['status'], 'stable')


class LocalizationCoordinateTests(unittest.TestCase):
    def test_crop_covers_wrists_outside_body_box(self):
        keys = [[0, 0, 0] for _ in range(17)]
        keys[9] = [100, 250, .9]
        crop = actor_crop({'box': [200, 200, 300, 400], 'keypoints': keys}, 720, 1280)
        self.assertLessEqual(crop[0], 100)
        self.assertGreater(crop[2], 300)

    def test_crop_boxes_translate_to_source_and_do_not_assign_people_from_text(self):
        result = {'task_name': 'held', 'boxes': [{'box':[10,20,60,70], 'label':'the box'},
                                                {'box':[0,0,100,100], 'label':'person'}]}
        boxes = source_boxes(result, [100,200,400,600], 720,1280,[('P001','left',[120,240])], 'P001')
        self.assertEqual(len(boxes), 1)
        self.assertEqual(boxes[0]['box'], [110,220,160,270])
        self.assertEqual(boxes[0]['person_hint'], 'P001')
        self.assertNotIn('person_id', boxes[0])

    def test_actor_crop_does_not_admit_a_far_box_merely_because_prompt_says_held(self):
        result = {'task_name': 'held', 'boxes': [{'box':[10,20,60,70], 'label':'the box'}]}
        self.assertEqual(source_boxes(result, [100,200,400,600],720,1280,
                                     [('P001','left',[600,800])], 'P001'), [])

    def test_generic_bag_is_not_a_verified_parcel_category(self):
        result = {'task_name': 'boxes', 'boxes': [{'box':[10,20,60,70], 'label':label}
                  for label in ('bags', 'backpack', 'mailing bag')]}
        boxes = source_boxes(result, [0,0,200,200],720,1280,[])
        self.assertEqual([b['label'] for b in boxes], ['mailing bag'])


class ShelfAssociationTests(unittest.TestCase):
    def test_changes_in_another_region_or_time_are_not_attached_to_parcel(self):
        row = {'time_ms': 500, 'parcels': [{'id': 'K0001', 'box': [10,10,30,30]}],
               'shelf_polygons': [{'id': 'left', 'polygon': [[0,0],[40,0],[40,40],[0,40]]},
                                  {'id': 'right', 'polygon': [[50,0],[90,0],[90,40],[50,40]]}],
               'shelf_state': [{'region_id':'left','status':'appearance_changed','frame_id':'f1'},
                               {'region_id':'right','status':'appearance_changed','frame_id':'f2'}]}
        candidate = {'target_type':'parcel','target_id':'K0001','clip_start_ms':0,'clip_end_ms':1000}
        later = {**row, 'time_ms': 1500,
                 'shelf_state': [{'region_id':'left','status':'appearance_changed','frame_id':'f3'}]}
        self.assertEqual(related_shelf_changes(candidate, [row, later]), ['f1'])
        self.assertEqual(related_shelf_changes({**candidate,'target_type':'region','target_id':'right'}, [row]), ['f2'])


if __name__ == '__main__':
    unittest.main()
