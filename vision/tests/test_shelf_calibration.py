import copy
import unittest

from station_vision.shelf_calibration.proposal import normalize


def answer():
    return {'coordinate_space':'normalized_0_1000','reference_frame_id':'f000000',
        'bottom_reference_visible':True,'bottom_reason':'底部可见','limitations':[],
        'shelves':[{'id':'S1','level':1,'polygon':[[0,600],[400,600],[400,800],[0,800]],
                    'support_line':[[0,800],[400,800]],'reason':'储物板'}]}


class ShelfCalibrationTests(unittest.TestCase):
    def test_normalized_points_map_to_exact_source_bounds(self):
        data=answer(); data['shelves'][0]['polygon']=[[0,0],[1000,0],[1000,1000],[0,1000]]
        result=normalize(data,'f000000',720,1280)
        self.assertEqual(result['zones'][0]['polygon'][2],[719,1279])
        self.assertFalse(result['approved_for_action_inference'])

    def test_hidden_bottom_cannot_receive_a_definite_level(self):
        data=answer(); data['bottom_reference_visible']=False
        zone=normalize(data,'f000000',720,1280)['zones'][0]
        self.assertIsNone(zone['level'])
        self.assertEqual(zone['model_level'],1)

    def test_wrong_reference_is_rejected(self):
        with self.assertRaises(ValueError): normalize(answer(),'f000540',720,1280)

    def test_out_of_range_and_nan_are_not_clipped_into_valid_proposals(self):
        for coordinate in (float('nan'),1001,True):
            data=answer(); data['shelves'][0]['polygon'][0][0]=coordinate
            result=normalize(data,'f000000',720,1280)
            self.assertFalse(result['zones']); self.assertEqual(len(result['rejected']),1)

    def test_crossing_polygon_is_not_accepted(self):
        data=answer(); data['shelves'][0]['polygon']=[[0,0],[400,400],[0,400],[400,0]]
        self.assertFalse(normalize(data,'f000000',720,1280)['zones'])

    def test_top_down_level_numbers_are_kept_unknown(self):
        data=answer(); second=copy.deepcopy(data['shelves'][0]); second.update(id='S2',level=2)
        second['polygon']=[[0,810],[400,810],[400,980],[0,980]]
        second['support_line']=[[0,980],[400,980]]
        data['shelves'].append(second)
        result=normalize(data,'f000000',720,1280)
        self.assertTrue(all(z['level'] is None for z in result['zones']))
        self.assertTrue(result['geometry_warnings'])


if __name__=='__main__': unittest.main()
