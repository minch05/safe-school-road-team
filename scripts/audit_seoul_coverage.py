"""검토된 공개 시설 파일의 서울 25구×종류 점검. 실제 시설 미설치를 추론하지 않는다."""
import argparse, collections, datetime, hashlib, json, pathlib
DISTRICTS='종로구 중구 용산구 성동구 광진구 동대문구 중랑구 성북구 강북구 도봉구 노원구 은평구 서대문구 마포구 양천구 강서구 구로구 금천구 영등포구 동작구 관악구 서초구 강남구 송파구 강동구'.split()
KINDS='CROSSWALK PEDESTRIAN_SIGNAL SCHOOL SCHOOL_ZONE CCTV EMERGENCY_BELL CHILD_SAFETY_HOUSE SECURITY_LIGHT'.split()
def inspect(rows, today):
 groups=collections.defaultdict(list)
 for row in rows:groups[(row.get('district'),row.get('kind'))].append(row)
 matrix=[]
 for district in DISTRICTS:
  for kind in KINDS:
   values=groups[(district,kind)];dates=[r.get('baseDate') for r in values if r.get('baseDate')]
   old=sum(d<str(today-datetime.timedelta(days=730)) for d in dates) if kind!='CROSSWALK' else None
   coords=collections.Counter((r.get('lat'),r.get('lng')) for r in values)
   matrix.append({'district':district,'kind':kind,'rows':len(values),'missingDates':len(values)-len(dates),'oldestDate':min(dates,default=None),'newestDate':max(dates,default=None),'overTwoYearsRows':old,'coLocatedRows':sum(n-1 for n in coords.values()),'dateMeaning':('원본 내려받은 날짜 · 시설 갱신일 아님' if kind=='CROSSWALK' else '원본 기준일 · 현장 확인일 아님'),'status':'REGISTERED' if values else 'NO_REGISTERED_DATA'})
 return {'checkedAt':str(today),'districtCount':25,'matrix':matrix,'unassignedRows':sum(r.get('district') not in DISTRICTS for r in rows),'limits':['0행은 실제 시설 없음이 아닌 등록 자료 없음','동일 좌표는 중복 시설 확정이 아님','등록 자료가 있어도 전수 수집을 보장하지 않음','2년 기준은 갱신 점검 기준이며 유효기간이 아님']}
def inspect_report(report):
 counts={(r.get('district'),r['kind']):int(r['total']) for r in report['districts']}
 matrix=[{'district':d,'kind':k,'rows':counts.get((d,k),0),'status':'REGISTERED' if counts.get((d,k),0) else 'NO_REGISTERED_DATA'} for d in DISTRICTS for k in KINDS]
 return {'checkedAt':report['checkedAt'],'scope':'운영 DB 집계 보고서 스냅샷 · 입력 보고서 시점 기준','districtCount':25,'matrix':matrix,'kindDateQuality':report['facilities'],'unassignedRows':report['unknownDistrict'],'limits':['0행은 실제 시설 없음이 아닌 등록 자료 없음','구별 수집 완전성을 보장하지 않음','날짜 품질은 종류 전체 집계이며 구별 날짜로 해석하지 않음']}
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('input',type=pathlib.Path);parser.add_argument('--output',type=pathlib.Path,required=True);args=parser.parse_args()
 raw=args.input.read_bytes();data=json.loads(raw);result=inspect_report(data) if 'districts' in data else inspect(data['facilities'],datetime.date.today());result['inputSha256']=hashlib.sha256(raw).hexdigest();args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2));print('서울 25구 × 8종 점검:',len(result['matrix']),'칸. 시설 이름·좌표 출력 없음.')
