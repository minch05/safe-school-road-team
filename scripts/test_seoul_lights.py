import datetime,hashlib,unittest
from prepare_seoul_lights import prepare
from download_seoul_lights import review
HEADER='보안등위치명,설치개수,소재지도로명주소,소재지지번주소,위도,경도,관리기관명,데이터기준일자\n'
class LightsTests(unittest.TestCase):
 def prepare(self,lines):
  raw=(HEADER+lines).encode();return prepare([(dict(name='서울특별시_은평구_보안등정보',sha256=hashlib.sha256(raw).hexdigest()),raw)],datetime.date(2026,10,10))
 def test_same_address_distinct_coordinates_preserved(self):
  r,_=self.prepare('등1,1,서울 은평구 길1,,37.6,126.9,은평구,2026-06-30\n등1,1,서울 은평구 길1,,37.61,126.9,은평구,2026-06-30\n')
  self.assertEqual(len(r['facilities']),2)
 def test_aggregate_old_and_missing_coordinates_quarantined(self):
  r,s=self.prepare('등1,31,서울 은평구 길1,,37.6,126.9,은평구,2026-06-30\n등2,1,서울 은평구 길2,,,126.9,은평구,2026-06-30\n등3,1,서울 은평구 길3,,37.6,126.9,은평구,2020-01-01\n')
  self.assertEqual(r['facilities'],[]);self.assertEqual(sum(s[0]['excluded'].values()),3)
 def test_hash_change_refused(self):
  with self.assertRaises(ValueError):prepare([(dict(sha256='x'),b'')],datetime.date(2026,10,10))
 def test_non_csv_refused(self):
  with self.assertRaises(ValueError):review(b'<html>login</html>')
