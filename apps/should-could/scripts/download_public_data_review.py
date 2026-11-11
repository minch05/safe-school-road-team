"""공식 공공데이터 원본을 검토용 원본 폴더/에 저장. DB 변경/키 출력 없음."""
import concurrent.futures, datetime, hashlib, json, pathlib, urllib.request, urllib.parse
ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'무제 폴더/품질검토/2026-10-10'
OUT.mkdir(parents=True,exist_ok=True)
ENV={}
for line in (ROOT/'.env').read_text().splitlines():
 if line.strip() and not line.lstrip().startswith('#') and '=' in line:
  k,v=line.split('=',1);ENV[k.strip()]=v.strip().strip('\"\'')
manifest=[]
def get(name,url,params=None,method='GET'):
 safe=url.split('?')[0]
 if params:
  encoded=urllib.parse.urlencode(params)
  if method=='GET':url+='?'+encoded; data=None
  else:data=encoded.encode()
 else:data=None
 try:
  req=urllib.request.Request(url,data=data,method=method)
  with urllib.request.urlopen(req,timeout=40) as r: b=r.read();ct=r.headers.get('Content-Type','')
  (OUT/name).parent.mkdir(parents=True,exist_ok=True);(OUT/name).write_bytes(b)
  rec={'file':name,'source':safe,'bytes':len(b),'contentType':ct,'sha256':hashlib.sha256(b).hexdigest(),'downloadedAt':datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).isoformat()}
  manifest.append(rec);print(name,len(b),flush=True)
  return b
 except Exception as e:
  manifest.append({'file':name,'source':safe,'error':type(e).__name__});print(name,type(e).__name__,flush=True)

def main():
 get('eunpyeong-lights.json','https://www.ep.go.kr/wt/wt50/getDownloadFile.do',{'data_meta_id':184,'stdr_de':'2026-02','type':'json'})
 get('pedestrian-signal-2023.csv','https://www.data.go.kr/cmm/cmm/fileDownload.do',{'atchFileId':'FILE_000000002822202','fileDetailSn':1,'insertDataPrcus':'N'})
 get('eunpyeong-link.json','https://www.data.go.kr/tcs/dss/selectLinkUrl.do',{'publicDataPk':'15128038'})
 for name,inf,seq in [('seoul-crosswalk-files.zip','OA-15554','10'),('seoul-signal.csv','OA-15546','7'),('seoul-signal-definition.pdf','OA-15546','6'),('pedestrian-signal-2026.xlsx','OA-22355','2')]:
  get(name,'https://datafile.seoul.go.kr/bigfile/iot/inf/nio_download.do',{'infId':inf,'infSeq':'1' if inf=='OA-22355' else '3','seq':seq},'POST')
 key=ENV.get('DATA_GO_KR_SERVICE_KEY','')
 if key:
  services={'schoolzone':'schoolzoneChild/getRestSchoolzoneChild','child':'frequentzoneChild/getRestFrequentzoneChild','elderly':'frequentzoneOldman/getRestFrequentzoneOldman'}
  for name,path in services.items():
   for year in [2023,2024,2025]:
    get(f'accidents/{name}-{year}.json','https://apis.data.go.kr/B552061/'+path,{'ServiceKey':urllib.parse.unquote(key),'searchYearCd':year,'siDo':'11','guGun':'','type':'json','numOfRows':100,'pageNo':1})
 key=ENV.get('SAFEMAP_API_KEY','')
 if key:
  get('crime-legend.json','https://www.safemap.go.kr/openapi2/lgdInfo',{'serviceKey':key,'intId':'IF_0087','layer':'A2SM_CRMNLHSPOT_TOT','style':'A2SM_CrmnlHspot_Tot_Tot'})
  get('crime-public-sample.png','https://www.safemap.go.kr/openapi2/IF_0087_WMS',{'serviceKey':key,'srs':'EPSG:4326','bbox':'127.07,37.57,127.10,37.60','format':'image/png','width':512,'height':512,'transparent':'TRUE','layers':'A2SM_CRMNLHSPOT_TOT','styles':'A2SM_CrmnlHspot_Tot_Tot'})
  # 공개 시설 원본. 전국 페이지를 모두 수집하여 페이지 완결성 확인.
  first=get('safety-houses/page-1.json','https://www.safemap.go.kr/openapi2/IF_0055',{'serviceKey':key,'numOfRows':1000,'pageNo':1,'returnType':'json'})
  try:
   total=int(json.loads(first)['body']['totalCount'])
   if 0<total<=200000:
    for page in range(2,(total+999)//1000+1):
     get(f'safety-houses/page-{page}.json','https://www.safemap.go.kr/openapi2/IF_0055',{'serviceKey':key,'numOfRows':1000,'pageNo':page,'returnType':'json'})
  except (TypeError,ValueError,KeyError): pass
 (OUT/'download-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
