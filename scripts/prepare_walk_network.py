"""서울 OA-21208 CSV -> 저장 가능한 공공 원본 기반 갈림길. 외부 API/회원 자료 없음."""
import argparse, csv, hashlib, json, math
from collections import defaultdict
from pathlib import Path

SOURCE_URL = 'https://data.seoul.go.kr/dataList/OA-21208/S/1/datasetView.do'

def distance(a, b):
    return math.hypot((a[0]-b[0])*111320*math.cos(math.radians((a[1]+b[1])/2)), (a[1]-b[1])*110540)

def coords(wkt, kind):
    if not wkt.startswith(kind+'(') or not wkt.endswith(')'):
        raise ValueError('WKT 형식 불일치')
    out = [tuple(map(float, p.split())) for p in wkt[len(kind)+1:-1].split(',')]
    if any(len(p)!=2 or not (126.7<=p[0]<=127.3 and 37.4<=p[1]<=37.8) for p in out):
        raise ValueError('서울 범위 밖 좌표')
    return out

def prepare(path):
    nodes, links = {}, {}
    counts = defaultdict(int)
    # 포털 CSV의 실제 인코딩은 CP949. 노드/링크는 동일 파일에 섞여 있다.
    with path.open(encoding='cp949', newline='') as f:
        reader = csv.reader(f)
        header = next(reader)
        if len(header)!=23 or header[1]!='노드 WKT':
            raise ValueError('공식 CSV 헤더를 확인해 주세요')
        for r in reader:
            if len(r)!=23: raise ValueError('열 개수 불일치')
            counts[r[0]] += 1
            if r[0]=='NODE':
                p = coords(r[1], 'POINT')[0]
                item = (p, r[11])
                if r[2] in nodes and nodes[r[2]]!=item: raise ValueError('상충하는 노드 ID')
                nodes[r[2]]=item
            elif r[0]=='LINK':
                points=coords(r[4], 'LINESTRING')
                item=(r[7], r[8], points, any(v not in ('', '0') for v in r[14:22]))
                if r[5] in links and links[r[5]]!=item: raise ValueError('상충하는 링크 ID')
                links[r[5]]=item
            else: raise ValueError('알 수 없는 레코드 종류')
    branches=defaultdict(dict)
    excluded=set()
    for link_id,(a,b,points,special) in links.items():
        if a not in nodes or b not in nodes:
            counts['missingEndpoint']+=1
            excluded.update((a,b)); continue
        if distance(nodes[a][0], points[0])>3 or distance(nodes[b][0], points[-1])>3:
            counts['endpointMismatch']+=1
            excluded.update((a,b)); continue
        if special:
            excluded.update((a,b)); counts['specialLink']+=1; continue
        for node_id,other,ps in ((a,b,points),(b,a,list(reversed(points)))):
            origin=nodes[node_id][0]
            # 곡선 링크도 접속부 가까운 실제 원본 좌표를 사용한다.
            tip=next((p for p in ps[1:] if distance(origin,p)>=8),None)
            if tip is None: excluded.add(node_id); continue
            branches[node_id][other]={'lat':tip[1], 'lng':tip[0]}
    junctions=[]
    for node_id, choices in branches.items():
        if node_id in excluded or len(choices)<3: continue
        (lng,lat),district=nodes[node_id]
        junctions.append({'id':node_id,'lat':lat,'lng':lng,'district':district,'branches':list(choices.values())})
    if not junctions: raise ValueError('연결이 검증된 갈림길이 없습니다')
    return {'sourceUrl':SOURCE_URL,'license':'KOGL-1','surveyYear':2020,
            'originalSha256':hashlib.sha256(path.read_bytes()).hexdigest(),
            'counts':dict(counts),'junctions':junctions}

if __name__=='__main__':
    p=argparse.ArgumentParser(); p.add_argument('csv',type=Path); p.add_argument('output',type=Path)
    p.add_argument('--expected-rows', type=int, required=True, help='원본 포털의 전체 행 수')
    args=p.parse_args(); data=prepare(args.csv)
    if data['counts'].get('NODE',0)+data['counts'].get('LINK',0) != args.expected_rows:
        raise ValueError('포털 전체 행 수와 다릅니다. 잘린 다운로드는 사용하지 않습니다')
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(data,ensure_ascii=False),encoding='utf-8')
    print(json.dumps({'counts':data['counts'],'junctions':len(data['junctions']),'sha256':hashlib.sha256(args.output.read_bytes()).hexdigest()},ensure_ascii=False))
