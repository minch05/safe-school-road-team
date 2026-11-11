"""공식 보안등 표준자료의 서울 기관별 CSV 확보. API 키·사용자 데이터 불필요."""
import concurrent.futures,csv,datetime,hashlib,html,io,json,math,re,urllib.parse,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'무제 폴더/품질검토/2026-10-10/seoul-lights'
def request(path,params=None):
 data=urllib.parse.urlencode(params).encode() if params else None
 with urllib.request.urlopen(urllib.request.Request('https://www.data.go.kr'+path,data=data),timeout=45) as r:return r.read()
def review(raw):
 for encoding in ('utf-8-sig','cp949'):
  try:rows=list(csv.DictReader(io.StringIO(raw.decode(encoding))));break
  except UnicodeDecodeError:continue
 else:raise ValueError('unknown encoding')
 if not rows or '데이터기준일자' not in rows[0]:raise ValueError('not standard CSV')
 valid=missing=outside=0;coordinates=[];dates={};providers={}
 for row in rows:
  date=row.get('데이터기준일자','').strip();dates[date]=dates.get(date,0)+1
  provider=row.get('관리기관명','').strip();providers[provider]=providers.get(provider,0)+1
  try:
   lat=float(row['위도']);lng=float(row['경도'])
   if not math.isfinite(lat) or not math.isfinite(lng) or not 37.4<=lat<=37.73 or not 126.75<=lng<=127.2:outside+=1
   else:valid+=1;coordinates.append((lat,lng))
  except (ValueError,KeyError,TypeError):missing+=1
 return {'rows':len(rows),'validSeoulCoordinates':valid,'missingCoordinates':missing,'outsideSeoulCoordinates':outside,'coLocatedExtraRows':len(coordinates)-len(set(coordinates)),'baseDates':dates,'providers':providers}
def download(item):
 pk,name=item
 try:
  detail=request('/tcs/dss/selectDpkDetailInfo.do',{'publicDataDetailPk':pk}).decode()
  matches=list(set(re.findall(r"fn_fileDataDown\('15017320',\s*'[^']+',\s*'(FILE_[^']+)',\s*'(\d+)',\s*'\.?csv'\)",detail)))
  if len(matches)!=1:raise ValueError('ambiguous CSV attachment')
  fileid,sn=matches[0];url='/cmm/cmm/fileDownload.do?'+urllib.parse.urlencode({'atchFileId':fileid,'fileDetailSn':sn,'insertDataPrcus':'N'})
  raw=request(url);result=review(raw);filename=name.replace('/','_')+'.csv';(OUT/filename).write_bytes(raw)
  return dict(name=name,publicDataDetailPk=pk,downloadUrl='https://www.data.go.kr'+url,file=str(OUT.relative_to(ROOT)/filename),sha256=hashlib.sha256(raw).hexdigest(),**result)
 except Exception as e:return dict(name=name,publicDataDetailPk=pk,error=type(e).__name__)
if __name__=='__main__':
 OUT.mkdir(parents=True,exist_ok=True);items={}
 for page in range(1,6):
  text=request('/tcs/dss/stdFileList.do',{'publicDataPk':'15017320','searchKeyword2':'서울','pageIndex':page}).decode()
  for pk,body in re.findall(r'data-public-pk="([^"]+)"[^>]*>(.*?)</a>',text,re.S):
   name=html.unescape(re.sub('<[^>]+>','',body)).replace('파일데이터','').strip();items[pk]=name
 with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:results=list(pool.map(download,items.items()))
 report={'checkedAt':datetime.date.today().isoformat(),'source':'https://www.data.go.kr/data/15017320/standard.do','files':results,'limits':['포털 등록일은 원본 기준일이 아님','같은 좌표 반복은 동일 시설 확정이 아님','좌표 검사는 현재 점등 상태·전수 수집을 확인하지 않음','검토만 수행 · DB 변경 없음']}
 (ROOT/'docs/seoul-lights-file-review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
 print(json.dumps([dict(name=r['name'],rows=r.get('rows'),valid=r.get('validSeoulCoordinates'),dates=r.get('baseDates'),error=r.get('error')) for r in results],ensure_ascii=False,indent=2))
