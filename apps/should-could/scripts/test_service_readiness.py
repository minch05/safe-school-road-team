import unittest,urllib.error
from unittest.mock import patch
import check_service_readiness as audit

class Response:
 def __init__(self,status=200):self.status=status;self.headers={'X-Content-Type-Options':'nosniff'}
 def read(self):return b''
 def __enter__(self):return self
 def __exit__(self,*args):pass
class ReadinessTests(unittest.TestCase):
 def test_initial_failure_is_not_hidden_by_later_success(self):
  class Opener:
   calls=0
   def open(self,url,timeout):
    self.calls+=1
    return Response(503 if self.calls==1 else 401 if '/api/places' in url or '/points' in url else 200)
  with patch.object(audit.urllib.request,'build_opener',return_value=Opener()):r=audit.check('http://localhost:8081')
  self.assertFalse(r['passed']);self.assertEqual(r['results'][0]['statuses'],[503,200,200])
 def test_unreachable_server_produces_report_instead_of_crashing(self):
  class Opener:
   def open(self,url,timeout):raise urllib.error.URLError('offline')
  with patch.object(audit.urllib.request,'build_opener',return_value=Opener()):r=audit.check('http://localhost:8081')
  self.assertFalse(r['passed']);self.assertTrue(all(x['errors']==['URLError']*3 for x in r['results']))
if __name__=='__main__':unittest.main()
