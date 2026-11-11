"""개인정보 없는 로컬 서버 점검. HTML/보호된 API의 응답 상태·지연만 기록."""
import argparse,json,time,urllib.request,urllib.error,statistics
from pathlib import Path
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None

def check(base):
 opener=urllib.request.build_opener(NoRedirect());results=[]
 for path,expected in [('/',200),('/explore',200),('/map',200),('/login',200),('/data-info',200),('/health/ready',200),('/api/facilities/catalog',200),('/api/session',200),('/api/places',401),('/api/routes/1/points',401)]:
  timings=[];statuses=[];errors=[];protections=[];status=0;headers={}
  for _ in range(3):
   started=time.monotonic()
   try:res=opener.open(base+path,timeout=10)
   except urllib.error.HTTPError as e:res=e
   except (urllib.error.URLError,TimeoutError,OSError) as e:
    statuses.append(0);errors.append(type(e).__name__);protections.append(False)
    timings.append(round((time.monotonic()-started)*1000,1));continue
   try:
    with res:
     status=res.status;headers=dict(res.headers);res.read()
    statuses.append(status);protections.append(headers.get('X-Content-Type-Options')=='nosniff')
   except (urllib.error.URLError,TimeoutError,OSError) as e:
    statuses.append(0);errors.append(type(e).__name__);protections.append(False)
   timings.append(round((time.monotonic()-started)*1000,1))
  results.append({'path':path,'status':statuses[-1],'statuses':statuses,'errors':errors,'expected':expected,'passed':all(s==expected for s in statuses),'latencyMs':{'median':statistics.median(timings),'max':max(timings)},'nosniff':all(protections),'frameProtection':headers.get('X-Frame-Options'),'cacheControl':headers.get('Cache-Control')})
 return {'checkedAt':time.strftime('%Y-%m-%d %H:%M:%S'),'passed':all(r['passed'] and r['nosniff'] for r in results),'results':results,'limits':['로컬 저부하 측정이며 운영 부하 시험 아님','비로그인 경계만 점검, 가족 권한은 서버 회귀 테스트에서 검증','위치/계정/경로 응답 본문을 보고서에 저장하지 않음']}
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--base',default='http://localhost:8081');parser.add_argument('--report',type=Path,default=Path('docs/service-readiness-check.json'));args=parser.parse_args();r=check(args.base);args.report.write_text(json.dumps(r,ensure_ascii=False,indent=2));print(json.dumps({'passed':r['passed'],'checks':len(r['results'])}));raise SystemExit(0 if r['passed'] else 1)
