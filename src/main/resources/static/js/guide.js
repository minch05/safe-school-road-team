// 길 안내 화면 뼈대 (RGN6-127 보조자료). API: GET /api/routes, GET /api/routes/{id}, GET /api/routes/{id}/line
// 위치·거리 판정은 guide-geo.js(GuideGeo)에 있다. 화면은 상태만 받아서 글자를 바꾼다.
(function () {
  const $ = id => document.getElementById(id);
  let route = null, prepared = null, passed = new Set(), watchId = null;

  function status(text) { $('guide-status').textContent = text; }

  async function getJson(url) {
    const r = await fetch(url, { credentials: 'same-origin' });
    if (r.status === 401) { location.assign('/login.html'); throw new Error('로그인이 필요해요.'); }
    const body = await r.json().catch(() => ({}));
    if (!r.ok) throw Object.assign(new Error(body.error || '불러오지 못했어요.'), { status: r.status });
    return body;
  }

  async function showList() {
    stop();
    $('route-guide').hidden = true;
    const { routes } = await getJson('/api/routes');
    const ul = $('routes');
    ul.replaceChildren();
    if (!routes.length) { status('저장한 경로가 없어요. 지도에서 후보를 고르고 저장해 주세요.'); return; }
    for (const r of routes) {
      const li = document.createElement('li');
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = `${r.name} · 안내 지점 ${r.pointCount}곳`;
      b.onclick = () => open(r.id).catch(e => status(e.message));
      li.append(b);
      ul.append(li);
    }
    $('route-list').hidden = false;
    status('안내할 경로를 골라요.');
  }

  async function open(id) {
    route = await getJson(`/api/routes/${id}`);
    passed = new Set();
    $('route-name').textContent = route.name;
    const ol = $('points');
    ol.replaceChildren();
    for (const p of route.points) {
      const li = document.createElement('li');
      li.id = `point-${p.seq}`;
      li.textContent = p.guideText;
      ol.append(li);
    }
    // 오늘 경로선: 못 받아도 저장한 지점으로 안내한다(서버가 503과 안내 문구를 준다)
    try {
      const today = await getJson(`/api/routes/${id}/line`);
      prepared = GuideGeo.prepareLine(today.line);
      $('line-note').textContent = today.note;
      // TODO(지도): today.line과 route.points를 지도에 그린다
    } catch (e) {
      prepared = null;
      $('line-note').textContent = e.message;
    }
    $('route-list').hidden = true;
    $('route-guide').hidden = false;
    $('now').textContent = route.points[0].guideText;
    status('안내 시작을 누르면 위치에 따라 안내가 바뀌어요.');
  }

  function update(pos) {
    const here = { lat: pos.coords.latitude, lng: pos.coords.longitude };
    const r = GuideGeo.step(route.points, passed, here, prepared, pos.coords.accuracy);
    for (const seq of r.newlyArrived || []) {
      passed.add(seq);
      $(`point-${seq}`)?.classList.add('passed');
    }
    $('now').textContent = r.message;
    $('next-distance').textContent = r.next && r.state !== 'OFF_ROUTE' ? `다음: ${r.next.guideText}` : '';
    // TODO(음성·진동): r.state가 ARRIVED·OFF_ROUTE일 때
  }

  // 위치 권한을 거부하면 안내를 멈춘 상태로 돌려 다시 시작할 수 있게 하고, 저장한 목록으로 걷도록 알린다
  function positionError(e) {
    if (e.code === 1) {
      stop();
      $('now').textContent = '위치를 쓸 수 없어요. 아래 안내 목록을 보고 걸어요.';
      $('next-distance').textContent = '';
      status('위치 권한이 없어요. 목록을 보고 걸어요.');
      return;
    }
    status('위치를 받지 못했어요. 잠시 뒤 다시 해요.');
  }

  function start() {
    if (!('geolocation' in navigator)) { status('이 기기에서는 위치를 쓸 수 없어요. 목록을 보고 걸어요.'); return; }
    watchId = navigator.geolocation.watchPosition(update, positionError,
      { enableHighAccuracy: true, maximumAge: 3000, timeout: 15000 });
    $('start').hidden = true;
    $('stop').hidden = false;
    status('안내 중이에요.');
  }

  function stop() {
    if (watchId != null) navigator.geolocation.clearWatch(watchId);
    watchId = null;
    $('start').hidden = false;
    $('stop').hidden = true;
  }

  $('start').onclick = start;
  $('stop').onclick = () => { stop(); status('안내를 멈췄어요.'); };
  $('back').onclick = () => showList().catch(e => status(e.message));

  const id = new URLSearchParams(location.search).get('id');
  (id ? open(id) : showList()).catch(e => status(e.status === 404 ? '경로를 찾을 수 없어요.' : e.message));
})();
