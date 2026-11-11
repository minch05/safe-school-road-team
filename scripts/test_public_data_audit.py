import pathlib,tempfile,unittest,json
from audit_public_data_files import read,coord,audit,ROOT
class FileAuditTests(unittest.TestCase):
 def setUp(self):
  (ROOT/'build').mkdir(exist_ok=True)
 def test_eunpyeong_schema_preserves_real_columns(self):
  with tempfile.TemporaryDirectory(dir=ROOT/'build') as d:
   p=pathlib.Path(d)/'sample.json';p.write_text(json.dumps({'ep':{'column':{'c1':'위도','c2':'경도'},'list':[{'c1':'37.62','c2':'126.92'}]}}))
   rows,_=read(p);self.assertEqual(coord(rows[0]),(37.62,126.92))
 def test_missing_coordinate_is_not_zero_or_geocoded(self):
  self.assertIsNone(coord({'위도':'','경도':'126.9'}))
 def test_declared_seoul_and_bounding_box_are_separate(self):
  with tempfile.TemporaryDirectory(dir=ROOT/'build') as d:
   p=pathlib.Path(d)/'sample.json';p.write_text(json.dumps({'records':[{'소재지도로명주소':'경기도 구리시','위도':'37.60','경도':'127.15'}]}))
   r=audit(p);self.assertEqual(r['seoulCandidateRows'],0);self.assertEqual(r['seoulRectangleCoordinateRows'],1)
 def test_duplicate_and_date_missing_reported_without_mutation(self):
  with tempfile.TemporaryDirectory(dir=ROOT/'build') as d:
   p=pathlib.Path(d)/'sample.json';p.write_text(json.dumps({'records':[{'위도':'37.6','경도':'127.1'}]*2}));before=p.read_bytes();r=audit(p)
   self.assertEqual(r['exactDuplicateRows'],1);self.assertEqual(r['missingBaseDate'],2);self.assertEqual(p.read_bytes(),before)
if __name__=='__main__':unittest.main()
