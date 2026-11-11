"""주소만 있는 보안등 원본의 소량 지오코딩 가능성 점검. DB 적재·좌표 저장 없음."""
import csv,io,json,re,urllib.parse,urllib.request
from collections import Counter
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def read_rows(path):
    raw=path.read_bytes()
    for encoding in ('utf-8-sig','cp949'):
        try:return list(csv.DictReader(io.StringIO(raw.decode(encoding))))
        except UnicodeDecodeError:pass
    raise ValueError('CSV 인코딩 미확인')

def address(row):
    return (row.get('소재지도로명주소','').strip() or row.get('소재지지번주소','').strip())

def inventory(rows):
    addresses=[address(r) for r in rows if address(r)]
    counts=Counter(addresses)
    return {'rows':len(rows),'withAddress':len(addresses),'uniqueAddresses':len(counts),
            'rowsSharingAddress':sum(n for n in counts.values() if n>1),
            'largestAddressGroup':max(counts.values(),default=0)}

def sample(rows,n=10):
    a=list(dict.fromkeys(address(r) for r in rows if address(r)))
    return [a[i*len(a)//min(n,len(a))] for i in range(min(n,len(a)))] if a else []

def classify(response,district):
    a=response.get('addresses',[])
    if not a:return '검색 결과 없음'
    if len(a)!=1:return '복수 후보'
    x=a[0]
    if district not in (x.get('roadAddress','')+' '+x.get('jibunAddress','')):return '다른 구 후보'
    try:
        lat,lng=float(x['y']),float(x['x'])
        if not (37.4<=lat<=37.73 and 126.75<=lng<=127.2):return '서울 범위 밖'
    except (ValueError,KeyError,TypeError):return '좌표 오류'
    return '구 일치 단일 주소 후보 (시설 위치 미검증)'

if __name__=='__main__':
    env={}
    for line in (ROOT/'.env').read_text().splitlines():
        m=re.match(r'^([A-Z_]+)=(.*)$',line.strip())
        if m:env[m[1]]=m[2].strip().strip('\"\'')
    if not env.get('NAVER_MAPS_CLIENT_ID') or not env.get('NAVER_MAPS_CLIENT_SECRET'):raise SystemExit('지도 인증 정보 미설정')
    out=[]
    for district in ('용산구','마포구'):
        p=ROOT/'무제 폴더/품질검토/2026-10-10/seoul-lights'/('서울특별시_'+district+'_보안등정보.csv')
        rows=read_rows(p);stats=inventory(rows);results=Counter()
        for q in sample(rows):
            # 공개 시설 주소만 사용. API 키 및 응답 좌표를 출력·저장하지 않는다.
            u='https://maps.apigw.ntruss.com/map-geocode/v2/geocode?count=5&query='+urllib.parse.quote(q)
            req=urllib.request.Request(u,headers={'X-NCP-APIGW-API-KEY-ID':env['NAVER_MAPS_CLIENT_ID'],'X-NCP-APIGW-API-KEY':env['NAVER_MAPS_CLIENT_SECRET']})
            try:
                with urllib.request.urlopen(req,timeout=20) as r:results[classify(json.load(r),district)]+=1
            except Exception:results['호출 실패']+=1;break
        out.append({'district':district,**stats,'sampleResults':dict(results)})
    report={'checkedAt':'2026-10-10','districts':out,'decision':'DB 미적재. 주소 검색 성공은 보안등 실제 위치 검증이 아님.',
            'limits':['동일 주소에 여러 보안등이 있어도 한 건물 좌표로 겹칠 수 있음','주소 중심점을 정확한 보안등 위치나 경로 주변 시설 수로 쓰지 않음','표본 10개씩, 전수 좌표 확보·현장 검증 아님','응답 좌표·키 저장 없음']}
    (ROOT/'docs/address-only-lights-audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False,indent=2))
