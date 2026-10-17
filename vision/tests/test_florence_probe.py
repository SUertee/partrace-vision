import unittest

from station_vision.florence.probe import normalized_boxes


class FlorenceBoxParsingTests(unittest.TestCase):
    def test_pixel_boxes_keep_generated_labels_without_invented_confidence(self):
        parsed = {'<OD>': {'bboxes': [[10, 20, 80, 90]], 'labels': ['box']}}
        boxes, rejected = normalized_boxes(parsed, '<OD>', 100, 100)
        self.assertEqual(boxes[0]['box'], [10, 20, 80, 90])
        self.assertEqual(boxes[0]['label'], 'box')
        self.assertIsNone(boxes[0]['score'])
        self.assertEqual(rejected, [])

    def test_invalid_coordinates_are_rejected_without_reordering_or_fake_boxes(self):
        parsed = {'<OD>': {'bboxes': [[30, 10, 20, 40], [0, 0, float('nan'), 30],
                                    [-5, -8, 120, 130], [0, 0, 10, 10]],
                           'labels': ['reversed', 'nonfinite', 'large', '']}}
        boxes, rejected = normalized_boxes(parsed, '<OD>', 100, 100)
        self.assertEqual([item['label'] for item in boxes], ['large'])
        self.assertEqual(boxes[0]['box'], [0, 0, 100, 100])
        self.assertEqual(len(rejected), 3)

    def test_count_mismatch_and_missing_task_are_errors(self):
        with self.assertRaisesRegex(ValueError, 'counts differ'):
            normalized_boxes({'<OD>': {'bboxes': [[0, 0, 10, 10]], 'labels': []}}, '<OD>', 100, 100)
        with self.assertRaisesRegex(ValueError, 'Missing task'):
            normalized_boxes({'other': {}}, '<OD>', 100, 100)

    def test_open_vocabulary_parser_accepts_its_own_bbox_labels(self):
        parsed = {'<OPEN_VOCABULARY_DETECTION>': {
            'bboxes': [[1, 2, 10, 20]], 'bboxes_labels': ['box'], 'polygons': [], 'polygons_labels': []}}
        boxes, rejected = normalized_boxes(parsed, '<OPEN_VOCABULARY_DETECTION>', 100, 100)
        self.assertEqual(boxes[0]['label'], 'box')
        self.assertEqual(rejected, [])


if __name__ == '__main__':
    unittest.main()
