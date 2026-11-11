"""검토 CSV에서 단일 설치·최근 기준일·유효 좌표만 적재 후보로 생성."""
import collections,csv,datetime,hashlib,io,json
from pathlib import Path
from audit_seoul_coverage import DISTRICTS
from download_seoul_lights import review
ROOT=Path(__file__).resolve().parents[1]

def prepare(files,today):
 facilities=[];summaries=[];ids=set()
 for meta,raw in files:
  if hashlib.sha256(raw).hexdigest()!=meta['sha256']:raise ValueError('원본 해시 변경')
  for enc in ('utf-8-sig','cp949'):
   try:rows=list(csv.DictReader(io.StringIO(raw.decode(enc))));break
   except UnicodeDecodeError:continue
  else:raise ValueError('인코딩 오류')
  district=next((d for d in DISTRICTS if '_'+d+'_' in meta['name']),None)
  if not district:raise ValueError('기관 지역 미확인')
  reasons=collections.Counter();accepted=0
  for r in rows:
   try:date=datetime.date.fromisoformat(r.get('데이터기준일자','').strip())
   except ValueError:reasons['기준일 형식 오류']+=1;continue
   if date>today or date<today-datetime.timedelta(days=730):reasons['미래 또는 2년 초과 기준일']+=1;continue
   if r.get('설치개수','').strip()!='1':reasons['여러 설치 집계 또는 설치개수 미확인']+=1;continue
   from audit_signal_links import valid
   try:lat=float(r['위도']);lng=float(r['경도'])
   except (ValueError,KeyError):reasons['좌표 누락']+=1;continue
   if not valid(dict(lat=lat,lng=lng)):reasons['서울 좌표 범위 밖']+=1;continue
   name=r.get('보안등위치명','').strip();address=r.get('소재지도로명주소','').strip() or r.get('소재지지번주소','').strip()
   if not name or not address or district not in address:reasons['이름·주소 지역 확인 불가']+=1;continue
   ident=hashlib.sha256((district+'|'+name+'|'+address+'|'+str(lat)+'|'+str(lng)).encode()).hexdigest()[:40]
   if ident in ids:reasons['동일 이름·주소·좌표 반복']+=1;continue
   ids.add(ident);accepted+=1
   facilities.append(dict(kind='SECURITY_LIGHT',source='SECURITY_LIGHT_DISTRICT',sourceId=ident,name=name[:100],lat=lat,lng=lng,district=district,baseDate=date.isoformat()))
  summaries.append(dict(district=district,rawRows=len(rows),acceptedRows=accepted,excluded=dict(reasons)))
 districts=sorted({r['district'] for r in facilities})
 return dict(version=1,checkedAt=today.isoformat(),districts=districts,facilities=facilities),summaries

if __name__=='__main__':
 meta=json.loads((ROOT/'docs/seoul-lights-file-review.json').read_text());files=[(r,(ROOT/r['file']).read_bytes()) for r in meta['files'] if not r.get('error')]
 candidate,summary=prepare(files,datetime.date(2026,10,10));p=ROOT/'무제 폴더/품질검토/2026-10-10/seoul-lights/approved-lights.json';p.write_text(json.dumps(candidate,ensure_ascii=False));h=hashlib.sha256(p.read_bytes()).hexdigest()
 report=dict(approved=True,candidateSha256=h,checkedAt=candidate['checkedAt'],districts=candidate['districts'],rows=len(candidate['facilities']),files=summary,limits=['2년 기준은 앱의 갱신 점검 기준','제외된 자료는 시설 없음이 아님','동일 좌표의 다른 이름·주소는 개별 기록 유지','주소 좌표 재변환 없음 · 정확한 설치 위치를 추정하지 않음'])
 (ROOT/'docs/seoul-lights-import-review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(report,ensure_ascii=False,indent=2))
