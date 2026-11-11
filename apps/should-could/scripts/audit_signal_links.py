"""공개 횡단보도·보행등 좌표 교차검증. 거리 후보이며 도로 연결·신호 작동을 확정하지 않는다."""
import argparse, collections, hashlib, json, math
from pathlib import Path
CELL=.001

def valid(r):
 return all(isinstance(r.get(k),(int,float)) and not isinstance(r[k],bool) and math.isfinite(r[k]) for k in ('lat','lng')) and 37.4<=r['lat']<=37.73 and 126.75<=r['lng']<=127.2

def cell(r):return (math.floor(r['lat']/CELL),math.floor(r['lng']/CELL))
def distance(a,b):return math.hypot((a['lat']-b['lat'])*110540,(a['lng']-b['lng'])*88200)

def audit(rows):
 crossings=[r for r in rows if r.get('kind')=='CROSSWALK' and valid(r)]
 signals=[r for r in rows if r.get('kind')=='PEDESTRIAN_SIGNAL' and valid(r)]
 grid=collections.defaultdict(list)
 for i,r in enumerate(crossings):grid[cell(r)].append(i)
 linked=collections.Counter();ambiguous=set();signal_counts=collections.Counter()
 # 같은 좌표의 중복 보행등은 연결 근거를 늘리지 않는다.
 seen=set()
 for s in signals:
  coord=(s['lat'],s['lng'])
  if coord in seen:continue
  seen.add(coord);y,x=cell(s)
  nearby=[i for dy in (-1,0,1) for dx in (-1,0,1) for i in grid[(y+dy,x+dx)] if distance(s,crossings[i])<=10]
  signal_counts['NO_CROSSWALK_WITHIN_10M' if not nearby else 'ONE_CANDIDATE' if len(nearby)==1 else 'MULTIPLE_CANDIDATES']+=1
  for i in nearby:linked[i]+=1
  if len(nearby)>1:ambiguous.update(nearby)
 grouped=collections.defaultdict(collections.Counter)
 for i,r in enumerate(crossings):
  state='AMBIGUOUS_NEARBY_SIGNAL' if i in ambiguous else 'DISTANCE_CANDIDATE_ONLY' if linked[i] else 'NO_NEARBY_SIGNAL_RECORD'
  grouped[r.get('district') or '지역 미분류'][state]+=1
 return {'crosswalkRows':len(crossings),'signalRows':len(signals),'uniqueSignalCoordinates':len(seen),'invalidCoordinateRows':sum(not valid(r) for r in rows if r.get('kind') in ('CROSSWALK','PEDESTRIAN_SIGNAL')),'signalMatching':dict(signal_counts),'districts':dict(sorted((d,dict(v)) for d,v in grouped.items())),'thresholdMeters':10,'limits':['거리 후보는 같은 도로·횡단보도 신호임을 확정하지 않음','복수 후보는 오류 확정이 아닌 검토 대상','주변 등록 보행등 없음은 실제 신호등 없음이 아님','원본 좌표·사용자 경로는 보고서에 저장하지 않음','DB 및 추천 계산 변경 없음']}

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('input',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args();raw=a.input.read_bytes();data=json.loads(raw);result=audit(data['facilities']);result['inputSha256']=hashlib.sha256(raw).hexdigest();result['checkedAt']=data.get('checkedAt');a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps({k:result[k] for k in ('crosswalkRows','signalRows','uniqueSignalCoordinates','invalidCoordinateRows','signalMatching')},ensure_ascii=False))
