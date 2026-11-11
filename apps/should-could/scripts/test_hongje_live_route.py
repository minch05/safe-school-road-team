"""공개 장소 홍제역→홍제초 정문으로 현재 서비스 비교 점검.
TMAP 응답은 메모리에서만 읽고 경로선/원본 좌표/장소명 응답 파일은 쓰지 않는다.
"""
import collections,http.cookiejar,html.parser,json,pathlib,urllib.request,urllib.parse
ROOT=pathlib.Path(__file__).resolve().parents[1]
class Meta(html.parser.HTMLParser):
 def __init__(self):super().__init__();self.values={}
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=='meta' and a.get('name'):self.values[a['name']]=a.get('content','')
if __name__=='__main__':
 jar=http.cookiejar.CookieJar();opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar));base='http://localhost:8081'
 html=opener.open(base+'/explore',timeout=15).read().decode();meta=Meta();meta.feed(html)
 def search(q):return json.load(opener.open(base+'/api/places/search?'+urllib.parse.urlencode({'q':q}),timeout=35))
 starts=search('홍제역');ends=search('서울홍제초등학교정문')
 start=next((p for p in starts if '홍제역' in p.get('title','')),None);end=next((p for p in ends if '홍제초등학교정문' in p.get('title','')),None)
 if not start or not end:raise SystemExit('공개 출발지/정문 POI를 확인하지 못했습니다.')
 def place(p):return {'name':p['title'][:30],'lat':p['lat'],'lng':p['lng']}
 token=next((urllib.parse.unquote(c.value) for c in jar if c.name=='XSRF-TOKEN'),None);header=meta.values.get('_csrf_header')
 if not token or not header:raise SystemExit('CSRF 확인 실패. 요청하지 않음.')
 req=urllib.request.Request(base+'/api/explore/candidates',data=json.dumps({'start':place(start),'end':place(end)}).encode(),headers={'Content-Type':'application/json',header:token},method='POST')
 response=json.load(opener.open(req,timeout=65));candidates=response.get('candidates',[])
 if not candidates:raise SystemExit('후보 없음')
 summaries=[]
 for c in candidates:
  # 파일에는 공공 시설 집계/미확인 정도만 저장. TMAP 선/거리/시간/지점 응답 미저장.
  featureCounts=collections.Counter(f.get('kind') for f in c.get('mapFeatures',[]))
  crime=c.get('crime',{});safeCrime={k:crime[k] for k in ['available','message','coveragePercent'] if k in crime}
  summaries.append({'label':c['label'],'crossings':c['crossings'],'mapFeatureCounts':dict(featureCounts),'schoolZoneTargetCount':len(c.get('schoolZones',[])),'accidentOverlapCount':len(c.get('accidents',[])),'crimeState':safeCrime})
  print(c['label'],'거리',c['distance'],'시간',c['time'],'횡단',c['crossings'],'시설',dict(featureCounts),flush=True)
 report={'checkedAt':'2026-10-10','scenario':'공개 장소 홍제역 → 서울홍제초등학교정문 검색 POI','candidateCount':len(candidates),'merged':response.get('merged'),'summaries':summaries,'limits':['정문 검색 POI는 현장 출입 가능 여부 확인이 아님','실제 GPS 보행/시설 작동 검증 아님','현재 운영 DB 기준. 반영 원본은 reviewed-import-summary.json과 함께 확인','원본 경로 응답/선 좌표/거리/시간은 파일 미저장']}
 (ROOT/'docs/hongje-live-route-check.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
