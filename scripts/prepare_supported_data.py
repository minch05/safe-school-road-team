"""지원 12개 구 공공자료를 팀 DB 적재용 한 파일로 정리한다 (4-10-07).

입력 원본은 Git에 올리지 않는다(용량·출처 파일). 실행:
  python3 scripts/prepare_supported_data.py --reviewed <검토완료 백업.json> --raw <원본 폴더> \
      --out src/main/resources/data/supported-districts.json.gz

- 횡단보도·학교·사고 다발 지역: 2026-10-09 검토 완료 백업(reviewed-*.json)에서 가져온다.
  횡단보도 signal_status는 그 검토에서 보행 신호 위치와 연결한 결과(ESTIMATED)이거나 미확인(UNKNOWN)이다. 확정(CONFIRMED)은 없다.
- CCTV·안전비상벨·보안등·어린이보호구역: 원본 폴더의 서울/전국 표준데이터 전체 파일에서 12개 구만 고른다.
- 좌표가 없거나 서울 범위 밖이면 버리고 그 수를 기록한다. 같은 출처·원본 ID는 한 번만 남긴다.
"""
import argparse, csv, gzip, hashlib, json, os, re, datetime, collections

DISTRICTS = ['강서구', '광진구', '동대문구', '중랑구', '관악구', '강북구', '구로구', '성북구', '종로구', '영등포구', '양천구', '중구']
SEOUL = (37.40, 37.75, 126.75, 127.25)

SOURCES = {
    'CROSSWALK': ('서울시 교통안전시설물 횡단보도 OA-15554', '서울 열린데이터광장 OA-15554 trafficSafetyA004AInfo · 신호 연결은 2026-10-09 검토 결과'),
    'SCHOOL': ('전국초중등학교위치표준데이터', '공공데이터포털 표준데이터'),
    'SCHOOL_ZONE': ('전국어린이보호구역표준데이터', '공공데이터포털 표준데이터 · 대상시설 위치(구역 경계 아님)'),
    'CCTV': ('서울특별시 CCTV정보', '공공데이터포털 CCTV정보(서울특별시) WGS84'),
    'EMERGENCY_BELL': ('안전비상벨위치정보', '공공데이터포털 안전비상벨위치정보 WGS84'),
    'SECURITY_LIGHT': ('전국보안등정보표준데이터', '공공데이터포털 표준데이터'),
}
RISK_SOURCE = ('도로교통공단 사고다발지역 B552061', '공공데이터포털 B552061 보행자 사고다발지역 · 2023년 사고 기준')


def gu_of(*addrs):
    for a in addrs:
        m = re.match(r'\s*서울(?:특별시)?\s+(\S+구)(?:\s|$)', a or '')
        if m:
            return m.group(1)
    return None


def in_seoul(lat, lng):
    return SEOUL[0] <= lat <= SEOUL[1] and SEOUL[2] <= lng <= SEOUL[3]


def num(v):
    try:
        x = float(str(v).strip())
        return x if x == x else None
    except (TypeError, ValueError):
        return None


def iso(v):
    v = (v or '').strip()
    return v[:10] if re.match(r'\d{4}-\d{2}-\d{2}', v) else None


def open_csv(path):
    for enc in ('utf-8-sig', 'cp949'):
        try:
            with open(path, encoding=enc) as f:
                f.read(4096)
            return open(path, encoding=enc, newline='')
        except UnicodeDecodeError:
            continue
    raise ValueError(f'인코딩을 알 수 없습니다: {path}')


def json_rows(path):
    with open(path, encoding='utf-8') as f:
        data = json.load(f)
    def find(x):
        if isinstance(x, list) and x and isinstance(x[0], dict):
            return x
        if isinstance(x, dict):
            if isinstance(x.get('records'), list):
                return x['records']
            for v in x.values():
                r = find(v)
                if r:
                    return r
        return None
    return find(data) or []


def file_date(path):
    return datetime.date.fromtimestamp(os.path.getmtime(path)).isoformat()


def synthetic_id(*parts):
    return 'h-' + hashlib.sha1('|'.join(str(p) for p in parts).encode()).hexdigest()[:16]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--reviewed', required=True)
    ap.add_argument('--raw', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()

    facilities, seen = [], set()
    dropped = collections.Counter()
    downloaded = {}

    def add(kind, district, lat, lng, original_id, base_date, signal=None, name=''):
        if district not in DISTRICTS:
            return
        if lat is None or lng is None or not in_seoul(lat, lng):
            dropped[(kind, 'no_or_bad_coordinate')] += 1
            return
        key = (kind, original_id)
        if key in seen:
            dropped[(kind, 'duplicate_id')] += 1
            return
        seen.add(key)
        facilities.append({'kind': kind, 'district': district, 'lat': round(lat, 7), 'lng': round(lng, 7),
                           'originalId': original_id, 'baseDate': base_date, 'signalStatus': signal,
                           'name': (name or '').strip()[:100]})

    with open(a.reviewed, encoding='utf-8') as f:
        reviewed = json.load(f)
    for r in reviewed['facilities']:
        if r['kind'] == 'CROSSWALK':
            # 백업의 base_date는 원본 내려받은 날짜라서 시설 기준일로 쓰지 않는다.
            add('CROSSWALK', r['district'], num(r['lat']), num(r['lng']), r['source_id'], None, r.get('signal_status') or 'UNKNOWN')
            downloaded['CROSSWALK'] = r['base_date']
        elif r['kind'] == 'SCHOOL':
            add('SCHOOL', r['district'], num(r['lat']), num(r['lng']), r['source_id'], r['base_date'], name=r.get('name'))
    # 학교는 백업에 원본 내려받은 날짜가 없어 검토 완료 백업 파일 날짜로 기록한다(base_date는 데이터 기준일).
    downloaded['SCHOOL'] = file_date(a.reviewed)

    p = os.path.join(a.raw, 'CCTV정보_서울특별시.csv')
    with open_csv(p) as f:
        for r in csv.DictReader(f):
            add('CCTV', gu_of(r['소재지도로명주소'], r['소재지지번주소']), num(r['WGS84위도']), num(r['WGS84경도']),
                r['관리번호'].strip() or synthetic_id(r['WGS84위도'], r['WGS84경도']), iso(r['데이터기준일자']))
    downloaded['CCTV'] = file_date(p)

    p = os.path.join(a.raw, '안전비상벨위치정보.csv')
    with open_csv(p) as f:
        for r in csv.DictReader(f):
            add('EMERGENCY_BELL', gu_of(r['소재지도로명주소'], r['소재지지번주소']), num(r['WGS84위도']), num(r['WGS84경도']),
                (r.get('관리번호') or '').strip() or (r.get('안전비상벨관리번호') or '').strip() or synthetic_id(r['WGS84위도'], r['WGS84경도']),
                iso(r['데이터기준일자']))
    downloaded['EMERGENCY_BELL'] = file_date(p)

    p = os.path.join(a.raw, '전국보안등정보표준데이터.json')
    for r in json_rows(p):
        lat, lng = num(r.get('위도')), num(r.get('경도'))
        add('SECURITY_LIGHT', gu_of(r.get('소재지도로명주소'), r.get('소재지지번주소')), lat, lng,
            synthetic_id(r.get('제공기관코드'), r.get('보안등위치명'), lat, lng), iso(r.get('데이터기준일자')))
    downloaded['SECURITY_LIGHT'] = file_date(p)

    p = os.path.join(a.raw, '전국어린이보호구역표준데이터.json')
    for r in json_rows(p):
        lat, lng = num(r.get('위도')), num(r.get('경도'))
        add('SCHOOL_ZONE', gu_of(r.get('소재지도로명주소'), r.get('소재지지번주소')), lat, lng,
            synthetic_id(r.get('제공기관코드'), r.get('대상시설명'), lat, lng), iso(r.get('데이터기준일자')), name=r.get('대상시설명'))
    downloaded['SCHOOL_ZONE'] = file_date(p)

    areas = []
    for r in reviewed['risks']:
        if r['district'] not in DISTRICTS:
            continue
        geo = json.loads(r['area_json'])
        if geo.get('type') != 'Polygon':
            dropped[('RISK_AREA', 'not_polygon')] += 1
            continue
        areas.append({'kind': r['kind'], 'district': r['district'], 'name': r.get('name') or '',
                      'originalId': r['source_id'], 'baseYear': r['base_year'], 'polygon': geo['coordinates'][0]})

    counts = collections.Counter((f['district'], f['kind']) for f in facilities)
    kinds = list(SOURCES)
    coverage = [{'district': d, 'kind': k, 'rows': counts[(d, k)], 'status': 'LOADED' if counts[(d, k)] else 'NO_DATA'}
                for d in DISTRICTS for k in kinds]
    out = {
        'generatedAt': datetime.date.today().isoformat(),
        'districts': DISTRICTS,
        'sources': [{'kind': k, 'source': SOURCES[k][0], 'reference': SOURCES[k][1], 'downloaded': downloaded.get(k)} for k in kinds],
        'riskSource': {'source': RISK_SOURCE[0], 'reference': RISK_SOURCE[1]},
        'signalNote': '신호는 보행 신호 위치와 연결된 추정(ESTIMATED) 또는 미확인(UNKNOWN)만 있다. 확정(CONFIRMED) 자료는 없다.',
        'coverage': coverage,
        'dropped': [{'kind': k, 'reason': r, 'rows': n} for (k, r), n in sorted(dropped.items())],
        'facilities': facilities,
        'riskAreas': areas,
    }
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    with gzip.open(a.out, 'wt', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, separators=(',', ':'))
    print(f'시설 {len(facilities)}건, 사고 다발 지역 {len(areas)}개 → {a.out} ({os.path.getsize(a.out)//1024}KB)')
    for d in DISTRICTS:
        print(d, {k: counts[(d, k)] for k in kinds})
    print('버린 행', dict(dropped))


if __name__ == '__main__':
    main()
