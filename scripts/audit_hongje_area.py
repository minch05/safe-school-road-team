"""홍제초 주변 공개 시설 원본 점검. DB 및 TMAP 미사용."""
import collections,json,math,pathlib,sys
from audit_public_data_files import ROOT,read,coord,audit
DATA=ROOT/'무제 폴더';REVIEW=DATA/'품질검토/2026-10-10';OUT=REVIEW/'hongje'
CENTER=(37.59350546,126.9454205)
def distance(a,b):return math.hypot((a[0]-b[0])*110540,(a[1]-b[1])*88200)
def latest(rows,key):
 out={}
 for r in rows:
  k=r.get(key)
  if not k:continue
  try:h=int(r.get('HSTRY_ID') or 0)
  except ValueError:h=0
  if k not in out or h>int(out[k].get('HSTRY_ID') or 0):out[k]=r
 return list(out.values())
def layer(name,path,kind,idkeys=(),latestkey=None,status=None):
 rows,_=read(path)
 if latestkey:rows=latest(rows,latestkey)
 if status:rows=[r for r in rows if str(r.get(status,''))=='001']
 local=[];dates=collections.Counter();features=[]
 for r in rows:
  c=coord(r)
  if not c or not all(map(math.isfinite,c)) or distance(c,CENTER)>1000:continue
  local.append((r,c));date=str(r.get('데이터기준일자') or r.get('data_ty') or r.get('data_yr') or '')
  dates[date or '미확인']+=1
  ident=next((str(r[k]) for k in idkeys if r.get(k)), '')
  features.append({'type':'Feature','geometry':{'type':'Point','coordinates':[c[1],c[0]]},'properties':{'layer':name,'kind':kind,'sourceId':ident,'baseDate':date or None,'distanceFromSchoolMeters':round(distance(c,CENTER))}})
 exact=collections.Counter(json.dumps(r,sort_keys=True,ensure_ascii=False) for r,c in local)
 result={'layer':name,'file':str(path.relative_to(ROOT)),'totalRowsAfterHistoryStatusFilter':len(rows),'within300m':sum(distance(c,CENTER)<=300 for r,c in local),'within500m':sum(distance(c,CENTER)<=500 for r,c in local),'within1000m':len(local),'baseDates':dict(dates),'exactDuplicateRowsWithin1000m':sum(n-1 for n in exact.values()),'coordinateMeaning':'대표 좌표 중심 직선 반경. 통학 경로/출입구/촬영 범위가 아님'}
 return result,features,local
if __name__=='__main__':
 OUT.mkdir(exist_ok=True)
 specs=[('기존 CCTV',DATA/'CCTV정보_서울특별시.csv','CCTV',['관리번호'],None,None),('기존 비상벨',DATA/'안전비상벨위치정보.csv','EMERGENCY_BELL',['관리번호'],None,None),('서대문구 비상벨 대조',OUT/'sdm-bell.csv','EMERGENCY_BELL',['안전비상벨관리번호'],None,None),('서울 횡단보도',DATA/'서울시_횡단보도_trafficSafetyA004AInfo.json','CROSSWALK',['CRSWK_MNG_NO1'],'CRSWK_MNG_NO1','STTS'),('서울 보행등',REVIEW/'pedestrian-signal-2026.xlsx','PEDESTRIAN_SIGNAL',[],None,None),('최신 학교',REVIEW/'schools-fresh.json','SCHOOL',['학교ID'],None,None),('보호구역 대상시설',REVIEW/'school-zones-fresh.json','SCHOOL_ZONE',['대상시설명'],None,None),('2022년 조도 측정점',OUT/'sdm-illumination.csv','HISTORICAL_ILLUMINANCE',[],None,None)]
 results=[];features=[];localsets={}
 for name,path,kind,ids,key,status in specs:
  r,fs,local=layer(name,path,kind,ids,key,status);results.append(r);features+=fs;localsets[name]=local
 # 지킴이집 원본: 서울/시설종류를 확인하고 좌표만 전달. 상호·전화 미포함.
 houses=[]
 for f in (REVIEW/'safety-houses').glob('*.json'):
  rows,_=read(f);houses += [r for r in rows if str(r.get('fclty_cd'))=='504040']
 tmp=OUT/'safety-house-facility-rows.json';tmp.write_text(json.dumps({'records':houses},ensure_ascii=False))
 r,fs,local=layer('지킴이집',tmp,'CHILD_SAFETY_HOUSE',['objt_id']);results.append(r);features+=fs
 existing=localsets['기존 비상벨'];alt=localsets['서대문구 비상벨 대조']
 matches=collections.Counter()
 for raw,c in alt:
  best=min((distance(c,c2) for _,c2 in existing),default=float('inf'))
  matches['10m 이내 위치 후보' if best<=10 else '10~30m 위치 후보' if best<=30 else '30m 초과/일치 미확인']+=1
 alternative=[audit(OUT/'sdm-bell.csv'),audit(OUT/'sdm-illumination.csv')]
 school=next(r for r in json.loads((REVIEW/'schools-fresh.json').read_text())['records'] if r['학교ID']=='B000001915')
 scenario={'schoolId':'B000001915','name':school['학교명'],'address':school['소재지도로명주소'],'representativeCoordinate':{'lat':CENTER[0],'lng':CENTER[1]},'entranceVerified':False,'radiusMeters':[300,500,1000],'limits':['학교 대표 좌표이며 실제 출입구 확인은 남음','반경 수는 통학길에서 지나가는 시설 수가 아님','현장 시설 작동/밝기/횡단보도 연결 정확도 검증은 남음'],'checks':['홍은중앙로 접근 방향별 횡단보도·신호 연결 대조','홍제천 접근 방향의 보행 연결/건널목 현장 확인','학교 출입구 핀 확정 후 경로 후보 비교','비상벨 좌표·기준일·실제 경찰 연결 상태 대조','CCTV 설치 지점과 카메라 대수/촬영 범위 구분']}
 (OUT/'hongje-test-area.json').write_text(json.dumps(scenario,ensure_ascii=False,indent=2))
 (OUT/'hongje-facilities.geojson').write_text(json.dumps({'type':'FeatureCollection','features':features},ensure_ascii=False))
 report={'checkedAt':'2026-10-10','area':scenario,'layers':results,'alternativeFileQuality':alternative,'bellSpatialComparison':dict(matches),'comparisonLimits':['10/30m 위치 일치는 동일 시설/현재 상태 증거 아님','비상벨 2024년 대조 파일은 기존 최신 원본을 자동 대체하지 않음','CCTV 조도 자료는 2022년 측정 시점이며 현재 밝기/운영 시설 목록 아님','보안등 은평구 데이터는 서대문구 홍제초의 누락을 해결하지 못함']}
 (OUT/'hongje-quality-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));(ROOT/'docs/hongje-data-quality.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
 for r in results:print(r['layer'],r['within300m'],r['within500m'],r['within1000m'],r['baseDates'])
 print('대조',dict(matches));print('비상벨 파일',alternative[0]['rows'],alternative[0]['seoulMissingOrOutOfBounds'])
