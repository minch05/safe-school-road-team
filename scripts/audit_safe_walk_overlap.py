"""이미 승인해 받은 공개 원본을 기존 공공시설과 대조. DB 쓰기 없음."""
import json, pathlib, subprocess, math, re, collections, datetime, zipfile, struct
root=pathlib.Path(__file__).resolve().parents[1]
rows=json.loads((root/'무제 폴더/품질검토/2026-10-13/safe-walk-parsed.json').read_text())
raw=subprocess.run(['mysql','-h','127.0.0.1','-N','-B','-u','root','safe_school_road','-e',"SELECT kind,lat,lng FROM facility WHERE kind IN ('EMERGENCY_BELL','CCTV','SECURITY_LIGHT')"],check=True,capture_output=True,text=True).stdout
cells=collections.defaultdict(list)
for line in raw.splitlines():
 kind,lat,lng=line.split('\t');lat=float(lat);lng=float(lng);cells[(kind,math.floor(lat/.001),math.floor(lng/.001))].append((lat,lng))
def distance(a,b):
 p,q=map(math.radians,[a[0],b[0]]);dlat=q-p;dlng=math.radians(b[1]-a[1]);return 6371000*2*math.asin(min(1,math.sqrt(math.sin(dlat/2)**2+math.cos(p)*math.cos(q)*math.sin(dlng/2)**2)))
by=collections.defaultdict(collections.Counter);mapping={'301':'EMERGENCY_BELL','302':'CCTV','305':'SECURITY_LIGHT'}
candidates=[]
for index,r in enumerate(rows):
 if r['시설코드'] not in mapping:continue
 group=r['시군구명'].removeprefix('서울특별시 ')+':'+mapping[r['시설코드']];s=by[group];s['rows']+=1
 nums=re.findall(r'-?\d+(?:\.\d+)?',r['포인트 WKT'])
 if len(nums)!=2:s['bad_coordinate']+=1;continue
 lng,lat=map(float,nums)
 if not(126.7<=lng<=127.3 and 37.4<=lat<=37.8):s['bad_coordinate']+=1;continue
 kind=mapping[r['시설코드']];x,y=math.floor(lat/.001),math.floor(lng/.001)
 found=any(distance((lat,lng),point)<=15 for dx in [-1,0,1] for dy in [-1,0,1] for point in cells[(kind,x+dx,y+dy)])
 s['near_existing_15m' if found else 'not_near_existing_15m']+=1
 try:date=datetime.datetime.strptime(r['데이터 기'],'%Y%m%d').date()
 except ValueError:s['bad_date']+=1;continue
 if kind=='EMERGENCY_BELL':
  if date<datetime.date(2022,10,13):s['older_than_four_years']+=1
  elif not found:
   s['nonoverlap_within_four_years']+=1;candidates.append((index,r,lat,lng))
 else:s['historical_only_2022']+=1
report={'checkedAt':'2026-10-13','databaseChanged':False,'distanceRuleMeters':15,'comparisonMeaning':'근접 후보이며 동일 시설 확정이 아님','groups':{k:dict(v) for k,v in sorted(by.items())},'limits':['원본 내 근접 중복·현재 작동은 미확인','4년 이내라는 이유만으로 자동 승인하지 않음','CCTV·보안등은 오래된 참고 자료로 취급']}
(root/'docs/safe-walk-overlap-20261013.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
for k,v in report['groups'].items():
 if k.endswith('EMERGENCY_BELL'):print(k,v)

# 원본 SHP 좌표와 속성 WKT를 별도로 대조한다. ZIP은 추출하지 않고 읽는다.
archive=zipfile.ZipFile(root/'무제 폴더/품질검토/2026-10-13/safe-walk-facilities.zip')
data=archive.read(next(n for n in archive.namelist() if n.endswith('.shp')))
points=[];offset=100
while offset<len(data):
 _,words=struct.unpack_from('>ii',data,offset);offset+=8;content=data[offset:offset+words*2];offset+=words*2
 if len(content)!=20 or struct.unpack_from('<i',content)[0]!=1:raise ValueError('예상하지 않은 SHP Point 형식')
 points.append(struct.unpack_from('<dd',content,4))
if len(points)!=len(rows):raise ValueError('SHP/DBF 개수 불일치')
details=[];all_bells=[]
for index,r in enumerate(rows):
 if r['시설코드']=='301':all_bells.append((index,points[index][1],points[index][0]))
for index,r,lat,lng in candidates:
 issues=[]
 if distance((lat,lng),(points[index][1],points[index][0]))>.01:issues.append('SHP_WKT_COORDINATE_MISMATCH')
 if not re.fullmatch(r'\d{10}_\d+_P\d+',r['시설물 ID']):issues.append('ID_FORMAT_NEEDS_REVIEW')
 try:
  if float(r['설치대수'])!=1:issues.append('COUNT_NOT_ONE')
 except ValueError:issues.append('INVALID_COUNT')
 neighbors=[other for other,a,b in all_bells if other!=index and distance((lat,lng),(a,b))<=15]
 if neighbors:issues.append('NEAR_OTHER_SOURCE_BELL_15M')
 details.append({'id':r['시설물 ID'],'district':r['시군구명'],'surveyDate':r['데이터 기'],'issues':issues})
quality={'checkedAt':'2026-10-13','databaseChanged':False,'preliminaryCandidates':len(details),'issueCounts':dict(collections.Counter(x for r in details for x in r['issues'])),'cleanByDistrict':dict(collections.Counter(r['district'] for r in details if not r['issues'])),'cleanCount':sum(not r['issues'] for r in details),'limits':['점검정상/현재작동/경찰연결은 원본으로 입증하지 못함','구코드 대조는 행정경계 검증 아님','날짜 허용 정책은 설치·작동 검증과 별개']}
(root/'docs/safe-walk-candidate-quality-20261013.json').write_text(json.dumps(quality,ensure_ascii=False,indent=2))
(root/'무제 폴더/품질검토/2026-10-13/bell-candidate-check.json').write_text(json.dumps(details,ensure_ascii=False,indent=2))
print(json.dumps(quality,ensure_ascii=False))
