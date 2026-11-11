// 경로 등록: R-02 장소 입력 → R-03 후보 비교 → R-04 확정 (한 화면 안에서 단계별로)
// 경로선 좌표는 화면에 그리기만 하고 서버에 저장하지 않는다 (TMAP 약관).
(() => {
  const $ = (s, el = document) => el.querySelector(s);
  const $$ = (s, el = document) => [...el.querySelectorAll(s)];
  const ICON = { start: '🏠', end: '🚩' };
  const LABEL = { start: '출발지', end: '도착지' };
  // 후보마다 다른 색 (지도 선과 후보 카드가 같은 색)
  const COLORS = ['#2459d6', '#e07a00', '#0f9b6c'];

  // ── 상태 ──
  const places = { start: null, end: null };    // {name, lat, lng, fromMine}
  const markers = { start: null, end: null };
  let activeForMap = 'start';                     // "지도에서" 탭이 켜진 쪽
  let candidates = [], selected = 0, lastResult = null;
  let tone = 'LOWER';
  let step = 'places';
  let candidateRequest=null;
  let saving=false;
  function lockSaveControls(locked){
    for(const el of $$('#routeName, #nameChips button, #toneSeg button, #backToCompare, #draftRetry'))el.disabled=locked;
    $('#stepConfirm')?.setAttribute('aria-busy',String(locked));
  }
  let placeVersion=0, draftVersion=0, draftId=null, saveRequestId=null;
  const newId=()=>crypto.randomUUID();
  function authRecovery(e){
    if(e.code!=='AUTH')return false;
    const modal=App.modal('<h2>로그인이 필요해요</h2><p>이 화면의 입력은 유지돼요. 새 창에서 로그인한 뒤 돌아와 다시 눌러 주세요.</p><a class="btn primary" href="/login" target="_blank" rel="noopener">새 창에서 로그인</a><button type="button" class="btn ghost" data-close>입력 화면으로 돌아가기</button>');
    modal.querySelector('[data-close]').onclick=App.closeModal;return true;
  }
  const searchVersions={start:0,end:0};
  history.replaceState({...history.state,ssrStep:'places'},'');

  // 한 번에 한 장소만 편집한다. 목적지를 먼저 고르고 출발지를 이어서 정한다.
  function editPlace(which, focus=false) {
    $$('.placebox').forEach(box => { box.hidden=box.dataset.which!==which; });
    $$('[data-edit]').forEach(button=>button.setAttribute('aria-expanded',button.dataset.edit===which));
    centerMark.hidden=!which || !$(`.placebox[data-which="${which}"] [data-tab="map"]`).classList.contains('on');
    if(which) {
      activeForMap=which;
      if(focus)$(`.placebox[data-which="${which}"] [data-q]`).focus({preventScroll:true});
    }
    $('#plannerHint').textContent = which ? `${LABEL[which]}를 검색하거나 지도에서 골라 주세요.` : '핀을 끌어 출입구를 확인한 뒤 길을 찾아 주세요.';
    setTimeout(()=>{pinMap.invalidateSize();fitPins();},0);
  }
  function syncJourney() {
    $('#findBtn').hidden=!(places.start&&places.end);
    $('#journeyEnd').textContent=places.end?.name || (places.end?'장소 이름을 정해 주세요':'어디로 갈까요?');
    $('#journeyStart').textContent=places.start?.name || (places.start?'장소 이름을 정해 주세요':'출발 장소를 골라 주세요');
    $('#swapPlaces').hidden=!(places.start && places.end);
  }
  $$('[data-edit]').forEach(button=>button.onclick=()=>editPlace(button.dataset.edit,true));
  $('#swapPlaces').onclick=()=>{
    const start={...places.start},end={...places.end};
    setPlace('start',end,{fit:false});setPlace('end',start);editPlace(null);syncJourney();
  };

  // ── R-02: 지도 ──
  const pinMap = App.makeMap('pinMap', { zoom: 15 });
  const centerMark=document.createElement('span');centerMark.className='planner-center-mark';centerMark.textContent='＋';centerMark.setAttribute('aria-hidden','true');centerMark.hidden=true;$('#pinMap').append(centerMark);
  function visibleMapCenter(){
    const map=$('#pinMap').getBoundingClientRect(),panel=$('.planner-panel').getBoundingClientRect();
    const mobile=matchMedia('(max-width:700px)').matches;
    const x=mobile?map.width/2:Math.max(map.width/2,(panel.right-map.left+map.width)/2);
    const y=mobile?Math.max(48,Math.min(map.height,panel.top-map.top)/2):map.height/2;
    centerMark.style.left=x+'px';centerMark.style.top=y+'px';return [x,y];
  }
  const pickerObserver=new ResizeObserver(()=>visibleMapCenter());pickerObserver.observe($('.planner-panel'));pickerObserver.observe($('#pinMap'));
  pinMap.on('click', e => {
    const which = $$('.placebox').find(b => !b.hidden && $('[data-tab="map"]', b).classList.contains('on'))?.dataset.which;
    if (which) setPlace(which, { name: places[which]?.name || '', lat: e.latlng.lat, lng: e.latlng.lng });
    else App.toast('출발지나 도착지의 "지도에서"를 먼저 눌러 주세요');
  });

  function setPlace(which, p, { fit = true } = {}) {
    searchVersions[which]++; placeVersion++;candidateRequest?.abort();
    places[which] = { ...p };
    const box = $(`.placebox[data-which="${which}"]`);
    box.classList.add('done');
    $('[data-picked]', box).hidden = false;
    $('[data-chosen]', box).hidden = false;
    const nameInput = $('[data-name]', box);
    nameInput.value = p.name || '';
    nameInput.readOnly = !!p.fromMine;
    $('[data-namechips]', box).hidden = !!p.fromMine;
    const saveBox = $('[data-savebox]', box);
    if (saveBox) saveBox.hidden = !!p.fromMine;
    $('[data-results]', box).hidden = true;

    if (markers[which]) pinMap.removeLayer(markers[which]);
    // 핀을 끌어서 실제 출입구로 옮길 수 있다 (FR-03)
    markers[which] = App.flagMarker([p.lat, p.lng], ICON[which]).addTo(pinMap);
    markers[which].dragging.enable();
    markers[which].on('dragend', ev => {
      const ll = ev.target.getLatLng();
      placeVersion++;candidateRequest?.abort(); places[which].lat = ll.lat; places[which].lng = ll.lng;
      if(places[which].fromMine){places[which].fromMine=false;nameInput.readOnly=false;$('[data-namechips]',box).hidden=false;if(saveBox)saveBox.hidden=false;}
      App.toast(`${LABEL[which]} 핀을 옮겼어요. 저장하면 이 출입구 위치를 사용해요.`);
    });
    if (fit) fitPins();
    $('#placesError').textContent = '';
    syncJourney();
    $('[data-chosen]',box).open=!p.name;
    if(!p.name)nameInput.focus({preventScroll:true});
    else {
      const next=!places.end?'end':!places.start?'start':null;
      editPlace(next,!!next);
      if(!next)$('#findBtn').focus({preventScroll:true});
    }
  }

  function fitPins() {
    const pts = ['start', 'end'].filter(k => places[k]).map(k => [places[k].lat, places[k].lng]);
    if(!pts.length)return;
    const panel=$('.planner-panel'), mobile=matchMedia('(max-width:700px)').matches;
    pinMap.fitBounds(pts,{paddingTopLeft:mobile?[32,72]:[panel.offsetWidth+44,72],paddingBottomRight:mobile?[32,Math.min(panel.offsetHeight+32,pinMap.getSize().y-110)]:[32,32],maxZoom:17});
  }

  function movePlaceTab(event, tabs, current) {
    let index=tabs.indexOf(current);
    if(event.key==='ArrowRight')index=(index+1)%tabs.length;
    else if(event.key==='ArrowLeft')index=(index+tabs.length-1)%tabs.length;
    else if(event.key==='Home')index=0;
    else if(event.key==='End')index=tabs.length-1;
    else return;
    event.preventDefault();tabs[index].click();tabs[index].focus();
  }
  // 탭: 검색 / 내 장소 / 지도에서
  $$('.placebox').forEach(box => {
    const which = box.dataset.which;
    const placeTabs=$$('[data-tab]',box);
    placeTabs.forEach(btn=>{
      btn.id=`place-${which}-tab-${btn.dataset.tab}`;
      btn.setAttribute('role','tab');btn.setAttribute('aria-selected',btn.classList.contains('on'));
      btn.tabIndex=btn.classList.contains('on')?0:-1;
      const panel=$(`[data-panel="${btn.dataset.tab}"]`,box);
      panel.id=`place-${which}-panel-${btn.dataset.tab}`;
      btn.setAttribute('aria-controls',panel.id);panel.setAttribute('role','tabpanel');panel.setAttribute('aria-labelledby',btn.id);
      btn.addEventListener('keydown',e=>movePlaceTab(e,placeTabs,btn));
    });
    $$('[data-tab]', box).forEach(btn => btn.addEventListener('click', () => {
      $$('[data-tab]', box).forEach(b => {b.classList.toggle('on',b===btn);b.setAttribute('aria-selected',b===btn);b.tabIndex=b===btn?0:-1;});
      searchVersions[which]++; placeVersion++;candidateRequest?.abort();
      $$('[data-panel]', box).forEach(p => p.hidden = p.dataset.panel !== btn.dataset.tab);
      $('[data-results]', box).hidden = true;
      centerMark.hidden=btn.dataset.tab!=='map';
      if (btn.dataset.tab === 'map') {
        activeForMap = which;
        // 다른 칸의 "지도에서"는 끈다 (지도를 누르면 어느 쪽인지 헷갈리지 않게)
        $$('.placebox').filter(b => b !== box).forEach(b => {
          if ($('[data-tab="map"]', b).classList.contains('on')) $('[data-tab="search"]', b).click();
        });
        centerMark.hidden=false;
        $('#pinMap').scrollIntoView({ behavior: 'smooth', block: 'center' });
      }
      if (btn.dataset.tab === 'mine') loadMine(box, which);
    }));
    $('[data-center-pick]',box).onclick=()=>{const p=pinMap.containerPointToLatLng(visibleMapCenter());setPlace(which,{name:places[which]?.name||'',lat:p.lat,lng:p.lng});};
    $('[data-adjust]',box).onclick=()=>{editPlace(which);$('[data-tab="map"]',box).click();if(places[which])pinMap.setView([places[which].lat,places[which].lng],18);};
    const q = $('[data-q]', box);
    q.setAttribute('aria-label',LABEL[which]+' 검색');
    q.addEventListener('input',()=>{searchVersions[which]++;$('[data-results]',box).hidden=true;});
    const run = () => search(box, which, q.value);
    $('[data-search]', box).addEventListener('click', run);
    q.addEventListener('keydown', e => { if (e.key === 'Enter' && !e.isComposing && e.keyCode!==229) { e.preventDefault(); run(); } });
    q.addEventListener('keydown',e=>{
      if(e.key!=='ArrowDown'||e.isComposing)return;
      const results=$('[data-results]',box),first=$('[data-i]',results);
      if(!results.hidden&&first){e.preventDefault();first.focus();}
    });
    $('[data-name]', box).addEventListener('input', e => { if (places[which]) {placeVersion++;candidateRequest?.abort();places[which].name = e.target.value;} syncJourney(); });
    $$('[data-chipname]', box).forEach(c => c.addEventListener('click', () => {
      $('[data-name]', box).value = c.dataset.chipname;
      if (places[which]) {placeVersion++;candidateRequest?.abort();places[which].name = c.dataset.chipname;} syncJourney();
    }));
  });

  function checkPlaceResults(items) {
    const valid=Array.isArray(items)&&items.every(it=>it&&
      Number.isFinite(it.lat)&&Math.abs(it.lat)<=90&&Number.isFinite(it.lng)&&Math.abs(it.lng)<=180&&
      typeof it.isPlace==='boolean'&&
      (it.title==null||typeof it.title==='string')&&(it.address==null||typeof it.address==='string')&&
      (it.isPlace?typeof it.title==='string'&&it.title.trim().length>0:typeof it.address==='string'&&it.address.trim().length>0));
    if(!valid)throw new Error('검색 결과의 위치를 확인하지 못했어요. 다시 검색하거나 지도에서 골라 주세요.');
    return items;
  }
  async function search(box, which, query) {
    const token=++searchVersions[which];
    const res = $('[data-results]', box);
    if (query.trim().length < 2) { res.hidden = false; res.innerHTML = '<div class="hint">두 글자 이상 써 주세요.</div>'; return; }
    res.hidden = false; res.innerHTML = '<div class="hint"><span class="spinner"></span> 찾는 중…</div>';
    try {
      const items = await App.api('/api/places/search?q=' + encodeURIComponent(query.trim()));
      if(token!==searchVersions[which])return;
      checkPlaceResults(items);
      if (!items.length) { res.innerHTML = '<div class="hint">찾지 못했어요. "지도에서"를 눌러 직접 골라 보세요.</div>'; return; }
      res.innerHTML = items.map((it, i) => `<button type="button" class="item" data-i="${i}">
          <span><b>${App.esc(it.title || it.address)}</b><small>${App.esc(it.title ? it.address : '도로명 주소')}</small></span><span class="chev">›</span></button>`).join('');
      $$('[data-i]', res).forEach(b => b.addEventListener('click', () => {
        const it = items[+b.dataset.i];
        // 장소 이름(학교 등)은 그대로 쓰고, 주소는 저장하지 않으므로 이름을 직접 쓰게 한다 (예: 집)
        setPlace(which, { name: it.isPlace ? it.title.slice(0, 30) : '', lat: it.lat, lng: it.lng });
      }));
    } catch (e) {
      if(token===searchVersions[which])res.innerHTML = `<div class="error">${App.esc(e.message)}</div>`;
    }
  }

  let mineCache = null;
  async function loadMine(box, which) {
    const list = $('[data-mine]', box);
    try {
      mineCache = mineCache || await App.api('/api/places' + (CFG.ownerId ? '?ownerId=' + CFG.ownerId : ''));
      if (!mineCache.length) { list.innerHTML = '<div class="hint">아직 저장한 장소가 없어요. 경로를 등록할 때 저장돼요.</div>'; return; }
      list.innerHTML = mineCache.map((p, i) => `<button type="button" class="item" data-i="${i}"><span>${App.svg('pin')} ${App.esc(p.name)}</span><span class="chev">›</span></button>`).join('');
      $$('[data-i]', list).forEach(b => b.addEventListener('click', () =>
        setPlace(which, { ...mineCache[+b.dataset.i], fromMine: true })));
    } catch (e) {
      list.innerHTML = `<div class="error">${App.esc(e.message)}</div>`;
    }
  }

  editPlace('end');

  // 다시 등록: 기존 출발지·도착지로 채운다. 비로그인으로 보던 경로를 가입 뒤 이어서 저장할 때도 같다.
  const pre = CFG.pending || (CFG.replaceStart ? { start: CFG.replaceStart, end: CFG.replaceEnd } : null);
  if (pre) {
    setPlace('start', { ...pre.start, fromMine: false }, { fit: false });
    setPlace('end', { ...pre.end, fromMine: false });
  }

  $('#cancelCandidates').onclick=()=>candidateRequest?.abort();
  let quotaVersion=0;
  async function refreshGuestQuota() {
    if(!CFG.guest)return;
    const version=++quotaVersion;
    $('#guestLeft').textContent='확인 중';
    try {
      const result=await App.api('/api/explore/quota',{redirectOnAuth:false});
      if(version!==quotaVersion)return;
      if(!Number.isInteger(result.left)||result.left<0)throw new Error('잘못된 조회 횟수');
      $('#guestLeft').textContent=result.left;
    } catch(e) { if(version===quotaVersion)$('#guestLeft').textContent='확인 못함'; }
  }
  $('#retryCandidates').onclick=()=>$('#findBtn').click();
  // ── R-02 → R-03: 경로 찾기 ──
  $('#findBtn').addEventListener('click', async () => {
    const err = $('#placesError');
    for (const k of ['start', 'end']) {
      if (!places[k]) { err.textContent = `${LABEL[k]}를 골라 주세요.`; editPlace(k,true); return; }
      places[k].name = (places[k].name || '').trim();
      if (!places[k].name) {
        err.textContent = `${LABEL[k]}의 이름을 써 주세요. (예: 집)`;
        editPlace(k);$(`.placebox[data-which="${k}"] [data-chosen]`).open=true;
        $(`.placebox[data-which="${k}"] [data-name]`).focus(); return;
      }
    }
    if (pinMap.distance([places.start.lat, places.start.lng], [places.end.lat, places.end.lng]) < 30) {
      err.textContent = '출발지와 도착지가 너무 가까워요.'; return;
    }
    ++quotaVersion; // 이전 조회의 늦은 횟수 응답이 새 결과를 덮어쓰지 않게 한다.
    const requestVersion=placeVersion,request=new AbortController();candidateRequest=request;
    const started=Date.now();err.textContent='';$('#retryCandidates').hidden=true;$('#cancelCandidates').hidden=false;
    $('#candidateProgress').textContent='후보 길과 주변 자료의 응답을 기다리고 있어요.';
    const progress=setInterval(()=>{const seconds=Math.floor((Date.now()-started)/1000);if(seconds===10||seconds===30)$('#candidateProgress').textContent=`${seconds}초째 응답을 기다리고 있어요. 중단하거나 기다릴 수 있어요.`;},1000);
    const btn = $('#findBtn');
    btn.disabled = true; btn.innerHTML = '<span class="spinner"></span> 공공데이터로 길을 살펴보는 중…';
    try {
      lastResult = await App.api(CFG.candidatesUrl, { method: 'POST', redirectOnAuth:false, signal:request.signal, body: { start: places.start, end: places.end } });
      if (CFG.guest && Number.isInteger(lastResult.left) && lastResult.left>=0) $('#guestLeft').textContent = lastResult.left;
      if(request.signal.aborted){const error=new Error('조회를 중단했어요. 입력한 장소는 유지돼요.');error.code='CANCELLED';throw error;}
      if(requestVersion!==placeVersion || step!=='places'){err.textContent='장소가 바뀌었어요. 현재 장소로 다시 찾아 주세요.';return;}
      if(!Array.isArray(lastResult.candidates)||!lastResult.candidates.length)throw new Error('찾은 후보 길이 없어요. 출입구 위치를 바꾸거나 다시 시도해 주세요.');
      clearCrimeMap(); publicData?.clear();
      candidates = lastResult.candidates; selected = lastResult.suggestion?.index ?? 0;
      // 가입 전에 고르던 후보가 있으면 그것을 고른 채로
      if (CFG.pending && CFG.pending.option !== undefined) selected = Math.max(0, candidates.findIndex(c => c.options.includes(CFG.pending.option)));
      show('compare');
      renderCompare();
    } catch (e) {
      void refreshGuestQuota(); // 경로를 재요청하지 않고 사용된 횟수만 읽는다.
      if(e.code==='CANCELLED'){err.textContent=requestVersion===placeVersion?e.message:'장소가 바뀌어 조회 대기를 중단했어요. 현재 장소로 다시 찾아 주세요.';$('#retryCandidates').hidden=false;return;}
      $('#retryCandidates').hidden=false;
      if(authRecovery(e))return;
      if (e.message.includes('가입')) {
        const m = App.modal(`<h2>오늘 볼 수 있는 횟수를 다 썼어요</h2><p style="margin:0">${App.esc(e.message)}</p>
          <a class="btn primary" href="/signup">가입하기</a><button type="button" class="btn ghost" data-close>닫기</button>`);
        m.querySelector('[data-close]').onclick = App.closeModal;
      } else err.textContent = e.message;
    } finally {
      clearInterval(progress);if(candidateRequest===request)candidateRequest=null;
      $('#cancelCandidates').hidden=true;$('#candidateProgress').textContent='';
      btn.disabled = false; btn.textContent = '후보 길 비교하기';
    }
  });

  // ── R-03 후보 비교 ──
  let candMap, candLayer, publicData, evidenceLayer;
  let evidenceKind="CROSSWALK", unknownOnly=false, evidenceExpanded=false, evidenceCandidate=-1;
  function showEvidence(kind, onlyUnknown=false){
    evidenceKind=kind;unknownOnly=onlyUnknown;evidenceExpanded=false;renderEvidence();
    const features=evidenceFeatures(candidates[selected]);
    if(features.length)candMap.fitBounds(features.flatMap(f=>f.line||[[f.lat,f.lng]]),{padding:[40,40],maxZoom:18});
    $(`#evidenceFilters [data-evidence="${kind}"]`)?.focus({preventScroll:true});
    $('#evidenceFilters').scrollIntoView({behavior:'smooth',block:'nearest'});
  }
  let crimeLayer = null, crimeUrl = null, crimeRequest = 0;
  function clearCrimeMap() {
    crimeRequest++;
    $('#crimeMapToggle').disabled = false;
    if (crimeLayer) candMap.removeLayer(crimeLayer);
    if (crimeUrl) URL.revokeObjectURL(crimeUrl);
    crimeLayer = null; crimeUrl = null;
    $('#crimeMapToggle').setAttribute('aria-pressed', 'false');
    $('#crimeMapToggle').textContent = '제공기관 원본 지도 보기';
  }
  $('#crimeMapToggle').addEventListener('click', async () => {
    if (crimeLayer) { clearCrimeMap(); $('#crimeMapHint').textContent = '원본 지도를 숨겼어요. 계산된 경로 비교값은 계속 볼 수 있어요.'; return; }
    const btn = $('#crimeMapToggle');
    const request = ++crimeRequest;
    btn.disabled = true;
    $('#crimeMapHint').textContent = '제공기관 지도를 불러오는 중이에요…';
    // 지도 중심이나 현재 위치는 보내지 않는다. 후보 경로의 범위만 사용한다.
    const bounds = MapKit.latLngBounds(candidates.flatMap(c => c.line)).pad(.1);
    const query = new URLSearchParams({south: bounds.getSouth(), west: bounds.getWest(), north: bounds.getNorth(), east: bounds.getEast()});
    try {
      const response = await fetch('/api/risk-map/crime?' + query);
      if (!response.ok) {
        const error = await response.json().catch(() => ({}));
        throw new Error(error.error || '지도를 불러오지 못했어요. 미확인으로 표시해요.');
      }
      const blob = await response.blob();
      if (request !== crimeRequest) return;
      crimeUrl = URL.createObjectURL(blob);
      crimeLayer = MapKit.imageOverlay(crimeUrl, bounds, {opacity: .7, pane: 'crimeMapPane'}).addTo(candMap);
      btn.setAttribute('aria-pressed', 'true'); btn.textContent = '제공기관 원본 지도 숨기기';
      $('#crimeMapHint').textContent = '제공기관 원본의 여러 색은 과거 범죄 밀도 등급이에요. 앱의 진분홍 경로 표시는 아래 범죄 자료 항목에서 볼 수 있어요.';
    } catch (e) { if (request === crimeRequest) { clearCrimeMap(); $('#crimeMapHint').textContent = e.message; } }
    finally { if (request === crimeRequest) btn.disabled = false; }
  });
  function renderCompare() {
    const restoreCardFocus=document.activeElement?.matches("#cands [data-c]");
    if (!candMap) { candMap = App.makeMap('candMap');
      const crimePane = candMap.createPane('crimeMapPane');
      crimePane.style.zIndex = 250; crimePane.style.pointerEvents = 'none';
      candLayer = MapKit.layerGroup().addTo(candMap);
      evidenceLayer = MapKit.layerGroup().addTo(candMap);
      publicData=MapData.attach(candMap,'#candidateLayers',()=>MapKit.latLngBounds(candidates.flatMap(c=>c.line)).pad(.15)); }
    setTimeout(() => candMap.invalidateSize(), 0);
    const single = candidates.length === 1;
    $('#stepCompare .compare-intro h2').textContent = single ? '이 길을 살펴볼까요?' : `후보 ${candidates.length}개, 어떤 길로 갈까요?`;
    $('#stepCompare .compare-intro p').textContent = lastResult.suggestion?.summary ? `${single?'':`${lastResult.suggestion.index+1}번 길 제안 · `}${lastResult.suggestion.summary}` : '시간과 주의할 곳을 함께 보고 골라 주세요.';
    $('#stepCompare .map-caption').textContent = single ? '지도에서 걸어갈 길과 주변 시설을 확인해 주세요.' : '길을 누르면 선택할 수 있어요 · 굵은 선이 선택한 길이에요';
    $('#title').textContent = single ? '이 길의 안전 정보' : '후보 길 비교';
    $('#multiView').hidden = single;
    $('#singleView').hidden = !single;
    $('#cands').hidden = single;
    const pick = $('#pickBtn');
    if (pick) pick.textContent = single ? '이 길로 정하기' : `${selected+1}번 ${candidates[selected].label} 길로 정하기`;
    if($('#guestSaveBtn'))$('#guestSaveBtn').textContent=`${selected+1}번 길 저장하기 · 가입`;
    drawCandidates();
    renderSelection();
    renderEvidence();
    renderSuggestion();
    const chosen=candidates[selected];
    $('#comparisonStatus').innerHTML=`<b>먼저 확인해 주세요</b><span>${chosen.crossings.unknown?`이 길의 건넘 ${chosen.crossings.total}곳 중 ${chosen.crossings.unknown}곳은 신호 미확인이에요.`:chosen.crossings.total?'신호 정보는 위치로 추정한 자료예요.':'경로 안내에 건넘 표시가 없어요. 실제 횡단보도가 없다는 뜻은 아니에요.'} 지도에서 건너는 곳과 보행신호등 위치를 함께 확인해 주세요.</span><small>시설 수와 자료 확인 비율은 안전 점수가 아니에요.</small>`;
    const quick=document.createElement('div');quick.className='evidence-shortcuts';
    quick.innerHTML=`<button type="button" data-check="CROSSWALK">${chosen.crossings.unknown?`신호 미확인 ${chosen.crossings.unknown}곳 위치 보기`:'건너는 곳 위치 보기'}</button><button type="button" data-check="ACCIDENT">사고 영역 ${chosen.accidents.length}개 위치 보기</button><button type="button" data-check="CRIME">범죄 자료 구간 보기</button>`;
    const labels={AMBIGUOUS_SIGNAL_LINK:'여러 횡단보도와 가까워 신호 연결 미확인',UNMATCHED_CROSSWALK:'횡단보도 연결 미확인',UNLINKED_NEARBY_SIGNAL:'주변 보행등 연결 미검증',NO_NEARBY_SIGNAL:'보행등 자료 미확인'};
    const reasons=Object.entries(chosen.crossings.causes||{}).filter(([key,n])=>labels[key]&&n).map(([key,n])=>`${labels[key]} ${n}곳`);
    if(reasons.length){const note=document.createElement('small');note.textContent=reasons.join(' · ');$('#comparisonStatus').append(note);}
    $('#comparisonStatus').append(quick);
    quick.querySelectorAll('[data-check]').forEach(b=>b.onclick=()=>showEvidence(b.dataset.check,b.dataset.check==='CROSSWALK'&&chosen.crossings.unknown>0));
    if (single) return renderSingle(candidates[0]);

    $('#cands').innerHTML = candidates.map((c, i) => `<button type="button" class="cand ${i === selected ? 'on' : ''}" data-c="${i}" aria-pressed="${i === selected}">
        <span class="choice-top"><span class="route-dot" style="background:${COLORS[i]}">${i+1}</span><b>${App.esc(c.label)}</b>${lastResult.suggestion?.index===i?'<small class="choice-proposal">비교 제안</small>':''}<span class="choice-check">${i===selected?'✓ 선택':''}</span></span>
        <span class="choice-time">${App.minutes(c.time)} <small>· ${App.km(c.distance)}</small></span>
        <span class="choice-facts"><span>횡단보도 ${c.crossings.total}곳</span><span>사고 영역 ${c.accidents.length}개</span></span>
        <span class="choice-crime">${App.esc(crimeText(c))}</span>
        ${crimeBadge(c) ? `<span class="tag">${App.esc(crimeBadge(c))}</span>` : ''}</button>`).join('');
    $$('#cands [data-c]').forEach(b => b.addEventListener('click', () => { selected = +b.dataset.c; renderCompare(); }));
    const chosenCard=$(`#cands [data-c="${selected}"]`);
    if(matchMedia("(max-width:700px)").matches)$("#cands").scrollLeft=chosenCard.offsetLeft-$("#cands [data-c]").offsetLeft;
    if(restoreCardFocus)chosenCard.focus({preventScroll:true});
    const rows = [
      ['거리 · 시간', c => `${App.km(c.distance)} · ${App.minutes(c.time)}`],
      ['건너는 횡단보도', c => `${c.crossings.total}곳`],
      ['└ 신호 있음 추정 / 미확인', c => `${c.crossings.estimated} / ${c.crossings.unknown}`],
      ['어린이보호구역 근처', c => c.schoolZones.length + '곳'],
      ['사고다발지역', c => c.accidents.length ? `<span style="color:var(--warn)">${c.accidents.length}곳</span>` : '0곳'],
      ['범죄 7~10등급 구간 (추정)', c => App.esc(crimeText(c))],
      ['범죄 등급 확인 비율', c => c.crime?.available ? `${c.crime.coveragePercent}% · 미확인 약 ${c.crime.unknownMeters}m` : '미확인'],
      ['CCTV·비상벨', c => c.safety + '개'],
      ['주변 보행등 위치 (연결 미검증)', c => (c.mapFeatures||[]).filter(f=>f.kind==='SIGNAL').length + '곳'],
      ['주변 보안등 (지역별 누락)', c => {const n=(c.mapFeatures||[]).filter(f=>f.kind==='LIGHT').length;return n?n+'곳':'위치 미확인';}],
      ['다음 단계의 길 안내 지점', c => c.points.length + '곳'],
    ];
    let h = '<tr><th></th>' + candidates.map((c, i) => `<th class="${i === selected ? 'sel' : ''}"><span style="color:${COLORS[i]}">●</span> ${App.esc(c.label)}</th>`).join('') + '</tr>';
    const metricKinds=[null,'CROSSWALK','CROSSWALK','SCHOOL_ZONE','ACCIDENT','CRIME','CRIME','SAFETY','SIGNAL','LIGHT',null];
    rows.forEach(([label,f],row)=>{h+=`<tr><td>${label}</td>`+candidates.map((c,i)=>`<td class="${i===selected?'sel':''}">${metricKinds[row]?`<button type="button" class="metric-location" data-metric="${metricKinds[row]}" data-candidate="${i}" data-unknown="${row===2}" aria-label="${i+1}번 ${App.esc(c.label)} ${label} 위치 보기">${f(c)} <small>위치 보기</small></button>`:f(c)}</td>`).join('')+'</tr>';});
    $('#cmpTable').innerHTML = h;
    $$('#cmpTable [data-metric]').forEach(b=>b.onclick=()=>{const kind=b.dataset.metric,unknown=b.dataset.unknown==='true';selected=+b.dataset.candidate;renderCompare();showEvidence(kind,unknown);});
    $('#mergeHint').hidden = !lastResult.merged;
    $('#diffBox').hidden=false;
    $('#diffHeading').textContent=`${selected+1}번 ${candidates[selected].label} 길과 비교해요`;
    $('#diffTag').textContent='수치 비교';
    $('#diffText').innerHTML=candidates.map((c,i)=>i===selected?'':pairComparison(c,i)).join('');
  }
  function pairComparison(c,i){
    const base=candidates[selected],parts=[],same=[];
    const distance=c.distance-base.distance,time=Math.round((c.time-base.time)/60);
    parts.push(distance===0?'거리가 같아요':`${Math.abs(distance)}m ${distance>0?'더 길어요':'더 짧아요'}`);
    if(time!==0)parts.push(`약 ${Math.abs(time)}분 ${time>0?'더 걸려요':'덜 걸려요'}`);
    for(const [name,a,b] of [['건너는 곳',c.crossings.total,base.crossings.total],['신호 미확인',c.crossings.unknown,base.crossings.unknown],['겹치는 사고 영역',c.accidents.length,base.accidents.length]]){
      const d=a-b;if(d===0)same.push(`${name} ${a}곳`);else parts.push(`${name}이 ${Math.abs(d)}곳 ${d>0?'더 많아요':'더 적어요'}`);
    }
    const crime=crimePairShorter(c,base)?'과거 범죄 자료의 주의 참고 구간은 더 짧아요.':crimePairShorter(base,c)?'과거 범죄 자료의 주의 참고 구간은 선택한 길이 더 짧아요.':!candidates.every(x=>x.crime?.available&&x.crime.coveragePercent>=90)?'확인하지 못한 범죄 자료가 있어, 이 항목으로 어느 길이 나은지 정하지 않았어요.':c.crime.upperGradeMeters===base.crime.upperGradeMeters&&c.crime.unknownMeters===0&&base.crime.unknownMeters===0?'과거 자료의 주의 참고 구간 길이는 같아요.':'길이 차이가 작거나 미확인 범위와 겹쳐서 뚜렷한 우열을 정하지 않았어요.';
    return `<div class="pair-comparison"><b>${i+1}번 ${App.esc(c.label)} → ${selected+1}번 ${App.esc(base.label)} 기준</b><p>${parts.map(App.esc).join(' · ')}</p><small>${same.length?`같은 항목: ${same.map(App.esc).join(' · ')}<br>`:''}${crime}</small></div>`;
  }

  function crimeText(c) {
    const d=c.crime;
    if(!d?.available && d?.segments?.some(s=>s.band==='NO_DISPLAY'))return '공식 지도에 등급 표시 없음 · 의미 미확정';
    // 쉬운 말로: "범죄 주의 구간"은 공식 7~10등급 구간(아래 '범죄 자료는 어떻게 읽나요?'에 설명). 0m 여도 안전하다는 뜻은 아니다
    if(!d?.available)return '범죄주의구간 미확인';
    const grade=Number.isInteger(d.maxGrade)&&d.maxGrade>=1&&d.maxGrade<=10?` · 최고 ${d.maxGrade}등급`:'';
    const pass=d.upperGradeMeters>0?`약 ${d.upperGradeMeters}m 지나요`:'지나지 않아요';
    return `범죄 주의 구간 ${pass}${grade} · 자료 ${d.coveragePercent}% 확인 (앱 추정)`;
  }
  function crimePairShorter(a,b) {
    return candidates.every(x=>x.crime?.available&&x.crime.coveragePercent>=90)
      && a.crime.upperGradeMeters+a.crime.unknownMeters+10<b.crime.upperGradeMeters;
  }
  function crimeBadge(c) {
    if (candidates.length<2 || !candidates.every(x => x.crime?.available && x.crime.coveragePercent>=90)) return '';
    // 미확인 부분을 모두 7~10등급으로 가정해도 다른 후보보다 짧을 때만 차이를 표시한다.
    const ceiling=c.crime.upperGradeMeters+c.crime.unknownMeters+10;
    return candidates.filter(x=>x!==c).every(x=>ceiling<x.crime.upperGradeMeters) ? '과거 자료의 주의 참고 구간이 더 짧아요' : '';
  }
  function crimeDistribution(d) {
    if(!Array.isArray(d?.gradeMeters)||d.gradeMeters.length!==10||d.gradeMeters.some(n=>!Number.isFinite(n)||n<0))return '';
    const rows=d.gradeMeters.map((meters,i)=>`<tr><th scope="row">${i+1}등급</th><td>약 ${Math.round(meters)}m</td></tr>`).join('');
    return `<details class="crime-distribution"><summary>공식 등급별 경로 겹침 보기</summary><p class="hint">등급은 제공기관 기준이며 길이는 앱의 근사 계산이에요. 0m는 이 경로에서 해당 등급을 관측하지 않았다는 뜻이에요. 빈 영역·판독 실패는 아래 합계에 포함하지 않아요.</p><table><caption>선택한 경로의 등급별 겹침 길이</caption><thead><tr><th scope="col">공식 지도 등급</th><th scope="col">경로 겹침 추정</th></tr></thead><tbody>${rows}</tbody></table></details>`;
  }
  function renderSelection() {
    const c=candidates[selected];
    $('#selectionSummary').innerHTML=`<details><summary><span class="route-dot" style="background:${COLORS[selected]}">${selected+1}</span> ${App.esc(c.label)} 선택 · 정보 보기</summary>
      <p>${c.crossings.unknown>0?`신호 미확인 건넘 ${c.crossings.unknown}곳 · 실제 보행 신호를 확인해 주세요.`:c.crossings.total?'신호는 추정 정보예요. 실제 보행 신호를 확인해 주세요.':'경로 안내에 건넘 표시가 없어요. 실제 도로를 확인해 주세요.'}</p>
      <p>${App.esc(crimeText(c))}</p><small>자료 확인 비율은 안전 점수가 아니에요. ${c.crime?.unknownMeters>0?`약 ${c.crime.unknownMeters}m는 등급 표시가 없거나 판독하지 못한 길이예요.`:''}</small>${crimeDistribution(c.crime)}</details>`;
  }

  // 후보 1개: 비교표 대신 위험요소 카드, 주의할 곳을 위로 (HANDOFF 5-2)
  function renderSingle(c) {
    const items = [
      c.crossings.unknown > 0 ? [true, `❔ 신호를 확인하지 못한 횡단보도 ${c.crossings.unknown}곳 — 멈춰서 꼭 확인해요`]
        : [false, c.crossings.total ? '✔ 건너는 횡단보도 모두 신호 있음(추정)' : '경로 안내에 건넘 표시 없음 · 실제 도로 확인 필요'],
      c.accidents.length ? [true, `⚠️ 과거 사고 영역 ${c.accidents.length}개와 겹쳐요`] : [false, '등록된 사고 영역과 겹침 없음 · 사고가 없다는 뜻은 아니에요'],
      [!c.crime?.available || c.crime.upperGradeMeters>0, crimeText(c)],
      [false, `어린이보호구역 근처 ${c.schoolZones.length}곳`],
      [false, `🚦 건너는 횡단보도 ${c.crossings.total}곳 (신호 있음 추정 ${c.crossings.estimated} / 미확인 ${c.crossings.unknown})`],
      [false, `🛡️ 길가의 CCTV·비상벨 ${c.safety}개`],
      [false, `📏 ${App.km(c.distance)} · 걸어서 ${App.minutes(c.time)}`],
    ];
    if (c.turnsWithoutFacility > 0) items.push([false, `🧭 꺾는 곳 ${c.turnsWithoutFacility}곳은 근처에 표시할 시설이 없어 지도의 내 위치로 안내해요`]);
    items.sort((a, b) => b[0] - a[0]);
    $('#riskCard').innerHTML = items.map(([caution, t]) => `<div class="${caution ? 'caution' : ''}">${App.esc(t)}</div>`).join('');
  }

  function drawCandidates() {
    candLayer.clearLayers();
    // 고른 후보를 맨 위에 굵게
    const order = candidates.map((c, i) => i).filter(i => i !== selected).concat([selected]);
    for (const i of order) {
      const on = i === selected;
      MapKit.polyline(candidates[i].line, { color: '#fff', weight: on ? 10 : 7, opacity: .9 }).addTo(candLayer);
      MapKit.polyline(candidates[i].line, { color: COLORS[i], weight: on ? 6 : 4, opacity: on ? .95 : .55 })
        .on('click', () => { selected = i; renderCompare(); }).addTo(candLayer);
    }
    App.flagMarker([places.start.lat, places.start.lng], ICON.start).addTo(candLayer);
    App.flagMarker([places.end.lat, places.end.lng], ICON.end).addTo(candLayer);
    const all = candidates.flatMap(c => c.line);
    candMap.fitBounds(all, { padding: [30, 30] });
  }

  const EVIDENCE={
    CROSSWALK:['건너는 곳','crosswalk','#b76810','TMAP이 건넌다고 안내한 위치예요. 신호는 보행등 자료로 추정하며, 미확인은 신호 없음과 달라요.'],
    SIGNAL:['보행등 위치','signal','#287d46','경로에서 50m 안의 보행등 위치예요. 가까운 신호가 어느 건널목에 연결되는지 확인되지 않아, 신호 미확인 건넘 수를 자동으로 줄이지 않아요.'],
    LIGHT:['보안등','light','#7d6820','경로에서 50m 안의 보안등 설치 위치예요. 구별 자료 확보 범위가 달라요. 표시가 없어도 실제로 없는 것은 아니며, 현재 밝기나 작동 상태를 뜻하지 않아요. 보안등 수는 길 제안 순위에 쓰지 않아요. 서대문·강남은 스마트폴 일부 자료만 있어요.'],
    SCHOOL_ZONE:['보호구역 근처','zone','#087d73','보호구역 대상 시설과 300m 참고 반경이에요. 실제 지정 도로 경계와 달라요.'],
    ACCIDENT:['사고 영역','warning','#b95324','이 경로와 겹치는 과거 사고다발지역이에요. 영역을 누르면 이름과 자료 연도를 볼 수 있어요.'],
    SAFETY:['CCTV·비상벨','shield','#6756a6','경로에서 50m 안의 시설을 같은 종류 20m 이내 중복을 합쳐 세었어요. 많다고 더 안전한 길로 판정하지 않아요.'],
    CRIME:['범죄 자료','warning','#b63279','진분홍: 과거 7~10등급과 겹치는 참고 구간 · 회색 점선: 공식 등급 표시 없음 또는 판독 불가. 현재 위험이나 범죄 확률을 뜻하지 않아요.']
  };
  function evidenceFeatures(c){
    if(evidenceKind!=='CRIME')return (c.mapFeatures||[]).filter(f=>f.kind===evidenceKind && (!unknownOnly || f.verification!=='ESTIMATED')).map((f,i)=>['SIGNAL','LIGHT'].includes(evidenceKind)?{...f,title:`${f.title} ${i+1}`}:f);
    return (c.crime?.segments||[]).filter(s=>s.band!=='OBSERVED').map((s,i)=>({id:'crime-'+i,kind:'CRIME',title:s.band==='UPPER'?`주의 참고 구간 ${i+1}`:s.band==='NO_DISPLAY'?`공식 지도에 등급 표시 없는 구간 ${i+1}`:`등급 판독 불가 구간 ${i+1}`,lat:s.line[0][0],lng:s.line[0][1],line:s.line,band:s.band,detail:s.band==='UPPER'?'과거 자료의 7~10등급에 대응해요. 지금 위험하다는 판정이 아니에요.':s.band==='NO_DISPLAY'?'공식 지도 응답에 색이 없는 구간이에요. 등급 영역 밖이거나 누락일 수 있어요. 범죄 없음이나 낮은 등급으로 단정하지 않아요.':'지도 경계·색·해상도 때문에 등급을 읽지 못했어요.',source:'생활안전지도 · 경찰청'}));
  }
  function renderEvidence(){
    if(evidenceCandidate!==selected){evidenceExpanded=false;evidenceCandidate=selected;}
    const c=candidates[selected],counts={CROSSWALK:c.crossings.total,SIGNAL:(c.mapFeatures||[]).filter(f=>f.kind==='SIGNAL').length,LIGHT:(c.mapFeatures||[]).filter(f=>f.kind==='LIGHT').length,SCHOOL_ZONE:c.schoolZones.length,ACCIDENT:c.accidents.length,SAFETY:c.safety,CRIME:c.crime?.available?`약 ${c.crime.upperGradeMeters}m`:'미확인'};
    if(!counts.LIGHT)counts.LIGHT='미확인';
    $('#evidenceFilters').innerHTML=Object.entries(EVIDENCE).map(([key,[name,icon]])=>`<button type="button" data-evidence="${key}" aria-pressed="${key===evidenceKind}">${App.svg(icon)}<span>${name}</span><small>${counts[key]}</small></button>`).join('');
    $$('[data-evidence]').forEach(b=>b.onclick=()=>{showEvidence(b.dataset.evidence);});
    $('#evidenceNote').textContent=(unknownOnly?'신호 미확인 건넘만 표시하고 있어요. ':'')+EVIDENCE[evidenceKind][3];
    $('#evidenceMapLabel').innerHTML=`<b>${selected+1}번 ${App.esc(c.label)}</b> · ${EVIDENCE[evidenceKind][0]}${evidenceKind==='CRIME'?'<br><span class="legend-upper">━ 주의 참고</span> <span class="legend-unknown">┄ 미확인</span>':''}`;
    evidenceLayer.clearLayers();
    const features=evidenceFeatures(c),markers=new Map();
    if(evidenceKind==='CRIME')for(const segment of c.crime?.segments||[]){
      if(segment.band==='OBSERVED')continue;
      MapKit.polyline(segment.line,{color:segment.band==='UPPER'?'#b63279':'#6b7280',weight:7,opacity:1,dashArray:segment.band!=='UPPER'?'6 6':null}).addTo(evidenceLayer);
    }
    features.forEach((f,i)=>{
      const date=f.kind==='CRIME'?'제공기관 수집연도 2025년 · 실제 사건 집계기간 미확인':f.kind==='ACCIDENT'?'':f.baseDate?`${f.baseDateLabel||'자료 기준일'}: ${f.baseDate}`:'자료 기준일 미확인';
      const popup=`<b>${App.esc(f.title)}</b><br>${App.esc(f.detail)}<br><small>${App.esc(f.source)}${date?'<br>'+App.esc(date):''}</small>`;
      if(f.geometry){try{MapKit.geoJSON(JSON.parse(f.geometry),{style:{color:'#b95324',weight:2,fillOpacity:.12}}).bindPopup(popup).addTo(evidenceLayer);}catch(e){}}
      if(f.kind==='SCHOOL_ZONE')MapKit.circle([f.lat,f.lng],{radius:300,color:'#087d73',weight:1,fillOpacity:.045,dashArray:'5 5'}).addTo(evidenceLayer);
      const marker=MapKit.marker([f.lat,f.lng],{title:f.title,icon:MapKit.divIcon({className:'map-poi evidence-poi',html:`<span style="--poi-color:${EVIDENCE[evidenceKind][2]}">${evidenceKind==='CROSSWALK'?i+1:App.svg(EVIDENCE[evidenceKind][1])}</span>`,iconSize:[44,44],iconAnchor:[22,22]})}).bindPopup(popup).addTo(evidenceLayer);
      markers.set(f.id,marker);
    });
    const visible=features.slice(0,evidenceExpanded?50:5);
    $('#evidenceList').innerHTML=visible.length?visible.map(f=>`<button type="button" data-spot="${App.esc(f.id)}"><span><b>${App.esc(f.title)}</b><small>${App.esc(f.detail)}</small></span><span class="spot-action">지도 보기 ›</span></button>`).join(''):`<p class="hint">${evidenceKind==='CRIME'?(c.crime?.available?'확인된 구간에 7~10등급 겹침이 없어요. 현재 안전함을 뜻하지 않아요.':'자료를 확인하지 못해 구간 위치를 표시할 수 없어요.'):'이번 경로의 비교 자료에는 표시할 위치가 없어요. 현장에 시설이나 위험이 없다는 뜻은 아니에요.'}</p>`;
    if(!evidenceExpanded && features.length>5){
      const more=document.createElement('button');more.type='button';more.className='btn ghost';more.textContent=`목록 더 보기 · ${features.length}곳 중 5곳 표시`;
      more.onclick=()=>{evidenceExpanded=true;renderEvidence();$('#evidenceList [data-spot]')?.focus({preventScroll:true});};$('#evidenceList').append(more);
    }
    if(evidenceExpanded && features.length>50)$('#evidenceList').insertAdjacentHTML('beforeend','<p class="hint">목록은 처음 50곳이에요. 지도에는 전체 위치를 표시했어요.</p>');
    $$('[data-spot]').forEach(b=>b.onclick=()=>{
      const feature=features.find(f=>f.id===b.dataset.spot);
      if(matchMedia('(max-width:700px)').matches){$('.route-workspace').classList.add('map-focused');$('#mapViewToggle').setAttribute('aria-pressed','true');$('#mapViewToggle').textContent='비교 함께 보기';}
      candMap.invalidateSize();candMap.setView([feature.lat,feature.lng],18);markers.get(feature.id)?.openPopup();$('#evidenceMapLabel').textContent=`${selected+1}번 ${c.label} · ${feature.title}`;
    });
  }
  function renderSuggestion(){
    const suggestion=lastResult.suggestion,c=candidates[selected];
    const search=`<h3>${selected+1}번 ${App.esc(c.label)} 길의 탐색 방식</h3><p>${(c.searchDescription||['TMAP 탐색 옵션으로 찾은 길이에요.']).map(App.esc).join('<br>')}</p>`;
    let text=`<details><summary>이 길의 탐색 방식 보기</summary>${search}</details>`;
    if(suggestion){const proposed=candidates[suggestion.index];
      text=`<div class="suggestion-lead"><b>${suggestion.basis==='TIME_DISTANCE'?'시간·거리 기준 제안':suggestion.basis==='SINGLE'?'비교 후보 없음':'자료 차이로 먼저 살펴볼 길'}: ${suggestion.index+1}번 ${App.esc(proposed.label)}</b><p>${App.esc(suggestion.summary)}</p><small>보안등·CCTV·보호구역 수는 제안 순위에 더하지 않아요. <a href="/data-info" target="_blank" rel="noopener">자료 확보 범위 보기 ↗</a></small></div><details><summary>계산 기준과 후보별 차이 보기</summary><ul>${suggestion.reasons.map(reason=>`<li>${App.esc(reason)}</li>`).join('')}</ul>${search}<p class="hint">${App.esc(suggestion.rule)}</p><p class="hint">${App.esc(suggestion.limitation)}</p></details>`;
    }
    $('#routeSuggestion').innerHTML=text;
    $('#proposalShortcut').hidden=!suggestion;
    if(suggestion)$('#proposalShortcut').textContent=suggestion.basis==='SINGLE'?'후보가 하나인 이유 보기':`${suggestion.index+1}번 비교 제안 · 이유 보기`;
    $('#proposalShortcut').onclick=()=>{const detail=$('#routeSuggestion details');if(detail)detail.open=true;$('#routeSuggestion').scrollIntoView({behavior:'smooth',block:'start'});};
  }

  $('#backToPlaces')?.addEventListener('click', () => history.back());
  $('#backToPlaces2')?.addEventListener('click', () => history.back());

  // 비로그인: 가입하고 이 경로 저장 (입력값을 잠깐 들고 있다가 가입 뒤 이어서, HANDOFF 5-7)
  $('#guestSaveBtn')?.addEventListener('click', async () => {
    try {
      await App.api('/api/explore/pending', { method: 'POST', redirectOnAuth:false, body: { start: places.start, end: places.end, option: candidates[selected].option } });
      location.href = '/signup';
    } catch (e) { App.toast('선택한 길을 이어 저장하지 못했어요. '+e.message+' 다시 눌러 주세요.'); }
  });

  // ── R-03 → R-04 확정 ──
  $('#pickBtn')?.addEventListener('click', () => {
    if (!$('#routeName').value) $('#routeName').value = `${places.end.name} 가기`.slice(0, 30);
    show('confirm');
    makeDraft();
  });
  $$('#nameChips .chip').forEach(c => c.addEventListener('click', () => { $('#routeName').value = c.textContent; }));
  $$('#toneSeg [data-tone]').forEach(b => b.addEventListener('click', () => {
    if (saving || tone === b.dataset.tone) return;
    tone = b.dataset.tone;
    $$('#toneSeg [data-tone]').forEach(x => x.classList.toggle('on', x === b));
    makeDraft();
  }));

  async function makeDraft() {
    if(saving)return;
    const version=++draftVersion,requestId=newId();draftId=null;saveRequestId=null;
    $('#draftRetry').hidden=true;
    $('#draftLoading').hidden = false; $('#draftResult').hidden = true; $('#saveBtn').disabled = true;
    $('#confirmError').textContent = '';
    try {
      const d = await App.api('/api/routes/draft', { method: 'POST', redirectOnAuth:false, body: {
        start: places.start, end: places.end, option: candidates[selected].option,
        ownerId: CFG.ownerId, tone, replaceRouteId: CFG.replaceId, requestId } });
      if(version!==draftVersion || step!=='confirm')return;
      if(!Array.isArray(d.points))throw new Error('안내문 응답을 확인하지 못했어요. 다시 만들어 주세요.');
      draftId=d.draftId;
      $('#draftCount').textContent = d.points.length;
      $('#draftSteps').innerHTML = App.stepsHTML(d.points);
      $('#draftEmpty').hidden = d.points.length > 0;
      const check = $('#draftCheck');
      if (d.guideSource === 'AI') {
        check.className = 'box ok';
        check.innerHTML = '<b>✓ 지점 목록과 대조했어요</b><br>' + (d.retried
          ? `목록에 없는 내용이 나와서 안내문을 ${d.retried}번 다시 만들었어요.`
          : '장소 이름·방향·횡단보도 주의 문장 등의 자동 검사 규칙을 통과했어요.') + '<br><small>자동 검사가 모든 오류를 찾는 것은 아니에요. 출입구와 실제 길을 함께 확인해 주세요.</small>';
      } else if (d.attempts > 0) {
        check.className = 'box warn';
        check.innerHTML = '<b>정해진 문장으로 만들었어요</b><br>AI 안내문이 지점 목록과 맞지 않거나 응답이 없었어요. 저장한 뒤 경로 상세에서 고칠 수 있어요.';
      } else {
        check.className = 'box soft';
        check.innerHTML = '<b>정해진 문장으로 만들었어요</b><br>AI가 연결되지 않았어요. 저장한 뒤 경로 상세에서 고칠 수 있어요.';
      }
      $('#draftLoading').hidden = true; $('#draftResult').hidden = false; $('#saveBtn').disabled = false;
    } catch (e) {
      if(version!==draftVersion || step!=='confirm')return;
      $('#draftLoading').hidden = true;
      $('#draftRetry').hidden=false;
      authRecovery(e);
      $('#confirmError').textContent = e.message;
    }
  }

  const retry=document.createElement('button');retry.id='draftRetry';retry.type='button';retry.className='btn ghost';retry.textContent='안내문 다시 만들기';retry.hidden=true;
  $('#confirmError')?.after(retry);retry.onclick=makeDraft;
  $('#saveBtn')?.addEventListener('click', async () => {
    if(saving)return;
    if(!draftId){$('#confirmError').textContent='안내문을 다시 만든 뒤 확인하고 저장해 주세요.';$('#draftRetry').hidden=false;return;}
    const name = $('#routeName').value.trim();
    if (!name) { $('#confirmError').textContent = '경로 이름을 써 주세요.'; $('#routeName').focus(); return; }
    saving=true;lockSaveControls(true);
    const btn = $('#saveBtn'); btn.disabled = true; btn.innerHTML = '<span class="spinner"></span> 저장하는 중…';
    try {
      const saveOf = k => !places[k].fromMine && !!$(`.placebox[data-which="${k}"] [data-save]`)?.checked;
      const r = await App.api('/api/routes', { method: 'POST', redirectOnAuth:false, body: { name, saveStart: saveOf('start'), saveEnd: saveOf('end'), draftId, requestId:saveRequestId||(saveRequestId=newId()) } });
      if(!Number.isSafeInteger(r.id)||r.id<=0)throw new Error('저장 결과를 확인하지 못했어요. 같은 요청으로 다시 저장해 주세요.');
      location.href = `/routes/${r.id}?saved=1`;
    } catch (e) {
      saving=false;lockSaveControls(false);
      authRecovery(e);
      $('#confirmError').textContent = e.message; btn.disabled = false; btn.textContent = '저장하기';
      if(e.code==='DRAFT_UNAVAILABLE' || e.code==='AUTH'){
        draftId=null;saveRequestId=null;btn.disabled=true;
        $('#draftRetry').hidden=false;
        $('#confirmError').textContent=e.code==='AUTH'?'로그인한 뒤 아래에서 안내문을 다시 만들어 주세요. 입력한 장소와 경로 이름은 유지돼요.':e.message;
        if(e.code!=='AUTH')$('#draftRetry').focus({preventScroll:true});
      }
    }
  });
  $('#backToCompare')?.addEventListener('click', () => history.back());

  $('#mapViewToggle').onclick=()=>{const full=$('.route-workspace').classList.toggle('map-focused');$('#mapViewToggle').setAttribute('aria-pressed',full);$('#mapViewToggle').textContent=full?'비교 함께 보기':'지도 크게 보기';setTimeout(()=>{candMap?.invalidateSize();if(candMap)drawCandidates();},0);};

  // ── 단계 전환 ──
  function show(next, recordHistory=true) {
    if(saving){App.toast('저장 결과를 확인하고 있어요. 잠시 기다려 주세요.');return;}
    if(recordHistory && next!==step)history.pushState({...history.state,ssrStep:next},'');
    if (next !== 'compare') clearCrimeMap();
    if(next!=='confirm'){draftVersion++;draftId=null;}
    step = next;
    $('.route-workspace').dataset.stage=next;
    $('#stepPlaces').hidden = step !== 'places';
    $('#stepCompare').hidden = step !== 'compare';
    if ($('#stepConfirm')) $('#stepConfirm').hidden = step !== 'confirm';
    const idx = { places: 1, compare: 2, confirm: 3 }[step];
    [1, 2, 3].forEach(i => { const el = $('#sb' + i); if (el) el.className = i === idx ? 'on' : i < idx ? 'done' : ''; });
    $('#title').textContent = step === 'places' ? (CFG.guest ? '경로 살펴보기' : CFG.replaceId ? '다시 등록하기' : '경로 등록')
      : step === 'confirm' ? '안내문 확인·저장' : (candidates.length === 1 ? '이 길의 안전 정보' : '후보 길 비교');
    window.scrollTo(0, 0);
    $('#title').setAttribute('tabindex','-1');$('#title').focus({preventScroll:true});
    if (step === 'places') setTimeout(() => {pinMap.invalidateSize();fitPins();}, 0);
    if (step === 'compare' && candMap) setTimeout(() => { candMap.invalidateSize(); drawCandidates(); }, 0);
  }
  // 뒤로(‹): 단계 안에서는 앞 단계로
  window.addEventListener('popstate',e=>{if(saving){history.pushState({...history.state,ssrStep:step},'');App.toast('저장 결과를 확인하고 있어요. 잠시 기다려 주세요.');return;}if(e.state?.ssrStep)show(e.state.ssrStep,false);});
  $('#backBtn').addEventListener('click', e => {
    if(saving){e.preventDefault();App.toast('저장 결과를 확인하고 있어요. 잠시 기다려 주세요.');return;}
    if (step === 'places') return;
    e.preventDefault();
    history.back();
  });

  // 지도에서 직접 고른 목적지만 같은 브라우저에서 한 번 이어 받는다. GPS·최근 검색은 저장하지 않는다.
  if(!CFG.pending && !CFG.replaceId){
    try{
      const raw=sessionStorage.getItem('ssr-map-destination');sessionStorage.removeItem('ssr-map-destination');
      const draft=raw?JSON.parse(raw):null, p=draft?.place;
      if(draft && Date.now()-draft.created>=0 && Date.now()-draft.created<120000 &&
         Number.isFinite(p?.lat) && Number.isFinite(p?.lng) && p.lat>=37.4 && p.lat<=37.73 && p.lng>=126.75 && p.lng<=127.2){
        setPlace('end',{name:typeof p.name==='string'?p.name.slice(0,30):'',lat:p.lat,lng:p.lng});
        $('.placebox[data-which="start"] [data-q]').focus({preventScroll:true});
      }
    }catch(e){}
  }
  // 비로그인으로 비교하던 경로를 가입 뒤 이어서: 바로 경로 찾기
  if (CFG.pending) $('#findBtn').click();
})();
