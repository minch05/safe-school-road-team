"""두 정규화 공개자료 파일 비교. 좌표 원본을 보고서에 복사하지 않고 이상 갱신을 차단한다."""
import argparse, collections, datetime, hashlib, json, math
from pathlib import Path

def distance(a,b):
    y1,y2=map(math.radians,(a['lat'],b['lat']));dy=y2-y1;dx=math.radians(b['lng']-a['lng'])
    return 6371000*2*math.asin(min(1,math.sqrt(math.sin(dy/2)**2+math.cos(y1)*math.cos(y2)*math.sin(dx/2)**2)))

def review(old,new,today=None):
    today=today or datetime.date.today();issues=[];changes={}
    for table in ('facilities','risks'):
        def indexed(rows):
            out={}
            for r in rows:
                key=(r['source'],str(r['sourceId']))
                if key in out:issues.append({'table':table,'reason':'원본 관리번호 중복','source':key[0]})
                out[key]=r
            return out
        before=indexed(old.get(table,[]));after=indexed(new.get(table,[]))
        bc=collections.Counter(r['source'] for r in before.values());ac=collections.Counter(r['source'] for r in after.values())
        for source,count in bc.items():
            if ac[source]<count*.9:issues.append({'table':table,'source':source,'reason':'행 수 10% 초과 감소','before':count,'after':ac[source]})
        moved=older=future=invalid=0
        for key,r in after.items():
            prev=before.get(key)
            date=r.get('baseDate')
            if date:
                try:
                    parsed=datetime.date.fromisoformat(date)
                    if parsed>today:future+=1
                    if prev and prev.get('baseDate') and parsed<datetime.date.fromisoformat(prev['baseDate']):older+=1
                except ValueError:invalid+=1
            if table=='facilities':
                if not all(isinstance(r.get(v),(float,int)) and math.isfinite(r[v]) for v in ('lat','lng')) or not 37.4<=r.get('lat',0)<=37.73 or not 126.75<=r.get('lng',0)<=127.2:invalid+=1
                elif prev and distance(prev,r)>30:moved+=1
            elif prev and r.get('baseYear',0)<prev.get('baseYear',0):older+=1
        for count,reason in [(moved,'30m 초과 좌표 이동'),(older,'자료 기준일/연도 역행'),(future,'미래 기준일'),(invalid,'좌표/날짜 형식 오류')]:
            if count:issues.append({'table':table,'reason':reason,'count':count})
        changes[table]={'before':len(before),'after':len(after),'added':len(after.keys()-before.keys()),'removed':len(before.keys()-after.keys()),'changed':sum(before[k]!=after[k] for k in before.keys()&after.keys()),'sourceCounts':dict(ac),'missingBaseDate':sum(not r.get('baseDate') for r in after.values()) if table=='facilities' else None}
    return {'approved':not issues,'checkedAt':today.isoformat(),'changes':changes,'issues':issues,'limits':['이 검사는 갱신 이상을 찾는 절차이며 현장 정확도를 보증하지 않음','보행등 연결·작동과 누락된 지역은 별도 검증 필요']}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('baseline',type=Path);parser.add_argument('candidate',type=Path);parser.add_argument('--report',type=Path,required=True);args=parser.parse_args()
    old=args.baseline.read_bytes();new=args.candidate.read_bytes();result=review(json.loads(old),json.loads(new))
    result.update(baselineSha256=hashlib.sha256(old).hexdigest(),candidateSha256=hashlib.sha256(new).hexdigest())
    args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps({'approved':result['approved'],'issues':result['issues'],'candidateSha256':result['candidateSha256']},ensure_ascii=False));raise SystemExit(0 if result['approved'] else 2)
