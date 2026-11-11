// 모든 화면이 같이 쓰는 작은 도구들 (지도, API 호출, 지점 목록)
const App = (() => {
  const meta = name => document.querySelector(`meta[name="${name}"]`)?.content || '';

  // ── API 호출: POST 에는 CSRF 값을 붙이고, 오류면 서버가 준 문장으로 Error 를 던진다 ──
  async function api(url, { method = 'GET', body, redirectOnAuth = true, signal, csrfRetry = true, timeoutMs = /\/draft(?:$|\?)/.test(url) ? 150000 : /\/(?:candidates|line|check)(?:$|\?)/.test(url) ? 60000 : 30000 } = {}) {
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (method !== 'GET' && meta('_csrf_header')) {
      // HTML 폼의 토큰은 마스킹돼 있고, JS 요청은 쿠키의 원래 토큰을 헤더에 넣는다.
      const token = document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN='));
      if (token) headers[meta('_csrf_header')] = decodeURIComponent(token.slice('XSRF-TOKEN='.length));
    }
    let res, data;
    const controller = new AbortController();
    const abort=()=>controller.abort();
    if(signal?.aborted)abort();else signal?.addEventListener('abort',abort,{once:true});
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      res = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: controller.signal });
      data = await res.json().catch(error => { if (controller.signal.aborted) throw error; return null; });
    } catch (e) {
      if(signal?.aborted){const error=new Error('조회를 중단했어요. 입력한 장소는 유지돼요.');error.code='CANCELLED';throw error;}
      if (controller.signal.aborted) throw new Error('응답을 확인하는 시간이 초과됐어요. 연결 상태를 확인하고 다시 시도해 주세요.');
      throw new Error('인터넷 연결을 확인해 주세요.');
    } finally { clearTimeout(timer);signal?.removeEventListener('abort',abort); }
    if ((res.redirected && res.url.includes('/login')) || res.status===401) { if(redirectOnAuth)location.href = '/login'; const error=new Error('로그인 시간이 끝났어요. 로그인한 뒤 다시 시도해 주세요.');error.code='AUTH';error.status=401;throw error; }
    if(res.ok && data===null)throw new Error('서버 응답을 읽지 못했어요. 입력은 유지돼요. 다시 시도해 주세요.');
    // CSRF 검사에서 거부된 처리는 아직 실행되지 않았다. 쿠키를 갱신한 뒤 한 번만 재전송한다.
    if(res.status===403 && data?.code==='CSRF' && csrfRetry && method!=='GET'){
      await api('/api/session',{signal,timeoutMs:10000,redirectOnAuth:false,csrfRetry:false});
      return api(url,{method,body,redirectOnAuth,signal,timeoutMs,csrfRetry:false});
    }
    if (!res.ok) {
      const error=new Error(data?.error || '문제가 생겼어요. 잠시 뒤 다시 해 주세요.');
      error.status=res.status;
      if(typeof data?.code==='string')error.code=data.code;
      throw error;
    }
    return data;
  }

  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  // ── 지도 ──
  const SEOUL_CITY_HALL = [37.5665, 126.9780];
  function makeMap(el, { center = SEOUL_CITY_HALL, zoom = 16, zoomControl = true } = {}) {
    const map = MapKit.map(el, { center, zoom, zoomControl, attributionControl: true }).setView(center, zoom);
    const key = meta('vworld-key');
    if (key && !window.naver?.maps) {
      L.tileLayer(`https://api.vworld.kr/req/wmts/1.0.0/${key}/Base/{z}/{y}/{x}.png`, {
        maxZoom: 19, attribution: '&copy; VWorld'
      }).addTo(map);
    }
    return map;
  }

  const ICON_NAMES = new Set(['home','map','pin','settings','back','bell','crosswalk','school','cctv','shield','light','warning','flag','user','bag','route','check','help','signal','zone']);
  const svg = name => `<svg class="ui-icon" aria-hidden="true" focusable="false"><use href="/icons/ui.svg#${ICON_NAMES.has(name)?name:'pin'}"></use></svg>`;
  const KIND_ICON = { WALK_JUNCTION:'zone', CROSSWALK:'crosswalk', SCHOOL:'school', CCTV:'cctv', EMERGENCY_BELL:'bell', SCHOOL_ZONE:'zone', CHILD_SAFETY_HOUSE:'shield', SECURITY_LIGHT:'light' };
  const icon = p => svg(p.kind==='CROSSWALK' && p.signal==='신호 있음 추정' ? 'signal' : KIND_ICON[p.kind]);

  function numberMarker(p, { done = false, current = false } = {}) {
    const cls = 'num-pin' + (done ? ' done' : '') + (current ? ' cur' : '');
    return MapKit.marker([p.lat, p.lng], {
      icon: MapKit.divIcon({ className: 'route-marker', html: `<span class="${cls}">${p.seq}</span>`, iconSize: [44, 44], iconAnchor: [22, 22] })
    });
  }

  function flagMarker(latlng, text) {
    return MapKit.marker(latlng, {
      title: text === '🏠' ? '출발지' : text === '🚩' ? '도착지' : String(text),
      // 물방울 모양 핀: 아래 뾰족한 끝이 그 좌표를 가리킨다
      icon: MapKit.divIcon({ className: '', html: `<span class="flag-pin"><span>${text==='🏠'?svg('home'):text==='🚩'?svg('flag'):esc(text)}</span></span>`, iconSize: [36, 36], iconAnchor: [18, 43] })
    });
  }

  const meDot = latlng => MapKit.circleMarker(latlng, { radius: 8, color: '#fff', weight: 3, fillColor: '#2a6df4', fillOpacity: 1 });

  // ── 글자 ──
  const km = m => m >= 1000 ? (m / 1000).toFixed(1) + 'km' : Math.round(m) + 'm';
  const minutes = s => Math.max(1, Math.round(s / 60)) + '분';

  // 지점 목록 (R-04, R-05, N-01)
  function stepsHTML(points, { showType = true, cur = 0, doneUntil = 0, editable = false } = {}) {
    return points.map(p => {
      const type = `${icon(p)} ${esc(p.kindLabel)}${p.signal ? ' · ' + esc(p.signal) : ''}${p.name && p.name !== p.kindLabel && p.kind !== 'CROSSWALK' ? ' · ' + esc(p.name) : ''}`;
      return `<li class="${p.seq === cur ? 'cur' : ''} ${p.seq < doneUntil ? 'done' : ''}" data-seq="${p.seq}">
        <span class="num">${p.seq}</span>
        <span class="grow">${showType ? `<span class="ftype">${type}</span>` : icon(p) + ' '}<span class="gtext">${esc(p.text)}</span>${p.kind === 'WALK_JUNCTION' ? '<small class="hint">서울특별시 도보망 · 공공누리 1유형 · 2020년 기준</small>' : ''}
        ${editable ? `<button type="button" class="link small" data-edit="${p.seq}">고치기</button>` : ''}</span></li>`;
    }).join('');
  }

  // 앱 안 브라우저(카카오톡·네이버 등) 감지 — 설치·알림이 안 될 수 있다 (2026-10-09 결정: 안내만, 막지 않음)
  const isInAppBrowser = () => /KAKAOTALK|NAVER\(inapp|Instagram|FBAN|FBAV|Line\/|DaumApps|everytimeApp|; wv\)/i.test(navigator.userAgent);

  // ── 창(모달)과 잠깐 뜨는 알림 ── (필요할 때 화면에 붙인다)
  let returnFocus, dismissModal, previousOverflow;
  function ensureOverlay() {
    let o = document.getElementById('overlay');
    if (!o) {
      o = document.createElement('div');
      o.id = 'overlay'; o.className = 'overlay';
      o.innerHTML = '<div class="modal" id="modal" role="dialog" aria-modal="true"></div>';
      o.addEventListener('click', e => { if (e.target === o) closeModal(); });
      o.addEventListener('keydown', e => {
        if (e.key === 'Escape') { e.preventDefault(); closeModal(); return; }
        if (e.key !== 'Tab') return;
        const focusable = [...o.querySelectorAll('button:not(:disabled),a[href],input:not(:disabled),textarea:not(:disabled),[tabindex="0"]')].filter(el => el.getClientRects().length);
        const first = focusable[0], last = focusable[focusable.length-1];
        if (!first) { e.preventDefault(); return; }
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      });
      document.body.appendChild(o);
    }
    return o;
  }
  function modal(html, onDismiss) {
    const o = ensureOverlay();
    if (!o.classList.contains('on')) { returnFocus = document.activeElement; previousOverflow = document.body.style.overflow; }
    dismissModal = onDismiss;
    o.querySelector('#modal').innerHTML = html;
    const heading = o.querySelector('h2');
    if (heading) { heading.id='modalTitle'; o.querySelector('#modal').setAttribute('aria-labelledby','modalTitle'); }
    else o.querySelector('#modal').removeAttribute('aria-labelledby');
    o.classList.add('on');
    document.body.style.overflow = 'hidden';
    (o.querySelector('input,textarea,[data-no],button,a[href]'))?.focus({preventScroll:true});
    return o.querySelector('#modal');
  }
  function closeModal() {
    document.getElementById('overlay')?.classList.remove('on');
    document.body.style.overflow = previousOverflow || '';
    returnFocus?.focus({preventScroll:true});
    const dismiss = dismissModal; dismissModal = null; dismiss?.();
  }
  function confirmBox(title, message, okLabel = '확인', danger = false) {
    return new Promise(resolve => {
      const m = modal(`<h2>${esc(title)}</h2><p style="margin:0">${esc(message)}</p>
        <div class="row"><button type="button" class="btn grow" data-no>취소</button>
        <button type="button" class="btn primary grow" data-yes ${danger ? 'style="background:#b00;border-color:#b00"' : ''}>${esc(okLabel)}</button></div>`, () => resolve(false));
      const finish = value => { dismissModal = null; closeModal(); resolve(value); };
      m.querySelector('[data-no]').onclick = () => finish(false);
      m.querySelector('[data-yes]').onclick = () => finish(true);
    });
  }
  let toastTimer;
  function toast(msg) {
    let t = document.getElementById('toast');
    if (!t) { t = document.createElement('div'); t.id = 'toast'; t.className = 'toast'; t.setAttribute('role', 'status'); document.body.appendChild(t); }
    t.textContent = msg; t.classList.add('on');
    clearTimeout(toastTimer); toastTimer = setTimeout(() => t.classList.remove('on'), 2600);
  }

  return { api, esc, makeMap, modal, closeModal, confirmBox, toast, numberMarker, flagMarker, meDot, icon, svg, km, minutes, stepsHTML, meta, isInAppBrowser };
})();

// 홈 화면 설치(PWA)를 위해 서비스 워커를 등록한다 (알림 받기에도 필요)
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => navigator.serviceWorker.register('/sw.js').catch(() => {}));
}
