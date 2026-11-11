"""포털 다운로드 UI의 공개 GET 방식으로 전 페이지 저장. 키/DB 불필요."""
import concurrent.futures,hashlib,json,pathlib,urllib.parse,urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[1];OUT=ROOT/'무제 폴더/품질검토/2026-10-10';OUT.mkdir(parents=True,exist_ok=True)
SETS={'school-zones':15012891,'security-lights':15017320,'schools':15021148,'crosswalk-standard':15028201,'cctv-standard':15013094,'emergency-bells':15028206}
def fetch(url,params):
 with urllib.request.urlopen(url+'?'+urllib.parse.urlencode(params,doseq=True),timeout=40) as r:return json.load(r)
def download(pair):
 name,pk=pair
 try:
  h=fetch('https://www.data.go.kr/download/columList.json',{'pk':pk,'ext':'JSON'});(OUT/(name+'-header.json')).write_text(json.dumps(h,ensure_ascii=False,indent=2))
  if 'tableVO' not in h:raise ValueError('no metadata')
  total=int(h['totalCount']);table=h['tableVO'];columns=h['columList'];rows=[];counts=[]
  for page in range(1,(total+9999)//10000+1):
   data=fetch('https://www.data.go.kr/download/standard.json',{'publicDataPk':pk,'colNmList':table['colNmList'],'totalCount':total,'svcTableNm':table['svcTableNm'],'perPage':10000,'page':page})
   if not isinstance(data,list):raise ValueError('not row list')
   counts.append(len(data));rows.extend({c['columNm']:r.get(c['columCode'],r.get(c['columCode'].lower(),'')) for c in columns} for r in data)
   print(name,page,len(data),flush=True)
   if not data:break
  out={'source':f'https://www.data.go.kr/data/{pk}/standard.do','downloaded':'2026-10-10','expectedTotal':total,'pageCounts':counts,'completePortalExport':len(rows)==total,'nationalCompleteness':'미확인 (5만 행 제한)' if total==50000 else '포털 전체 건수 일치','records':rows}
  (OUT/(name+'-fresh.json')).write_text(json.dumps(out,ensure_ascii=False));return {'name':name,'total':total,'received':len(rows),'completePortalExport':len(rows)==total,'nationalCompleteness':'미확인 (5만 행 제한)' if total==50000 else '포털 전체 건수 일치'}
 except Exception as e:return {'name':name,'error':type(e).__name__}
if __name__=='__main__':
 with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:results=list(pool.map(download,SETS.items()))
 (OUT/'standard-download-result.json').write_text(json.dumps(results,ensure_ascii=False,indent=2));print(results)
