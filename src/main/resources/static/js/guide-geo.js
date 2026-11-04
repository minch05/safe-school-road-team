/*
 * 길 안내 화면용 위치·거리 계산 (RGN6-127 팀원용 보조자료 → 11주차 길 안내 화면).
 * 서버 GeoLine과 같은 평면 근사(위도 1도 110,540m, 경도 1도 111,320m×cos위도)를 써서
 * 화면과 서버가 같은 alongM(출발점부터 선을 따라간 거리)을 보도록 한다.
 * 브라우저에서는 window.GuideGeo, node 시험에서는 require('./guide-geo.js')로 쓴다.
 */
(function (root) {
  const M_PER_DEG_LAT = 110540;
  const ARRIVE_M = 20;     // 안내 지점에서 이 거리 안이면 '도착'으로 보고 안내문을 띄운다
  const OFF_ROUTE_M = 40;  // 경로선에서 이 거리보다 멀면 '경로를 벗어났어요'
  const MAX_ACCURACY_M = 50; // 위치 정확도가 이보다 나쁘면 판정하지 않는다

  /** 두 점 사이 거리(m). 점은 {lat, lng} */
  function distanceM(a, b) {
    const k = 111320 * Math.cos(((a.lat + b.lat) / 2) * Math.PI / 180);
    return Math.hypot((a.lng - b.lng) * k, (a.lat - b.lat) * M_PER_DEG_LAT);
  }

  /** 경로선 준비. line: [{lat,lng}, ...] (GET /api/routes/{id}/line 의 line) */
  function prepareLine(line) {
    if (!Array.isArray(line) || line.length < 2) throw new Error('경로선에는 점이 2개 이상 있어야 해요.');
    const lat0 = line[0].lat, lng0 = line[0].lng, kx = 111320 * Math.cos(lat0 * Math.PI / 180);
    const xs = [], ys = [], cum = [];
    line.forEach((p, i) => {
      xs.push((p.lng - lng0) * kx);
      ys.push((p.lat - lat0) * M_PER_DEG_LAT);
      cum.push(i === 0 ? 0 : cum[i - 1] + Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]));
    });
    return { line, lat0, lng0, kx, xs, ys, cum, lengthM: cum[cum.length - 1] };
  }

  /** 현재 위치를 선에 투영. 반환 {distanceM: 선까지 거리, alongM: 출발점부터 따라간 거리} */
  function locate(prepared, p) {
    const { xs, ys, cum, kx, lat0, lng0 } = prepared;
    const px = (p.lng - lng0) * kx, py = (p.lat - lat0) * M_PER_DEG_LAT;
    let best = Infinity, bestAlong = 0;
    for (let i = 0; i < xs.length - 1; i++) {
      const dx = xs[i + 1] - xs[i], dy = ys[i + 1] - ys[i], l2 = dx * dx + dy * dy;
      const t = l2 === 0 ? 0 : Math.max(0, Math.min(1, ((px - xs[i]) * dx + (py - ys[i]) * dy) / l2));
      const d = Math.hypot(px - (xs[i] + t * dx), py - (ys[i] + t * dy));
      if (d < best) { best = d; bestAlong = cum[i] + t * Math.sqrt(l2); }
    }
    return { distanceM: best, alongM: bestAlong };
  }

  /**
   * 안내 상태 한 번 계산. 위치가 바뀔 때마다 부른다.
   * points: 저장 경로 상세(GET /api/routes/{id})의 points (seq 순, alongM·lat·lng·guideText 포함)
   * passed: 이미 안내한 seq 집합(Set). 함수가 바꾸지 않으니 반환값 newlyArrived를 보고 화면에서 추가한다.
   * prepared: prepareLine 결과. 오늘 경로선을 못 받았으면 null → 지점 좌표만으로 판정한다.
   */
  function step(points, passed, position, prepared, accuracyM) {
    if (accuracyM != null && accuracyM > MAX_ACCURACY_M)
      return { state: 'WEAK_SIGNAL', message: '위치가 정확하지 않아요. 잠시 기다리거나 지도를 보고 걸어요.' };
    const remaining = points.filter(pt => !passed.has(pt.seq));
    if (!remaining.length) return { state: 'DONE', message: '안내 지점을 모두 지났어요.' };
    const next = remaining[0];
    const toNextM = distanceM(position, next);
    let offRoute = false, alongM = null;
    if (prepared) {
      const loc = locate(prepared, position);
      alongM = loc.alongM;
      offRoute = loc.distanceM > OFF_ROUTE_M;
    }
    const newlyArrived = remaining.filter(pt => distanceM(position, pt) <= ARRIVE_M).map(pt => pt.seq);
    return {
      state: offRoute ? 'OFF_ROUTE' : newlyArrived.length ? 'ARRIVED' : 'WALKING',
      next, toNextM: Math.round(toNextM), alongM: alongM == null ? null : Math.round(alongM), newlyArrived,
      message: offRoute ? '경로를 벗어났어요. 멈춰서 지도의 선을 확인해요.'
        : newlyArrived.length ? next.guideText
        : toNextM < 10 ? '다음 안내 지점이 곧 나와요.' : `다음 안내까지 약 ${roundForChild(toNextM)}m`,
    };
  }

  /** 아이에게 보여 줄 거리: 10m 단위로 반올림 */
  function roundForChild(m) { return Math.round(m / 10) * 10; }

  const api = { distanceM, prepareLine, locate, step, roundForChild, ARRIVE_M, OFF_ROUTE_M, MAX_ACCURACY_M };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.GuideGeo = api;
})(typeof window !== 'undefined' ? window : globalThis);
