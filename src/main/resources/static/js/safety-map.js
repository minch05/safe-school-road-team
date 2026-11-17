(() => {
  const message = document.getElementById('map-message');
  if (!window.L) {
    message.textContent = '지도 화면을 불러오지 못했어요.';
    return;
  }
  const map = L.map('map').setView([37.592, 127.089], 15);
  const layers = L.layerGroup().addTo(map);
  const labels = { CROSSWALK: '횡단보도', SCHOOL: '학교', SCHOOL_ZONE: '어린이보호구역',
    CCTV: 'CCTV', EMERGENCY_BELL: '안전비상벨', SECURITY_LIGHT: '보안등' };
  fetch('/api/map-config').then(r => r.ok ? r.json() : null).then(config => {
    if (config?.vworldKey) L.tileLayer(
      `https://api.vworld.kr/req/wmts/1.0.0/${encodeURIComponent(config.vworldKey)}/Base/{z}/{y}/{x}.png`,
      { attribution: '© VWorld', maxZoom: 19 }).addTo(map);
  }).catch(() => {});

  const facilities = document.getElementById('map-load');
  const crime = document.getElementById('map-crime');
  let request, image, imageUrl;

  function clearCrime() {
    if (image) map.removeLayer(image);
    if (imageUrl) URL.revokeObjectURL(imageUrl);
    image = imageUrl = null;
    crime.textContent = '범죄주의구간 보기';
    crime.setAttribute('aria-pressed', 'false');
  }

  map.on('moveend', () => {
    request?.abort();
    request = null;
    facilities.disabled = crime.disabled = false;
    layers.clearLayers();
    clearCrime();
    message.textContent = '지도를 옮겼어요. 현재 범위를 다시 조회할 수 있어요.';
  });

  function popup(text) {
    const label = document.createElement('p');
    label.textContent = text;
    return label;
  }

  async function load(kind) {
    request?.abort();
    const controller = new AbortController();
    request = controller;
    facilities.disabled = crime.disabled = true;
    message.textContent = '자료를 불러오는 중이에요.';
    const b = map.getBounds();
    const q = new URLSearchParams({south:b.getSouth(),west:b.getWest(),north:b.getNorth(),east:b.getEast()});
    const timer = setTimeout(() => controller.abort(), 20000);
    try {
      const res = await fetch('/api/safety-map' + (kind === 'crime' ? '/crime' : '') + '?' + q,
        {signal:controller.signal});
      if (!res.ok) throw new Error((await res.json().catch(() => ({}))).error || '지도 자료를 불러오지 못했어요.');
      const data = kind === 'crime' ? await res.blob() : await res.json();
      if (request !== controller) return;
      if (kind === 'crime') {
        clearCrime();
        imageUrl = URL.createObjectURL(data);
        image = L.imageOverlay(imageUrl,b,{opacity:.65}).addTo(map);
        image.on('error', () => { clearCrime(); message.textContent = '범죄 지도를 표시하지 못했어요.'; });
        crime.textContent = '범죄주의구간 숨기기';
        crime.setAttribute('aria-pressed', 'true');
        message.textContent = '생활안전지도·경찰청의 과거 범죄 분포 자료를 표시했어요.';
      } else {
        layers.clearLayers();
        for (const f of data.facilities) L.circleMarker([f.lat,f.lng],{radius:4})
          .bindPopup(popup(`${labels[f.kind] || f.kind} ${f.name} · ${f.source} · 수집일 ${f.sourceDownloaded}`)).addTo(layers);
        for (const a of data.accidents) L.polygon(a.polygon.map(([lng,lat])=>[lat,lng]),{color:'#b45309'})
          .bindPopup(popup(`${a.name} · ${a.source} · ${a.year || '기준 연도 미확인'}`)).addTo(layers);
        message.textContent = `시설 ${data.facilities.length}곳 · 사고 영역 ${data.accidents.length}개` +
          (data.limited ? ' · 일부만 표시했어요. 확대해서 다시 조회하세요.' : '');
      }
    } catch (error) {
      if (request === controller) message.textContent = controller.signal.aborted ? '응답 시간이 초과됐어요.' : error.message;
    } finally {
      clearTimeout(timer);
      if (request === controller) {
        request = null;
        facilities.disabled = crime.disabled = false;
      }
    }
  }

  facilities.onclick = () => load('facilities');
  crime.onclick = () => image ? clearCrime() : load('crime');
})();
