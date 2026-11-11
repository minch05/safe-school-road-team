import datetime,unittest
from audit_seoul_coverage import inspect
class CoverageTests(unittest.TestCase):
 def test_empty_area_is_not_absence(self):
  r=inspect([],datetime.date(2026,10,10));self.assertEqual(len(r['matrix']),200);self.assertTrue(all(x['status']=='NO_REGISTERED_DATA' for x in r['matrix']))
 def test_download_date_is_not_facility_age(self):
  rows=[{'district':'서대문구','kind':'CROSSWALK','baseDate':'2020-01-01','lat':37.6,'lng':126.9}]
  r=inspect(rows,datetime.date(2026,10,10));x=next(x for x in r['matrix'] if x['district']=='서대문구' and x['kind']=='CROSSWALK');self.assertIsNone(x['overTwoYearsRows'])
 def test_dates_duplicates_and_unassigned_are_separate(self):
  rows=[{'district':'서대문구','kind':'SECURITY_LIGHT','baseDate':'2020-01-01','lat':37.6,'lng':126.9},{'district':'서대문구','kind':'SECURITY_LIGHT','baseDate':None,'lat':37.6,'lng':126.9},{'district':'','kind':'SCHOOL'}]
  r=inspect(rows,datetime.date(2026,10,10));x=next(x for x in r['matrix'] if x['district']=='서대문구' and x['kind']=='SECURITY_LIGHT');self.assertEqual((x['rows'],x['missingDates'],x['overTwoYearsRows'],x['coLocatedRows']),(2,1,1,1));self.assertEqual(r['unassignedRows'],1)
 def test_existing_db_report_preserves_existing_facilities(self):
  from audit_seoul_coverage import inspect_report
  r=inspect_report({'checkedAt':'2026-10-10','districts':[{'kind':'CCTV','district':'강남구','total':2672}],'facilities':[{'kind':'CCTV','total':59738,'missing_date':0}],'unknownDistrict':2})
  x=next(x for x in r['matrix'] if x['district']=='강남구' and x['kind']=='CCTV');self.assertEqual(x['rows'],2672);self.assertEqual(x['status'],'REGISTERED');self.assertNotIn('newestDate',x);self.assertEqual(r['unassignedRows'],2);self.assertEqual(r['kindDateQuality'][0]['total'],59738)
