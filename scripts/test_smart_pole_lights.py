import unittest
from prepare_smart_pole_lights import prepare
class SmartPoleTests(unittest.TestCase):
 def row(self,**changes):
  r={'기관명':'서대문구','탑재기능':'CCTV, 보안등','수량':'1','설치장소(주소)':'서울시 서대문구 홍은동 (공원)','연번':'1','위도':'37.59','경도':'126.94'};r.update(changes);return r
 def test_only_lighting_function_in_missing_districts(self):
  a,e=prepare([self.row(),self.row(**{'기관명':'구로구'}),self.row(**{'탑재기능':'가로등,CCTV'})]);self.assertEqual(len(a['facilities']),1);self.assertEqual(a['scope'],'SMART_POLE_ONLY');self.assertEqual(e,0)
 def test_no_geocoding_or_grouped_location(self):
  a,e=prepare([self.row(**{'위도':''}),self.row(**{'수량':'2'})]);self.assertEqual(a['facilities'],[]);self.assertEqual(e,2)
 def test_source_identity_and_month_date_meaning_preserved(self):
  a,e=prepare([self.row(),self.row()]);self.assertEqual(len(a['facilities']),1);self.assertEqual(e,1);self.assertEqual(a['facilities'][0]['source'],'SMART_POLE_LIGHT');self.assertEqual(a['facilities'][0]['baseDate'],'2025-12-31')
