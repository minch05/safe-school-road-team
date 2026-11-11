"""2026-10-10 추가 수급한 구청 파일(동대문·서대문 보안등, 종로 비상벨)을 검토해 적재 후보와 승인 보고서를 만든다.

기존 prepare_seoul_lights.py 규칙을 따르고, 두 가지를 더한다.
- 한 좌표에 10개 넘게 몰린 행은 실제 설치 위치가 아니라 대표점으로 보고 뺀다(서대문 한 점 377개 등).
- 서대문 파일은 설치개수 칸이 없다. 행마다 보안등 표찰(예: 충현동9029)이 하나씩이라 한 행 = 보안등 1개로 본다.
비상벨은 고정 시설이고 최종 점검 기록이 있어 기준일 4년(1,460일)까지 받는다. 점검 결과가 정상(Y)인 행만.
(기존 DB의 표준데이터 비상벨은 기준일 제한 없이 2020년 행도 있다. 그보다 엄격하되, 2023년 점검분은 받는다)
외부 API·사용자 데이터 없음. 원본은 Git 제외 폴더에 둔다.

  python3 -I scripts/prepare_extra_facilities.py
"""
import collections, csv, datetime, hashlib, io, json, re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / '무제 폴더/품질검토/2026-10-10-추가수급'
CLUSTER_MAX = 10
LIGHT_MAX_DAYS = 730
BELL_MAX_DAYS = 1460
ROAD = re.compile(r'([가-힣0-9]+(?:로|길))\s*\d')

FILES = [
    dict(file='dongdaemun_lights_20260929.csv', district='동대문구', kind='SECURITY_LIGHT', source='SECURITY_LIGHT_DISTRICT',
         dataset='https://www.data.go.kr/data/15012186/fileData.do', lat='위도', lng='경도', name='보안등위치명',
         address=('소재지도로명주소',), count='설치개수'),
    dict(file='seodaemun_lights_20250101.csv', district='서대문구', kind='SECURITY_LIGHT', source='SECURITY_LIGHT_DISTRICT',
         dataset='https://www.data.go.kr/data/15128090/fileData.do', lat='위도', lng='경도', name='보안등표찰명',
         address=('소재지지번주소',), count=None),
    dict(file='jongno_bells_20230831.csv', district='종로구', kind='EMERGENCY_BELL', source='BELL_DISTRICT',
         dataset='https://www.data.go.kr/data/15124510/fileData.do', lat='WGS84위도', lng='WGS84경도', name=None,
         address=('소재지도로명주소', '소재지지번주소'), count=None),
]


def valid(lat, lng):
    return 37.4 <= lat <= 37.73 and 126.75 <= lng <= 127.2


def read(raw):
    for enc in ('utf-8-sig', 'cp949'):
        try:
            return list(csv.DictReader(io.StringIO(raw.decode(enc))))
        except UnicodeDecodeError:
            continue
    raise ValueError('인코딩 오류')


def prepare(spec, raw, today):
    rows = read(raw)
    reasons = collections.Counter()
    max_days = BELL_MAX_DAYS if spec['kind'] == 'EMERGENCY_BELL' else LIGHT_MAX_DAYS
    keep = []
    for r in rows:
        try:
            date = datetime.date.fromisoformat(r.get('데이터기준일자', '').strip())
        except ValueError:
            reasons['기준일 형식 오류'] += 1; continue
        if date > today or date < today - datetime.timedelta(days=max_days):
            reasons[f'미래 또는 {max_days}일 초과 기준일'] += 1; continue
        if spec['count'] and r.get(spec['count'], '').strip() != '1':
            reasons['여러 설치 집계 또는 설치개수 미확인'] += 1; continue
        if spec['kind'] == 'EMERGENCY_BELL' and r.get('최종점검결과구분', '').strip() != 'Y':
            reasons['점검 결과 정상 아님'] += 1; continue
        try:
            lat, lng = float(r[spec['lat']]), float(r[spec['lng']])
        except (ValueError, KeyError):
            reasons['좌표 누락'] += 1; continue
        if not valid(lat, lng):
            reasons['서울 좌표 범위 밖'] += 1; continue
        address = next((r.get(a, '').strip() for a in spec['address'] if r.get(a, '').strip()), '')
        if spec['kind'] == 'EMERGENCY_BELL':
            road = ROAD.search(r.get('소재지도로명주소', '') or '')
            name = ' · '.join(x for x in (r.get('설치장소유형', '').strip(), road.group(1) if road else '') if x)
            ident_name = r.get('안전비상벨관리번호', '').strip()
        else:
            name = r.get(spec['name'], '').strip()
            ident_name = name
        # 주소에 구 이름이 없는 행(예: "명륜3가 산2-14")은 기관이 그 구라 같은 구로 본다. 주소가 아예 없으면 뺀다
        if not name or not address or (spec['district'] not in address and '서울' in address):
            reasons['이름·주소 지역 확인 불가'] += 1; continue
        keep.append((r, lat, lng, name, ident_name, address, date))
    # 한 좌표에 너무 많이 몰린 행은 대표점(주소·기관 위치 등)으로 보고 뺀다
    at = collections.Counter((round(k[1], 7), round(k[2], 7)) for k in keep)
    facilities, ids = [], set()
    for r, lat, lng, name, ident_name, address, date in keep:
        if at[(round(lat, 7), round(lng, 7))] > CLUSTER_MAX:
            reasons[f'한 좌표에 {CLUSTER_MAX}개 넘게 몰림'] += 1; continue
        ident = hashlib.sha256((spec['district'] + '|' + ident_name + '|' + address + '|' + str(lat) + '|' + str(lng)).encode()).hexdigest()[:40]
        if ident in ids:
            reasons['동일 이름·주소·좌표 반복'] += 1; continue
        ids.add(ident)
        facilities.append(dict(kind=spec['kind'], source=spec['source'], sourceId=ident, name=name[:100], lat=lat, lng=lng,
                               district=spec['district'], baseDate=date.isoformat()))
    summary = dict(district=spec['district'], file=spec['file'], dataset=spec['dataset'], sha256=hashlib.sha256(raw).hexdigest(),
                   rawRows=len(rows), acceptedRows=len(facilities), excluded=dict(reasons))
    return facilities, summary


def build(kind, today):
    facilities, summaries = [], []
    for spec in FILES:
        if spec['kind'] != kind:
            continue
        rows, summary = prepare(spec, (SRC / spec['file']).read_bytes(), today)
        facilities += rows
        summaries.append(summary)
    candidate = dict(version=1, checkedAt=today.isoformat(), districts=sorted({f['district'] for f in facilities}), facilities=facilities)
    return candidate, summaries


if __name__ == '__main__':
    today = datetime.date(2026, 10, 10)
    for kind, out, review in [('SECURITY_LIGHT', 'approved-extra-lights.json', 'docs/extra-lights-import-review.json'),
                              ('EMERGENCY_BELL', 'approved-extra-bells.json', 'docs/extra-bells-import-review.json')]:
        candidate, summaries = build(kind, today)
        path = SRC / out
        path.write_text(json.dumps(candidate, ensure_ascii=False))
        report = dict(approved=True, candidateSha256=hashlib.sha256(path.read_bytes()).hexdigest(), checkedAt=candidate['checkedAt'],
                      districts=candidate['districts'], rows=len(candidate['facilities']), files=summaries,
                      limits=['제외된 자료는 시설 없음이 아님', f'한 좌표 {CLUSTER_MAX}개 초과는 대표점으로 보고 제외',
                              '주소 좌표 재변환 없음 · 정확한 설치 위치를 추정하지 않음',
                              '비상벨은 작동 여부가 아니라 마지막 점검 기록 기준'])
        (ROOT / review).write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print(json.dumps({k: report[k] for k in ('districts', 'rows', 'files')}, ensure_ascii=False, indent=1))
