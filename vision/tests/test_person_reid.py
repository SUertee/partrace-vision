import tempfile
import unittest
from pathlib import Path
import numpy as np
from station_vision.reid.matching import GalleryMatcher
from station_vision.reid.encoder import crop_person
from station_vision.reid.pipeline import enrollment_folders


def obs(key, feature=None):
    return dict(track_id=key, feature=feature)


class MatchingTests(unittest.TestCase):
    def make(self, **kwargs):
        return GalleryMatcher({'G0001': [[1., 0., 0.]], 'G0002': [[0., 1., 0.]]}, **kwargs)

    def test_unknown_alias_persists_without_features(self):
        m = self.make()
        a = m.update(0, [obs('B-t3')])['B-t3']
        b = m.update(100, [obs('B-t3')])['B-t3']
        self.assertEqual(a, b)
        self.assertEqual(a, dict(person_id='U0001', status='unmatched'))

    def test_promote_only_after_three_observations(self):
        m = self.make()
        for t in (0, 300):
            self.assertEqual(m.update(t, [obs('p', [1, 0, 0])])['p']['person_id'], 'U0001')
        self.assertEqual(m.update(600, [obs('p', [1, 0, 0])])['p'],
                         dict(person_id='G0001', status='matched'))

    def test_same_timestamp_cannot_fake_three_supports(self):
        m = self.make()
        for _ in range(5):
            out = m.update(0, [obs('p', [1, 0, 0])])
        self.assertEqual(out['p']['status'], 'unmatched')

    def test_ambiguous_gallery_candidates_are_rejected(self):
        m = self.make()
        for t in (0, 300, 600, 900):
            out = m.update(t, [obs('p', [1, 1, 0])])
        self.assertEqual(out['p']['status'], 'unmatched')

    def test_low_similarity_is_not_forced_to_nearest(self):
        m = self.make()
        for t in (0, 300, 600):
            out = m.update(t, [obs('p', [0, 0, 1])])
        self.assertEqual(out['p']['person_id'], 'U0001')

    def test_two_people_cannot_take_same_profile(self):
        m = self.make()
        for t in (0, 300, 600):
            out = m.update(t, [obs('p', [1, 0, 0]), obs('q', [1, 0, 0])])
        self.assertEqual({v['status'] for v in out.values()}, {'unmatched'})

    def test_two_distinct_people_link_to_two_profiles(self):
        m = self.make()
        for t in (0, 300, 600):
            out = m.update(t, [obs('p', [1, 0, 0]), obs('q', [0, 1, 0])])
        self.assertEqual({v['person_id'] for v in out.values()}, {'G0001', 'G0002'})

    def test_supports_do_not_accumulate_across_long_gap(self):
        m = self.make()
        for t in (0, 300):
            m.update(t, [obs('p', [1, 0, 0])])
        self.assertEqual(m.update(2000, [obs('p', [1, 0, 0])])['p']['status'], 'unmatched')

    def test_one_bad_frame_does_not_switch_identity(self):
        m = self.make()
        for t in (0, 300, 600):
            m.update(t, [obs('p', [1, 0, 0])])
        out = m.update(900, [obs('p', [0, 1, 0])])
        self.assertEqual(out['p']['person_id'], 'G0001')

    def test_contradictions_revert_to_original_unknown_alias(self):
        m = self.make()
        for t in (0, 300, 600):
            m.update(t, [obs('p', [1, 0, 0])])
        for t in (900, 1200, 1500):
            out = m.update(t, [obs('p', [0, 0, 1])])
        self.assertEqual(out['p'], dict(person_id='U0001', status='unmatched'))

    def test_reservation_expires_without_duplicate_binding_on_return(self):
        m = self.make(confirmations=1)
        m.update(0, [obs('old', [1, 0, 0])])
        m.update(1000, [obs('new', [1, 0, 0])])
        out = m.update(1100, [obs('new'), obs('old', [1, 0, 0])])
        self.assertEqual(out['new']['person_id'], 'G0001')
        self.assertEqual(out['old']['status'], 'unmatched')

    def test_short_occlusion_reserves_global_id(self):
        m = self.make(confirmations=1)
        m.update(0, [obs('p', [1, 0, 0])])
        out = m.update(300, [obs('q', [1, 0, 0])])
        self.assertEqual(out['q']['status'], 'unmatched')

    def test_no_features_do_not_demote_visible_link(self):
        m = self.make(confirmations=1)
        m.update(0, [obs('p', [1, 0, 0])])
        for t in range(100, 2000, 100):
            out = m.update(t, [obs('p')])
        self.assertEqual(out['p']['person_id'], 'G0001')

    def test_empty_gallery_still_returns_unknown(self):
        m = GalleryMatcher({})
        self.assertEqual(m.update(0, [obs('p', [1, 0])])['p']['status'], 'unmatched')

    def test_invalid_vectors_and_reverse_time_are_rejected(self):
        m = self.make()
        with self.assertRaises(ValueError):
            m.update(100, [obs('p', [0, 0, 0])])
        with self.assertRaises(ValueError):
            m.update(0, [obs('p')])

    def test_public_states_have_only_requested_fields(self):
        m = self.make()
        self.assertEqual(set(m.update(0, [obs('p')])['p']), {'person_id', 'status'})


class EnrollmentInputTests(unittest.TestCase):
    def test_empty_folders_do_not_become_fake_profiles(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'person_001').mkdir()
            (root / 'person_001/README.md').write_text('instructions')
            with self.assertRaises(ValueError):
                enrollment_folders(root)

    def test_unicode_image_names_are_discovered(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sub = root / '人物一'
            sub.mkdir()
            (sub / '正面.PNG').write_bytes(b'fixture')
            self.assertEqual(enrollment_folders(root)[0][1][0].name, '正面.PNG')

    def test_outside_or_invalid_crop_is_not_replaced_with_whole_image(self):
        image = np.zeros((100, 100, 3), dtype=np.uint8)
        self.assertIsNone(crop_person(image, [150, 0, 160, 50]))
        self.assertIsNone(crop_person(image, [np.nan, 0, 10, 50]))


if __name__ == '__main__':
    unittest.main()
