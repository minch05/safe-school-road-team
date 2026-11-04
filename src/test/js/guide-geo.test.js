// node --test src/test/js/guide-geo.test.js  (길 안내 위치·거리 계산 시험, 외부 의존 없음)
const test = require('node:test');
const assert = require('node:assert');
const G = require('../../main/resources/static/js/guide-geo.js');

// 저장경로응답예시와 같은 모양: 서쪽 → 동쪽 직선 후 북쪽으로 꺾음
const line = [{ lat: 37.59867, lng: 127.1 }, { lat: 37.59867, lng: 127.1036 }, { lat: 37.5998, lng: 127.1036 }];
const points = [
  { seq: 1, lat: 37.5986733, lng: 127.1027495, alongM: 242, guideText: '횡단보도예요. 멈춰서 신호와 양쪽을 확인하고 건너요.' },
  { seq: 2, lat: 37.598737, lng: 127.1038418, alongM: 325, guideText: '서울망우초등학교 쪽에서 길을 꺾어요.' },
];

test('거리는 서버 GeoLine과 같은 근사(경도 0.001도 ≈ 88m)', () => {
  const d = G.distanceM({ lat: 37.59867, lng: 127.1 }, { lat: 37.59867, lng: 127.101 });
  assert.ok(Math.abs(d - 88.2) < 0.5, d);
});

test('선에 투영하면 따라간 거리와 선까지 거리가 나온다', () => {
  const p = G.prepareLine(line);
  const loc = G.locate(p, { lat: 37.59877, lng: 127.1018 });
  assert.ok(Math.abs(loc.distanceM - 11) < 1, loc.distanceM);
  assert.ok(Math.abs(loc.alongM - 159) < 2, loc.alongM);
});

test('지점 20m 안이면 도착, 안내문을 보여 준다', () => {
  const r = G.step(points, new Set(), { lat: 37.59867, lng: 127.10270 }, G.prepareLine(line), 10);
  assert.equal(r.state, 'ARRIVED');
  assert.deepEqual(r.newlyArrived, [1]);
  assert.match(r.message, /멈춰/);
});

test('경로에서 40m 넘게 벗어나면 OFF_ROUTE', () => {
  const r = G.step(points, new Set(), { lat: 37.5992, lng: 127.1010 }, G.prepareLine(line), 10);
  assert.equal(r.state, 'OFF_ROUTE');
});

test('정확도가 50m보다 나쁘면 판정하지 않는다', () => {
  assert.equal(G.step(points, new Set(), line[0], null, 80).state, 'WEAK_SIGNAL');
});

test('오늘 경로선이 없어도 지점 좌표로 다음 안내 거리를 낸다', () => {
  const r = G.step(points, new Set([1]), { lat: 37.59867, lng: 127.1030 }, null, 5);
  assert.equal(r.state, 'WALKING');
  assert.equal(r.next.seq, 2);
  assert.match(r.message, /다음 안내까지 약 \d+m/);
});

test('모두 지나면 DONE', () => {
  assert.equal(G.step(points, new Set([1, 2]), line[2], null, 5).state, 'DONE');
});
