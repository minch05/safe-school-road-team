// N-01 길 안내 (FR-19). 경로선 + 내 위치 + 번호 핀. 지점에 가까워지면 그 지점의 안내문을 띄운다.
// 내 위치는 이 폰에서만 쓰고 서버로 보내지 않는다. 서버로 가는 것은 "출발함/도착함" 이벤트뿐이다(HANDOFF 1장).
(() => {
  const $ = s => document.querySelector(s);
  // 안내 배너가 여러 줄이 되어도 위치 상태와 겹치지 않도록 실제 높이를 사용한다.
  const layoutStatus=()=>{
    const nav=$('.nav'),note=$('.topnote'),sheet=$('.sheet');
    if(!nav||!note||!sheet)return;
    const top=nav.getBoundingClientRect().top;
    nav.style.setProperty('--nav-status-top',(note.getBoundingClientRect().bottom-top+8)+'px');
    nav.style.setProperty('--nav-sheet-top',(sheet.getBoundingClientRect().top-top)+'px');
  };
  if(typeof ResizeObserver!=='undefined'){
    const resize=new ResizeObserver(layoutStatus);resize.observe($('.topnote'));resize.observe($('.sheet'));
    window.addEventListener('pagehide',()=>resize.disconnect(),{once:true});
    window.addEventListener('pageshow',()=>{resize.observe($('.topnote'));resize.observe($('.sheet'));layoutStatus();});
  }
  window.addEventListener('resize',layoutStatus);
  const NEAR_POINT_M = 25;   // 이 거리 안이면 그 지점 안내문을 띄운다

  const map = App.makeMap('navmap', { zoomControl: false });
  const layer = MapKit.layerGroup().addTo(map);
  let points = [], expectedSeconds = 0, routeLine = null;
  let walking = false, cur = 1, shown = new Set(), tripId = null, arriving = false;
  let me = null, meAcc = null, myLatLng = null, follow = false;
  let sheetOpen = true, loaded = false, watchId = null, locationError = '', lastPosition = null;
  let starting = false, autoArrivalPaused = false;
  const positionGuard = NavigationGuard.create();
  const startRequestId = (() => {
    if(window.crypto?.randomUUID)return window.crypto.randomUUID();
    if(!window.crypto?.getRandomValues)return null;
    const bytes=window.crypto.getRandomValues(new Uint8Array(16));
    bytes[6]=(bytes[6]&15)|64;bytes[8]=(bytes[8]&63)|128;
    const hex=Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');
    return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
  })();

  // 아래 안내 목록에 가리지 않게, 보이는 부분에 경로를 맞춘다
  function fit() {
    const pts = routeLine || points.map(x => [x.lat, x.lng]).concat([NAV.start, NAV.end]);
    if (!pts.length) return;
    const sheetH = $('.sheet').offsetHeight;
    // 위쪽 안내(출발 문구·위치 상태)가 경로 끝을 가리지 않게 그 아래부터 맞춘다
    const topCover = Math.max(70, ...['.topnote', '.nav-status'].map(sel => {
      const el = $(sel); return el && !el.hidden ? el.getBoundingClientRect().bottom + 16 : 0;
    }));
    map.fitBounds(pts, { paddingTopLeft: [24, topCover], paddingBottomRight: [24, sheetH + 24] });
  }
  function placeRecenter() { $('#recenter').style.bottom = ($('.sheet').offsetHeight + 12) + 'px'; }

  async function loadGuide() {
    $('#retryGuide').hidden = true; $('#navError').textContent = '';
    $('#startBtn').disabled = true;
    try {
      const d = await App.api(`/api/routes/${NAV.routeId}/line`);
      points = d.points; expectedSeconds = d.time; routeLine = d.line;
      MapKit.polyline(d.line, { color: '#fff', weight: 11, opacity: .95 }).addTo(map);
      MapKit.polyline(d.line, { color: '#2459d6', weight: 7, opacity: .95 }).addTo(map);
      loaded = true;
    } catch (e) {
      $('#navError').textContent = e.message + ' 경로선은 확인하지 못했어요.';
      $('#retryGuide').hidden = false;
      try {
        points = await App.api(`/api/routes/${NAV.routeId}/points`); loaded = true;
        $('#navError').textContent += ' 저장한 지점만 참고할 수 있어요.';
      } catch (fallback) { $('#navError').textContent += ' 안내 지점도 불러오지 못했어요. 연결을 확인하고 다시 시도해 주세요.'; }
    }
    $('#startBtn').disabled = !loaded;
    render(); fit();
  }
  $('#retryGuide').addEventListener('click', loadGuide);
  loadGuide();

  function render() {
    layer.clearLayers();
    points.forEach(p => App.numberMarker(p, { done: walking && p.seq < cur, current: walking && p.seq === cur })
      .bindPopup(`<b>${p.seq}. ${App.esc(p.kindLabel)}</b><br>${App.esc(p.text)}`).addTo(layer));
    App.flagMarker(NAV.start, '🏠').addTo(layer);
    App.flagMarker(NAV.end, '🚩').addTo(layer);
    $('#navSteps').innerHTML = App.stepsHTML(points, { showType: false, cur: walking ? cur : 0, doneUntil: walking ? cur : 0 });
    $('#sheetList').hidden = !sheetOpen;
    $('#sheetToggle').textContent = sheetOpen ? '목록 접기' : '목록 펼치기';
    $('#sheetToggle').setAttribute('aria-expanded',String(sheetOpen));
    $('#startBtn').hidden = walking;
    $('#arriveWrap').hidden = !walking;
    $('#walkInfo').hidden = !walking;
    if (walking) {
      const next = points.find(p => p.seq === cur);
      $('#nextNum').textContent = next ? next.seq : '✓';
      $('#passedPoint').hidden=!next;
      $('#nextLabel').textContent = next ? `다음 지점 · ${next.kindLabel}` : '이제 목적지예요';
      $('#nextText').textContent = next ? next.text : '도착하면 아래 버튼을 눌러 주세요.';
      const source = $('#nextSource');
      if (source) { source.hidden = next?.kind !== 'WALK_JUNCTION'; source.textContent = '서울특별시 도보망 · 공공누리 1유형 · 2020년 기준'; }
      $('#progressBar').style.width = points.length ? Math.round((cur - 1) / points.length * 100) + '%' : '100%';
    }
    placeRecenter();
  }

  $('#passedPoint').addEventListener('click',()=>{const next=points.find(p=>p.seq===cur);if(!walking||!next)return;shown.add(next.seq);cur=next.seq+1;render();});

  // ── 내 위치: 동의 후 기기 안에서만 사용. 정확하지 않은 GPS로 진행/도착을 판정하지 않는다 ──
  function note() {
    $('#navNote').textContent = document.hidden ? '화면이 꺼지면 위치 안내가 멈출 수 있어요'
      : walking ? '앞을 보고 걸어요. 헷갈릴 때만 확인해요' : '출발하면 폰은 넣고 앞을 보며 걸어요';
    $('#locationStatus').textContent = locationError || (!lastPosition ? '현재 위치 꺼짐'
      : !NavigationGuard.fresh(lastPosition) ? '위치 정보가 오래됐어요 · 자동 확인 잠시 멈춤'
      : lastPosition.coords.accuracy>35 ? '위치가 정확하지 않아요 · 자동 확인 잠시 멈춤'
      : `현재 위치 확인 중 · 오차 약 ${Math.round(lastPosition.coords.accuracy)}m`);
  }
  function clearLocation(){
    if(watchId!==null)navigator.geolocation.clearWatch(watchId);
    watchId=null;lastPosition=null;myLatLng=null;follow=false;positionGuard.reset();
    me?.remove();meAcc?.remove();me=meAcc=null;$('#recenter').hidden=true;
  }
  function locationHelp(){
    App.modal('<h2>위치 권한을 확인해 주세요</h2><p>주소창 옆 사이트 설정에서 위치를 허용해 주세요. 휴대폰 위치 서비스도 켜져 있어야 해요.</p><p>앱 안 브라우저라면 사파리나 크롬에서 열어 주세요. 현재 위치는 서버로 보내지 않아요.</p><button class="btn primary" type="button" onclick="App.closeModal()">확인</button>');
  }
  async function enableLocation(userAction=false){
    if($('#locationEnable').disabled)return;
    $('#locationEnable').disabled=true;
    clearLocation();
    if(!window.isSecureContext||!navigator.geolocation){$('#locationEnable').disabled=false;locationError='현재 위치는 지원하는 브라우저의 HTTPS 연결에서 사용할 수 있어요';note();return;}
    let permission;try{permission=await navigator.permissions?.query({name:'geolocation'});}catch(e){}
    if(permission?.state==='denied'){$('#locationEnable').disabled=false;locationError='위치 권한이 차단됐어요 · 버튼에서 허용 방법 확인';note();if(userAction)locationHelp();return;}
    locationError='현재 위치를 찾는 중…';note();
    $('#locationEnable').disabled=true;
    watchId=navigator.geolocation.watchPosition(pos=>{
      $('#locationEnable').disabled=false;$('#locationEnable').textContent='위치 다시 확인';
      if(!NavigationGuard.fresh(pos)){positionGuard.reset();locationError='위치 정보가 오래됐어요 · 다시 확인해 주세요';note();return;}
      try{localStorage.setItem('ssr-geo','granted');}catch(e){}
      lastPosition=pos;locationError='';myLatLng=[pos.coords.latitude,pos.coords.longitude];
      if(!me){meAcc=MapKit.circle(myLatLng,{radius:pos.coords.accuracy,color:'#2459d6',weight:1,fillOpacity:.1}).addTo(map);me=App.meDot(myLatLng).addTo(map);}
      else{me.setLatLng(myLatLng);meAcc.setLatLng(myLatLng).setRadius(pos.coords.accuracy);}
      $('#recenter').hidden=false;
      if(follow&&pos.coords.accuracy<=35)map.panTo(myLatLng);
      note();if(walking)onMove(pos);
    },error=>{
      clearLocation();$('#locationEnable').disabled=false;$('#locationEnable').textContent='내 위치 보기';
      locationError=error.code===1?'위치 권한이 차단됐어요 · 허용 방법을 확인해 주세요':error.code===3?'위치 확인 시간이 초과됐어요 · 다시 시도해 주세요':'현재 위치를 찾지 못했어요 · 휴대폰 위치 서비스를 확인해 주세요';
      if(error.code===1){try{localStorage.setItem('ssr-geo','denied');}catch(e){}if(userAction)locationHelp();}
      note();
    },{enableHighAccuracy:true,maximumAge:5000,timeout:12000});
  }
  $('#locationEnable').addEventListener('click',()=>enableLocation(true));
  try{if(localStorage.getItem('ssr-geo')==='granted')enableLocation();}catch(e){}
  note();
  $('#recenter').addEventListener('click',()=>{if(myLatLng){follow=true;map.setView(myLatLng,18);}});
  map.on('dragstart',()=>{follow=false;});
  function connection(){
    $('#connectionStatus').hidden=navigator.onLine;
    $('#connectionStatus').textContent='연결이 끊겼어요 · 열린 안내는 볼 수 있지만 출발·도착 기록은 보내지 못할 수 있어요';
  }
  window.addEventListener('online',connection);window.addEventListener('offline',connection);connection();
  document.addEventListener('visibilitychange',()=>{positionGuard.reset();note();});
  const freshnessTimer=setInterval(note,5000);
  window.addEventListener('pagehide',()=>{clearLocation();clearInterval(freshnessTimer);});
  function onMove(position){
    const status=positionGuard.inspect(position,NAV.end,routeLine);
    if(!status.usable)return;
    if(status.offRoute){locationError='표시된 길에서 떨어져 있어요 · 안전한 곳에 멈춰 지도를 확인해 주세요';note();return;}
    const ll=[position.coords.latitude,position.coords.longitude];
    // 현재 차례의 지점만 확인한다. GPS가 뒤쪽 지점으로 튀어도 중간 안내를 건너뛰지 않는다.
    const next=points.find(p=>p.seq===cur);
    if(next&&!shown.has(next.seq)&&map.distance(ll,[next.lat,next.lng])<=NEAR_POINT_M){
      shown.add(next.seq);cur=next.seq+1;showPopup(next);render();
    }
    if(!arriving&&!autoArrivalPaused&&status.arrived){App.toast('목적지 근처 위치를 연속 확인했어요');arrive(true);}
  }

  let popupFocus;
  const popupBackground = ['#navmap','.topnote','.nav-status','#recenter','.sheet'];
  function closePopup() {
    $('#popup').hidden=true; $('#popupBackdrop').hidden=true;
    popupBackground.forEach(selector=>{ $(selector).inert=false; });
    if(popupFocus?.isConnected && !popupFocus.closest('[hidden]')) popupFocus.focus();
  }
  function showPopup(p) {
    if($('#popup').hidden) popupFocus=document.activeElement;
    $('#popEmoji').innerHTML = App.icon(p);
    $('#popWhich').textContent = `${p.seq}번 지점 · ${p.kindLabel}`;
    $('#popText').textContent = p.text;
    $('#popup').hidden = false; $('#popupBackdrop').hidden=false;
    popupBackground.forEach(selector=>{ $(selector).inert=true; });
    $('#closePop').focus();
    if (navigator.vibrate) navigator.vibrate([150, 80, 150]);
  }
  $('#closePop').addEventListener('click', closePopup);
  $('#popup').addEventListener('keydown',e=>{
    if(e.key==='Escape'){e.preventDefault();closePopup();}
    if(e.key==='Tab'){e.preventDefault();$('#closePop').focus();}
  });
  $('#sheetToggle').addEventListener('click', () => { sheetOpen = !sheetOpen; render(); fit(); });

  // ── 출발: 이벤트 기록과 알림 수신을 구분한다 ──
  async function recordStart(){
    if(starting||tripId)return;
    starting=true;$('#retryStart').hidden=true;$('#tripStatus').hidden=false;
    $('#tripStatus').textContent='출발 기록을 보내는 중…';
    try{
      const r=await App.api('/api/trips/start',{method:'POST',body:{routeId:NAV.routeId,expectedSeconds,requestId:startRequestId}});
      tripId=r.tripId;$('#tripStatus').textContent='출발 기록 저장됨 · 보호자 화면에서 확인할 수 있어요';
    }catch(e){$('#tripStatus').textContent='출발 기록을 보내지 못했어요 · 길 안내는 계속돼요';$('#retryStart').hidden=false;}
    finally{starting=false;}
  }
  $('#retryStart').addEventListener('click',recordStart);
  $('#startBtn').addEventListener('click',async()=>{
    if(!loaded||walking)return;
    walking=true;cur=1;shown=new Set();positionGuard.reset();sheetOpen=false;
    render();note();fit();
    if(myLatLng&&lastPosition&&NavigationGuard.fresh(lastPosition)&&lastPosition.coords.accuracy<=35){follow=true;map.setView(myLatLng,18);}
    if(NAV.sendsEvents)await recordStart();else App.toast('이제 앞을 보고 걸어요!');
  });

  $('#arriveBtn').addEventListener('click',()=>arrive(false));
  async function arrive(auto){
    if(arriving||starting)return;
    arriving=true;$('#arriveBtn').disabled=true;
    let recorded=false;
    if(NAV.sendsEvents){
      try{
        if(!tripId){arriving=false;await recordStart();arriving=true;}
        if(!tripId)throw new Error('출발 기록 없음');
        await App.api(`/api/trips/${tripId}/arrive`,{method:'POST',body:{auto}});recorded=true;
      }catch(e){
        arriving=false;$('#arriveBtn').disabled=false;
        positionGuard.reset();
        if(!auto){
          const proceed=await App.confirmBox('도착 기록을 보내지 못했어요','취소하면 안내 화면에서 다시 시도할 수 있어요. 기록 없이 마치면 보호자 화면에는 도착이 표시되지 않을 수 있어요.','기록 없이 마치기');
          if(proceed){arriving=true;location.href=`/routes/${NAV.routeId}/arrived?recorded=false&failed=true`;}
        }else{ autoArrivalPaused=true; $('#tripStatus').hidden=false;$('#tripStatus').textContent='도착 기록을 보내지 못했어요 · 도착 버튼으로 다시 시도해 주세요'; }
        return;
      }
    }
    location.href=`/routes/${NAV.routeId}/arrived?tripId=${recorded?tripId:''}`;
  }

  window.addEventListener('resize', placeRecenter);
  // 걷는 도중 화면을 닫으려 하면 한 번 묻는다
  window.addEventListener('beforeunload', e => { if (walking && !arriving) { e.preventDefault(); e.returnValue = ''; } });
})();
