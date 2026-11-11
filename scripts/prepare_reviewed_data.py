"""검증 원본에서 적재 후보 생성. DB 미변경, 문제 행 격리, 원본 SHA 기록."""
import collections, hashlib, json, math, re
from audit_public_data_files import ROOT, OUT, read, coord
from audit_hongje_area import latest, distance

TARGET = OUT / 'approved-import.json'
DISTRICTS = {'110':'종로구','140':'중구','170':'용산구','200':'성동구','210':'광진구','230':'동대문구','260':'중랑구','290':'성북구','300':'강북구','320':'도봉구','350':'노원구','380':'은평구','410':'서대문구','440':'마포구','470':'양천구','500':'강서구','530':'구로구','540':'금천구','560':'영등포구','590':'동작구','620':'관악구','650':'서초구','680':'강남구','710':'송파구','740':'강동구'}

def valid(c):
    return c and all(map(math.isfinite,c)) and 37.4 <= c[0] <= 37.73 and 126.75 <= c[1] <= 127.2

def prepare():
    facilities=[]; risks=[]; rejected=[]; sources=[]; seen=set(); stats=collections.Counter()
    def load(path):
        sources.append({'file':str(path.relative_to(ROOT)), 'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
        return read(path)[0]
    def add(kind, source, ident, c, name='', district='', date=None, signal=None):
        if not valid(c) or not ident or len(str(ident))>40 or not district:
            rejected.append({'source':source,'sourceId':str(ident),'reason':'좌표/관리번호/자치구 미확인'});return
        key=(source,str(ident))
        if key in seen: stats['duplicateIdentity']+=1;return
        seen.add(key)
        facilities.append(dict(kind=kind,source=source,sourceId=str(ident),lat=c[0],lng=c[1],name=name[:100],district=district,baseDate=date,signalStatus=signal))

    # 보행등은 좌표 위치 단위로 묶는다. 신호기 대수/시설 ID라고 해석하지 않는다.
    signals=load(OUT/'pedestrian-signal-2026.xlsx'); cells=collections.defaultdict(list)
    for r in signals:
        c=coord(r)
        if valid(c):
            ident=hashlib.sha256(('%.8f|%.8f'%c).encode()).hexdigest()[:32]
            add('PEDESTRIAN_SIGNAL','SEOUL_PED_SIGNAL',ident,c,district=r.get('자치구',''))
            cells[(math.floor(c[0]/.001),math.floor(c[1]/.001))].append(c)
    def signal_near(c):
        y,x=math.floor(c[0]/.001),math.floor(c[1]/.001)
        return any(distance(c,p)<=10 for dy in [-1,0,1] for dx in [-1,0,1] for p in cells[(y+dy,x+dx)])
    cross_path=ROOT/'무제 폴더/서울시_횡단보도_trafficSafetyA004AInfo.json'
    raw=load(cross_path); downloaded=json.loads(cross_path.read_text()).get('downloaded')
    for r in latest(raw,'CRSWK_MNG_NO1'):
        if r.get('STTS')!='001':continue
        c=coord(r); district=DISTRICTS.get(str(r.get('GU_CD')),'')
        # 구 코드가 없으면 추측하지 않고 격리한다.
        add('CROSSWALK','SEOUL_CROSSWALK',r.get('CRSWK_MNG_NO1'),c,district=district,date=downloaded,signal='ESTIMATED' if valid(c) and signal_near(c) else 'UNKNOWN')
    for r in load(OUT/'schools-fresh.json'):
        address=str(r.get('소재지도로명주소',''))
        if not address.startswith('서울') or r.get('운영상태')!='운영':continue
        m=re.search(r'([가-힣]+구)',address)
        add('SCHOOL','SCHOOL_STD',r.get('학교ID'),coord(r),r.get('학교명',''),m.group(1) if m else '',r.get('데이터기준일자'))
    lights=load(OUT/'eunpyeong-lights.json'); exact=set()
    for r in lights:
        canonical=json.dumps(r,sort_keys=True,ensure_ascii=False)
        if canonical in exact:stats['securityLightExactDuplicates']+=1;continue
        exact.add(canonical)
        c=coord(r)
        # 관리번호가 없는 원본: 주소·설치형태·좌표로 안정적인 위치 식별자 생성.
        ident=hashlib.sha256(json.dumps([r.get('소재지도로명주소'),r.get('설치형태'),c],ensure_ascii=False).encode()).hexdigest()[:32]
        m=re.search(r'([가-힣0-9]+(?:로|길))\s*\d',str(r.get('소재지도로명주소','')))
        add('SECURITY_LIGHT','SECURITY_LIGHT_STD',ident,c,m.group(1) if m else '', '은평구',r.get('데이터기준일자'))
    kinds={'child':'CHILD_PEDESTRIAN_ACCIDENT','schoolzone':'CHILD_ACCIDENT_SCHOOLZONE','elderly':'ELDERLY_PEDESTRIAN_ACCIDENT'}
    risk_ids=set()
    for prefix,kind in kinds.items():
        path=OUT/'accidents'/f'{prefix}-2025.json';d=json.loads(path.read_text());load(path)
        rows=d['items']['item']
        if d.get('resultCode')!='00' or int(d['totalCount'])!=len(rows) or not rows:raise ValueError('사고 파일 불완전')
        for r in rows:
            g=json.loads(r['geom_json']);polys=g['coordinates'] if g['type']=='MultiPolygon' else [g['coordinates']]
            if g['type'] not in ['Polygon','MultiPolygon']:raise ValueError('잘못된 도형')
            for poly in polys:
                for ring in poly:
                    if len(ring)<4 or ring[0]!=ring[-1] or any(not valid((p[1],p[0])) for p in ring):raise ValueError('잘못된 사고 영역')
            ident=str(r['afos_fid'])
            if ident in risk_ids:raise ValueError('사고 원본 ID 중복')
            risk_ids.add(ident);address=r.get('spot_nm','');m=re.search(r'([가-힣]+구)',address)
            risks.append(dict(kind=kind,name=address,source='B552061',sourceId=ident,baseYear=2025,district=m.group(1) if m else '',geometry=g))
    report=dict(version=1,checkedAt='2026-10-10',sources=sources,facilities=facilities,risks=risks)
    TARGET.write_text(json.dumps(report,ensure_ascii=False))
    summary={'facilityCounts':dict(collections.Counter(r['kind'] for r in facilities)),'signalCounts':dict(collections.Counter(r['signalStatus'] for r in facilities if r['kind']=='CROSSWALK')),'riskCounts':dict(collections.Counter(r['kind'] for r in risks)),'rejectedCounts':dict(collections.Counter(r['source'] for r in rejected)), 'deduplication':dict(stats),'sources':sources,'artifactSha256':hashlib.sha256(TARGET.read_bytes()).hexdigest(),'limits':['보행등은 위치 단위이며 실제 건널목 연결/작동 미검증','보안등은 은평구만 갱신','구 코드 없는 횡단보도는 행정구역 추측 없이 격리','CCTV/비상벨/보호구역/지킴이집은 이번 갱신 대상 아님']}
    (OUT/'rejected-import-rows.json').write_text(json.dumps(rejected,ensure_ascii=False,indent=2))
    (ROOT/'docs/reviewed-import-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))
    print(json.dumps({k:v for k,v in summary.items() if k not in ['sources','limits']},ensure_ascii=False,indent=2))

if __name__=='__main__':prepare()
