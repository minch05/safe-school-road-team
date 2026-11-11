import datetime, unittest
from prepare_extra_facilities import FILES, prepare

TODAY = datetime.date(2026, 10, 10)
LIGHT = next(f for f in FILES if f['district'] == '서대문구')
BELL = next(f for f in FILES if f['district'] == '종로구')


def csv_bytes(header, rows):
    return ('\n'.join([','.join(header)] + [','.join(r) for r in rows])).encode('cp949')


class ExtraFacilitiesTest(unittest.TestCase):
    def test_한_좌표에_몰린_보안등과_오래된_기준일은_뺀다(self):
        h = ['보안등표찰명', '소재지지번주소', '경도', '위도', '설치형태', '관리기관전화번호', '관리기관명', '데이터기준일자']
        rows = [[f'충현동{i}', '서울특별시 서대문구 미근동26', '126.9668', '37.5635', '한전주', '02', '서대문구청', '2025-01-01'] for i in range(11)]
        rows.append(['충현동99', '서울특별시 서대문구 미근동35', '126.9667', '37.5634', '한전주', '02', '서대문구청', '2025-01-01'])
        rows.append(['충현동98', '서울특별시 서대문구 미근동36', '126.9665', '37.5633', '한전주', '02', '서대문구청', '2022-01-01'])
        facilities, summary = prepare(LIGHT, csv_bytes(h, rows), TODAY)
        self.assertEqual([f['name'] for f in facilities], ['충현동99'])
        self.assertEqual(summary['excluded']['한 좌표에 10개 넘게 몰림'], 11)
        self.assertEqual(summary['excluded']['미래 또는 730일 초과 기준일'], 1)

    def test_비상벨은_점검_정상이고_4년_안인_것만(self):
        h = ['안전비상벨관리번호', '설치장소유형', '소재지도로명주소', '소재지지번주소', 'WGS84위도', 'WGS84경도', '최종점검결과구분', '데이터기준일자']
        rows = [['A-1', '도로', '서울특별시 종로구 북촌로 134-1', '', '37.58', '126.98', 'Y', '2023-08-31'],
                ['A-2', '공원', '서울특별시 종로구 북촌로 140', '', '37.581', '126.981', 'N', '2023-08-31'],
                ['A-3', '공원', '서울특별시 종로구 북촌로 150', '', '37.582', '126.982', 'Y', '2020-07-01']]
        facilities, summary = prepare(BELL, csv_bytes(h, rows), TODAY)
        self.assertEqual([(f['name'], f['source']) for f in facilities], [('도로 · 북촌로', 'BELL_DISTRICT')])
        self.assertEqual(summary['excluded'], {'점검 결과 정상 아님': 1, '미래 또는 1460일 초과 기준일': 1})


if __name__ == '__main__':
    unittest.main()
