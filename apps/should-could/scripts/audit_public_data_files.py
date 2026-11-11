"""원본 파일 품질 점검. 표본/범위/날짜/중복을 집계하며 DB를 읽거나 쓰지 않는다."""
import collections,csv,datetime,hashlib,io,json,math,pathlib,zipfile,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1];OUT=ROOT/'무제 폴더/품질검토/2026-10-10'; TODAY=datetime.date(2026,10,10)
from coordinate_conversion import tm5186_to_wgs84 as tm
def xlsx(b):
 with zipfile.ZipFile(io.BytesIO(b)) as z:
  strings=[]
  if 'xl/sharedStrings.xml' in z.namelist():
   for s in ET.fromstring(z.read('xl/sharedStrings.xml')):strings.append(''.join(s.itertext()))
  tree=ET.fromstring(z.read('xl/worksheets/sheet1.xml'));rows=[]
  for row in tree.findall('.//{*}row'):
   d={}
   for c in row.findall('{*}c'):
    key=''.join(x for x in c.attrib['r'] if x.isalpha());v=c.find('{*}v');val=v.text if v is not None else ''.join(c.itertext())
    if c.attrib.get('t')=='s':val=strings[int(val)]
    d[key]=val
   rows.append(d)
  header=rows[0];return [{header.get(k,k):v for k,v in r.items()} for r in rows[1:]],'xlsx'
def read(f):
 b=f.read_bytes()
 if f.suffix=='.xlsx':return xlsx(b)
 if f.suffix=='.csv':
  for enc in ['utf-8-sig','cp949']:
   try:return list(csv.DictReader(io.StringIO(b.decode(enc)))),enc
   except UnicodeError:continue
 d=json.loads(b)
 if isinstance(d,list):return d,'json'
 if 'ep' in d:
  cols=d['ep']['column'];return [{cols[k]:v for k,v in r.items()} for r in d['ep']['list']],'provider-json'
 if 'records' in d:return d['records'],'json'
 if 'body' in d:return d['body'].get('items',{}).get('item',[]),'api-json'
 if 'items' in d:return d['items'].get('item',[]),'api-json'
 return [],'metadata-json'
def val(r,keys):
 for k in keys:
  if r.get(k) not in [None,'']:return str(r[k]).strip()
 return ''
def coord(r):
 la=val(r,['위도','WGS84위도','lat']);lo=val(r,['경도','WGS84경도','lng'])
 if la or lo:
  try:return float(la),float(lo)
  except ValueError:return None
 x=val(r,['X좌표','XCRD','x']);y=val(r,['Y좌표','YCRD','y'])
 try:
  if not x or not y:return None
  x,y=float(x),float(y)
  if abs(x)>1e6:return math.degrees(2*math.atan(math.exp(y/6378137))-math.pi/2),math.degrees(x/6378137)
  return tm(x,y)
 except (ValueError,OverflowError):return None
def audit(f):
 rows,enc=read(f);bad=missing=valid=0;dates=[];missingdate=0;districts=collections.Counter();points=collections.Counter(); exact=collections.Counter();seoul=0;seoulinvalid=0;box=0
 for r in rows:
  if not isinstance(r,dict):continue
  text=' '.join(val(r,[k]) for k in ['소재지도로명주소','소재지지번주소','관리기관명','자치구','시도명','주소','rn_adres','adres'])
  c=coord(r);is_seoul='서울' in text or str(r.get('ctprvn_cd',''))=='11' or ('서울시_' in f.name) or ('pedestrian-signal' in f.name) or f.name=='seoul-signal.csv'
  if c is not None and 37.40<=c[0]<=37.73 and 126.75<=c[1]<=127.20:box+=1
  if is_seoul:
   seoul+=1
   if not c or not all(map(math.isfinite,c)) or not (37.40<=c[0]<=37.73 and 126.75<=c[1]<=127.20):seoulinvalid+=1
  if not c:missing+=1
  elif not all(map(math.isfinite,c)) or not(-90<=c[0]<=90 and -180<=c[1]<=180) or c==(0,0):bad+=1
  else:
   valid+=1;points[(round(c[0],7),round(c[1],7))]+=1
  exact[json.dumps(r,sort_keys=True,ensure_ascii=False)]+=1
  date=val(r,['데이터기준일자','data_yr'])
  if date:dates.append(date)
  else:missingdate+=1
  district=val(r,['자치구','시군구명','GU_CD'])
  if not district:
   import re
   m=re.search(r'([가-힣]+구)',text);district=m.group(1) if m else '미확인'
  if is_seoul:districts[district]+=1
 return {'file':str(f.relative_to(ROOT)),'status':'empty-or-unavailable' if not rows else 'checked','sha256':hashlib.sha256(f.read_bytes()).hexdigest(),'rows':len(rows),'encoding':enc,'columns':list(rows[0]) if rows else [],'validCoordinateRows':valid,'missingCoordinateRows':missing,'invalidCoordinateRows':bad,'seoulCandidateRows':seoul,'seoulRectangleCoordinateRows':box,'seoulMissingOrOutOfBounds':seoulinvalid,'exactDuplicateRows':sum(n-1 for n in exact.values()),'coLocatedCoordinateRows':sum(n-1 for n in points.values()),'missingBaseDate':missingdate,'oldestBaseDate':min(dates) if dates else None,'newestBaseDate':max(dates) if dates else None,'seoulDistrictCounts':dict(districts),'downloadLimitSuspected':len(rows)==50000,'limits':['좌표 범위는 서울을 감싼 사각형이며 공식 행정경계/현장 정확도 검증이 아님','같은 좌표 시설이 실제 중복인지 판단하지 않음','기준일 없는 위치 원본은 내려받은 날짜로 대체하지 않음']}
def accidents():
 out=[]
 for f in sorted((OUT/'accidents').glob('*.json')):
  d=json.loads(f.read_text());items=d.get('items',{}).get('item',[]);bad=0;ids=collections.Counter();bounds=[]
  for r in items:
   ids[r.get('afos_fid')]+=1
   try:
    g=json.loads(r['geom_json']);assert g['type'] in ['Polygon','MultiPolygon']
    polys=g['coordinates'] if g['type']=='MultiPolygon' else [g['coordinates']]
    for poly in polys:
     for ring in poly:
      assert len(ring)>=4 and ring[0]==ring[-1]
      for x,y,*rest in ring:assert math.isfinite(x) and math.isfinite(y) and 126.75<=x<=127.20 and 37.40<=y<=37.73
   except (ValueError,KeyError,TypeError,AssertionError):bad+=1
  out.append({'file':f.name,'code':d.get('resultCode'),'totalCount':int(d.get('totalCount',0)),'received':len(items),'complete':int(d.get('totalCount',0))==len(items),'invalidGeometry':bad,'duplicateIdRows':sum(n-1 for n in ids.values()),'columns':list(items[0]) if items else []})
 return out
if __name__=='__main__':
 files=list((ROOT/'무제 폴더').glob('*.json'))+list((ROOT/'무제 폴더').glob('*.csv'))+list((ROOT/'무제 폴더').glob('*.xlsx'))
 files+=list(OUT.glob('*.csv'))+list(OUT.glob('*.xlsx'))+list(OUT.glob('*-fresh.json'))
 files+=list((OUT/'safety-houses').glob('*.json'))
 if (OUT/'eunpyeong-lights.json').exists():files.append(OUT/'eunpyeong-lights.json')
 results=[]
 for f in files:
  try:results.append(audit(f))
  except Exception as e:results.append({'file':str(f.relative_to(ROOT)),'error':type(e).__name__})
 report={'checkedAt':str(TODAY),'files':results,'accidents':accidents()}
 (OUT/'file-quality-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
 for r in results:print(pathlib.Path(r['file']).name,'rows',r.get('rows'),'서울',r.get('seoulCandidateRows'),'서울좌표문제',r.get('seoulMissingOrOutOfBounds'),r.get('error',''))
 print('accidents',json.dumps(report['accidents'],ensure_ascii=False))
 from report_public_data_review import assemble
 assemble()
