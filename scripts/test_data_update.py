import copy, datetime, unittest
from review_data_update import review
class UpdateTests(unittest.TestCase):
 def sample(self):return {'facilities':[dict(source='PUBLIC',sourceId=str(i),lat=37.6,lng=127.,baseDate='2026-10-01') for i in range(20)],'risks':[]}
 def test_same_data_passes(self):
  a=self.sample();self.assertTrue(review(a,a)['approved'])
 def test_deleted_source_is_blocked(self):
  a=self.sample();b=copy.deepcopy(a);b['facilities']=[];self.assertFalse(review(a,b)['approved'])
 def test_move_and_date_regression_are_blocked(self):
  a=self.sample();b=copy.deepcopy(a);b['facilities'][0].update(lat=37.61,baseDate='2025-01-01');r=review(a,b);self.assertEqual(len(r['issues']),2)
 def test_future_and_duplicate_are_blocked(self):
  a=self.sample();b=copy.deepcopy(a);b['facilities'].append(b['facilities'][0]);b['facilities'][1]['baseDate']='2099-01-01';self.assertFalse(review(a,b)['approved'])
 def test_report_has_no_coordinates(self):
  import json
  r=json.dumps(review(self.sample(),self.sample()));self.assertNotIn('37.6',r);self.assertNotIn('127.0',r)
