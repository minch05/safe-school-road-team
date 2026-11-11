// 길 안내 화면 뼈대 (RGN6-127 보조자료). API: GET /api/routes, GET /api/routes/{id}, GET /api/routes/{id}/line
// 위치·거리 판정은 guide-geo.js(GuideGeo)에 있다. 화면은 상태만 받아서 글자를 바꾼다.
(function () {
  const $ = id => document.getElementById(id);
  let route = null, prepared = null, passed = new Set(), watchId = null;
  let guideMap, guideLayers, locationMarker;

  function drawGuideMap(line, points) {
    if (!window.L) return;
    const mapEl = $('guide-map');
    mapEl.hidden = false;
    if (!guideMap) {
      guideMap = L.map(mapEl);
      guideLayers = L.layerGroup().addTo(guideMap);
      fetch('/api/map-config').then(r => r.ok ? r.json() : null).then(config => {
        if (!config?.vworldKey) return;
        const key = encodeURIComponent(config.vworldKey);
        L.tileLayer(`https://api.vworld.kr/req/wmts/1.0.0/${key}/Base/{z}/{y}/{x}.png`, {
          attribution: '© VWorld', maxZoom: 19
        }).addTo(guideMap);
      }).catch(() => {});
    }
    guideLayers.clearLayers();
    locationMarker = null;
    const path = (line || []).map(p => [p.lat, p.lng]);
    if (path.length > 1) L.polyline(path, { color: '#176a45', weight: 5 }).addTo(guideLayers);
    for (const p of points) {
      const label = document.createElement('span');
      label.textContent = `${p.seq}. ${p.guideText}`;
      L.circleMarker([p.lat, p.lng], { radius: 6 }).bindTooltip(label).addTo(guideLayers);
    }
    guideMap.invalidateSize();
    const bounds = L.latLngBounds([...path, ...points.map(p => [p.lat, p.lng])]);
    if (bounds.isValid()) guideMap.fitBounds(bounds, { padding: [16, 16], maxZoom: 17 });
  }

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
    let line = null;
    // 오늘 경로선: 못 받아도 저장한 지점으로 안내한다(서버가 503과 안내 문구를 준다)
    try {
      const today = await getJson(`/api/routes/${id}/line`);
      line = today.line;
      prepared = GuideGeo.prepareLine(line);
      $('line-note').textContent = today.note;
    } catch (e) {
      prepared = null;
      $('line-note').textContent = e.message;
    }
    $('route-list').hidden = true;
    $('route-guide').hidden = false;
    try { drawGuideMap(line, route.points); }
    catch (e) { $('guide-map').hidden = true; }
    $('now').textContent = route.points[0].guideText;
    status('안내 시작을 누르면 위치에 따라 안내가 바뀌어요.');
  }

  function clearLocationMarker() {
    if (!locationMarker) return;
    guideLayers.removeLayer(locationMarker);
    locationMarker = null;
  }

  function update(pos) {
    const here = { lat: pos.coords.latitude, lng: pos.coords.longitude };
    if (guideMap && !$('guide-map').hidden && Number.isFinite(pos.coords.accuracy) &&
        pos.coords.accuracy <= 50 && Number.isFinite(here.lat) && Number.isFinite(here.lng)) {
      const position = [here.lat, here.lng];
      if (locationMarker) locationMarker.setLatLng(position);
      else locationMarker = L.circleMarker(position, { radius: 7, color: '#174e99' }).addTo(guideLayers);
    } else {
      clearLocationMarker();
    }
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
    clearLocationMarker();
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
    clearLocationMarker();
    $('start').hidden = false;
    $('stop').hidden = true;
  }

  $('start').onclick = start;
  $('stop').onclick = () => { stop(); status('안내를 멈췄어요.'); };
  $('back').onclick = () => showList().catch(e => status(e.message));

  const id = new URLSearchParams(location.search).get('id');
  (id ? open(id) : showList()).catch(e => status(e.status === 404 ? '경로를 찾을 수 없어요.' : e.message));
})();
