"""스마트폴 보안등 기능을 미확보 구에 보완. 전수 보안등 목록으로 해석하지 않는다."""
import hashlib,json,re
from pathlib import Path
from audit_public_data_files import read,ROOT
from audit_signal_links import valid
SOURCE='SMART_POLE_LIGHT'
TARGETS={'서대문구','강남구'}
def prepare(rows):
 result=[];excluded=0;ids=set()
 for r in rows:
  if r.get('기관명') not in TARGETS or '보안등' not in r.get('탑재기능',''):continue
  try:lat=float(r['위도']);lng=float(r['경도'])
  except (KeyError,ValueError):excluded+=1;continue
  address=r.get('설치장소(주소)','');serial=r.get('연번','');ident='OA23001:'+serial
  if not valid(dict(lat=lat,lng=lng)) or r.get('수량')!='1' or not serial.isdigit() or r['기관명'] not in address or ident in ids:excluded+=1;continue
  ids.add(ident);label=re.search(r'\(([^)]+)\)',address)
  name='스마트폴 보안등'+(' ('+label.group(1)+')' if label else '')
  result.append(dict(kind='SECURITY_LIGHT',source=SOURCE,sourceId=ident,name=name[:100],lat=lat,lng=lng,district=r['기관명'],baseDate='2025-12-31'))
 return dict(version=1,scope='SMART_POLE_ONLY',districts=sorted({r['district'] for r in result}),facilities=result),excluded
if __name__=='__main__':
 source=ROOT/'무제 폴더/품질검토/2026-10-10/seoul-smart-poles-202512.xlsx';rows,_=read(source);candidate,excluded=prepare(rows)
 out=source.with_name('approved-smart-pole-lights.json');out.write_text(json.dumps(candidate,ensure_ascii=False));h=hashlib.sha256(out.read_bytes()).hexdigest()
 review=dict(approved=True,candidateSha256=h,sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),sourceUrl='https://data.seoul.go.kr/dataList/OA-23001/S/1/datasetView.do',checkedAt='2026-10-10',rows=len(candidate['facilities']),districts=candidate['districts'],excludedRows=excluded,limits=['2025년 12월 현황 · 날짜 칸은 월말 대표값','스마트폴 중 보안등 기능 포함 시설만 · 구 전체 목록 아님','기존 보안등 자료를 교체하지 않는 별도 원본','현재 점등 여부 확인 아님'])
 (ROOT/'docs/smart-pole-lights-review.json').write_text(json.dumps(review,ensure_ascii=False,indent=2));print(json.dumps(review,ensure_ascii=False))
