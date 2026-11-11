"""원본 점검 집계에 공개 범례/PNG/페이지 완결성 증거를 붙인다."""
import collections,hashlib,json,pathlib,struct,zlib
ROOT=pathlib.Path(__file__).resolve().parents[1];OUT=ROOT/'무제 폴더/품질검토/2026-10-10'
def assemble():
 d=json.loads((OUT/'file-quality-report.json').read_text())
 raw=[];expected=set()
 for f in (OUT/'safety-houses').glob('*.json'):
  r=json.loads(f.read_text());expected.add(r['body']['totalCount']);raw+=r['body']['items']['item']
 counts=collections.Counter(r.get('objt_id') for r in raw)
 d['safetyHouseCompleteness']={'expectedTotal':list(expected),'received':len(raw),'complete':len(expected)==1 and len(raw)==next(iter(expected)),'duplicateIdRows':sum(n-1 for n in counts.values()),'seoulRows':sum((str(r.get('ctprvn_cd'))=='11' or '서울' in str(r.get('rn_adres',''))+str(r.get('adres',''))) and str(r.get('fclty_cd'))=='504040' for r in raw)}
 legend=json.loads((OUT/'crime-legend.json').read_text());items=legend['body']['items']['item'];png=(OUT/'crime-public-sample.png').read_bytes();offset=8;crc=True;last=None
 while offset+12<=len(png):
  n=struct.unpack_from('>I',png,offset)[0];kind=png[offset+4:offset+8];body=png[offset+8:offset+8+n];stored=struct.unpack_from('>I',png,offset+8+n)[0];crc &= zlib.crc32(kind+body)&0xffffffff==stored;last=kind;offset+=n+12
 d['crime']={'format':'WMS PNG + 공식 범례 JSON','width':struct.unpack_from('>I',png,16)[0],'height':struct.unpack_from('>I',png,20)[0],'pngCRCValid':crc,'completePNG':png[:8]==b'\x89PNG\r\n\x1a\n' and offset==len(png) and last==b'IEND','grades':sorted(int(r['LGD_NO']) for r in items),'uniqueColors':len({r['IMAGE'] for r in items}),'provider':items[0]['PROVIDE'],'explanation':items[0]['HELP'],'warning':items[0]['WARN'],'baseYear':None,'limits':['정확한 기준 연도 없음','한 구간 공개 표본이며 서울 전체 검증 아님','원시 사건 좌표/발생 확률 데이터 아님','공개 표본은 검토용 보존. 저장/재배포 조건 별도 확인']}
 import zipfile
 with zipfile.ZipFile(OUT/'seoul-crosswalk-files.zip') as z:
  nested=z.read(next(n for n in z.namelist() if n.startswith('A004') and n.endswith('.zip')))
 (OUT/'seoul-crosswalk-shapes.zip').write_bytes(nested)
 with zipfile.ZipFile(OUT/'seoul-crosswalk-shapes.zip') as z:
  b=z.read('A004_A.shp');prj=z.read('A004_A.prj').decode();i=100;n=0
  while i+8<=len(b):_,size=struct.unpack_from('>2i',b,i);i+=8+size*2;n+=1
 d['crosswalkShape']={'file':'seoul-crosswalk-shapes.zip','records':n,'shapeType':struct.unpack_from('<i',b,32)[0],'projectionDefinition':prj,'completeBinaryRecords':i==len(b),'limits':['첨부 파일 수집일과 원본 기준일은 다름','자기교차/현장 정확도/최신 시설과의 매칭 미검증']}
 for r in d['files']:
  if r.get('rows')==0:r['status']='empty-or-unavailable'
  if r.get('rows')==50000:r['nationalCompleteness']='미확인: 포털 5만 행 내보내기 제한'
 d['blockedSources']=[{'source':'서울시 최신 시설 API','reason':'HTTPS 8088 실패; 평문 HTTP 인증키 전송 자동 승인 검토 거부','alternative':'공식 CSV/ZIP/XLSX 파일 대조; 최신 API와 동등성 미확인'},{'source':'행정안전부 CCTV/비상벨 최신 통합 파일','reason':'공식 제공처 및 브라우저 403','alternative':'기존 파일 전 행 검사. 구 표준자료가 더 오래되고 범위 좁아 교체 부적합'}]
 d['downloads']=[{'file':str(f.relative_to(OUT)),'bytes':f.stat().st_size,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()} for f in OUT.rglob('*') if f.is_file() and f.suffix in ['.json','.csv','.xlsx','.zip','.png','.pdf'] and f.name!='file-quality-report.json']
 (OUT/'file-quality-report.json').write_text(json.dumps(d,ensure_ascii=False,indent=2));(ROOT/'docs/public-data-file-audit.json').write_text(json.dumps({k:v for k,v in d.items() if k!='downloads'},ensure_ascii=False,indent=2))
if __name__=='__main__':assemble()
