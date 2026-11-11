import unittest
from audit_signal_links import audit

def row(kind,x,**extra):return dict(kind=kind,lat=37.6,lng=126.95+x/88200,district='서대문구',**extra)
class SignalLinksTests(unittest.TestCase):
 def test_one_signal_near_two_crosswalks_is_ambiguous(self):
  r=audit([row('CROSSWALK',0),row('CROSSWALK',5),row('PEDESTRIAN_SIGNAL',2)])
  self.assertEqual(r['districts']['서대문구']['AMBIGUOUS_NEARBY_SIGNAL'],2)
 def test_duplicate_signal_does_not_add_evidence(self):
  s=row('PEDESTRIAN_SIGNAL',2);r=audit([row('CROSSWALK',0),s,s])
  self.assertEqual(r['uniqueSignalCoordinates'],1);self.assertEqual(r['signalMatching']['ONE_CANDIDATE'],1)
 def test_absent_is_not_unsignalized(self):
  r=audit([row('CROSSWALK',0),row('PEDESTRIAN_SIGNAL',50)])
  self.assertEqual(r['districts']['서대문구']['NO_NEARBY_SIGNAL_RECORD'],1)
 def test_invalid_coordinates_excluded(self):
  r=audit([dict(kind='CROSSWALK',lat=float('nan'),lng=127)])
  self.assertEqual(r['invalidCoordinateRows'],1);self.assertEqual(r['crosswalkRows'],0)
