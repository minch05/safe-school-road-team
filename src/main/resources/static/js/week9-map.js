const byId = id => document.getElementById(id);
const points = {start:null,end:null};
let map, provider, markers=[], lines=[], facilityMarkers=[], selected='start', currentSearch=0;
let facilitySample=[];
const SAMPLE_NAMES=['0','1','2','3','partial'];
const sampleParam=new URLSearchParams(location.search).get('sample');
const sampleMode=SAMPLE_NAMES.includes(sampleParam)?sampleParam:null;
const COLORS=['#096c46','#c47a00','#a34081'];
let candidateList=[], cardEls=[], selectedCandidateId=null, lastLineClick=0;
if(sampleMode)byId('sample-banner').hidden=false;
function mapClicked(p){if(Date.now()-lastLineClick<300)return;setPoint(selected,p);}
const status = message => byId('message').textContent = message;
function point(which){ return {lat:Number(byId(`${which}-lat`).value),lng:Number(byId(`${which}-lng`).value)}; }
function setPoint(which,p){byId(`${which}-lat`).value=Number(p.lat).toFixed(6);byId(`${which}-lng`).value=Number(p.lng).toFixed(6);points[which]=p;selected=which==='start'?'end':'start';drawMarkers();}
function clearLayers(){for(const layer of [...markers,...lines]){if(provider==='naver')layer.setMap(null);else map.removeLayer(layer);}markers=[];lines=[];}
function drawMarkers(){if(!map)return;for(const m of markers){if(provider==='naver')m.setMap(null);else map.removeLayer(m);}markers=[];for(const [name,p] of Object.entries({start:point('start'),end:point('end')})){if(!valid(p))continue;markers.push(provider==='naver'?new naver.maps.Marker({position:new naver.maps.LatLng(p.lat,p.lng),map,title:name==='start'?'출발':'도착'}):L.marker([p.lat,p.lng]).addTo(map).bindPopup(name==='start'?'출발':'도착'));}}
function drawFacilities(){
  if(!map)return;
  for(const marker of facilityMarkers){if(provider==='naver')marker.setMap(null);else map.removeLayer(marker);}
  facilityMarkers=[];
  for(const site of facilitySample){
    const title=`횡단보도 개발용 표본 ${site.originalId} · 원본 내려받은 날짜 ${site.sourceDownloaded}`;
    if(provider==='naver'){
      facilityMarkers.push(new naver.maps.Marker({map,position:new naver.maps.LatLng(site.lat,site.lng),title}));
    }else facilityMarkers.push(L.circleMarker([site.lat,site.lng],{radius:5,color:'#a35219',fillOpacity:.8})
      .addTo(map).bindPopup(title));
  }
}
function lineStyle(on,i){return {color:COLORS[i%COLORS.length],weight:on?10:4,opacity:on?1:.5};}
function applyLineStyle(layer,on,i){
  const s=lineStyle(on,i);
  if(provider==='naver')layer.setOptions({strokeColor:s.color,strokeWeight:s.weight,strokeOpacity:s.opacity,zIndex:on?10:1});
  else{layer.setStyle(s);if(on)layer.bringToFront();}
}
function clearRoutes(){
  for(const l of lines){if(provider==='naver')l.setMap(null);else map.removeLayer(l);}
  lines=[];candidateList=[];cardEls=[];selectedCandidateId=null;
}
function resetCandidates(){
  clearRoutes();
  byId('candidates').replaceChildren();
  byId('warnings').replaceChildren();
  byId('selected-id').textContent='';
  byId('confirm-route').hidden=true;
  byId('confirm-message').textContent='';
}
function showWarnings(items){
  const box=byId('warnings');box.replaceChildren();
  for(const item of items||[]){const li=document.createElement('li');li.textContent=String(item);box.append(li);}
}
function selectCandidate(id){
  const index=candidateList.findIndex(c=>c.candidateId===id);
  if(index<0)return;
  selectedCandidateId=id;
  candidateList.forEach((c,i)=>{
    const on=i===index;
    if(lines[i])applyLineStyle(lines[i],on,i);
    if(cardEls[i]){
      cardEls[i].classList.toggle('selected',on);
      cardEls[i].querySelector('.candidate-pick').setAttribute('aria-pressed',String(on));
    }
  });
  byId('selected-id').textContent=`선택한 후보: ${id}`;
}
function drawRoutes(candidates){
  if(!map)return;
  candidates.forEach((c,i)=>{
    const path=c.line.map(p=>[p.lat,p.lng]);
    const pick=()=>{lastLineClick=Date.now();selectCandidate(c.candidateId);};
    let layer;
    if(provider==='naver'){
      layer=new naver.maps.Polyline({map,path:path.map(p=>new naver.maps.LatLng(p[0],p[1])),strokeColor:COLORS[i%COLORS.length],strokeWeight:4,clickable:true});
      naver.maps.Event.addListener(layer,'click',pick);
    }else{
      layer=L.polyline(path,{color:COLORS[i%COLORS.length],weight:4,bubblingMouseEvents:false}).addTo(map);
      layer.on('click',pick);
    }
    lines.push(layer);
  });
  const all=candidates.flatMap(c=>c.line);
  if(all.length){
    if(provider==='naver'){const bounds=new naver.maps.LatLngBounds();all.forEach(p=>bounds.extend(new naver.maps.LatLng(p.lat,p.lng)));map.fitBounds(bounds);}
    else map.fitBounds(all.map(p=>[p.lat,p.lng]));
  }
}
function renderCandidates(list){
  const box=byId('candidates');box.replaceChildren();cardEls=[];
  list.forEach((c,i)=>{
    const card=document.createElement('article');
    card.className='candidate';card.dataset.candidateId=c.candidateId;
    card.style.setProperty('--line-color',COLORS[i%COLORS.length]);
    const pick=document.createElement('button');
    pick.type='button';pick.className='candidate-pick';pick.setAttribute('aria-pressed','false');
    const dot=document.createElement('span');dot.className='dot';dot.setAttribute('aria-hidden','true');
    const title=document.createElement('strong');
    title.textContent=`${c.label} · ${(c.distanceM/1000).toFixed(1)}km · ${Math.ceil(c.durationSec/60)}분`;
    pick.append(dot,title);
    const note=document.createElement('p');note.textContent=c.dataCoverage;
    const evidence=document.createElement('div');evidence.className='evidence';
    const evidenceTitle=document.createElement('h3');evidenceTitle.textContent='경로 근거';
    const evidenceBody=document.createElement('p');evidenceBody.textContent='근거 분석 연결 전 · 확인 안 됨';
    evidence.append(evidenceTitle,evidenceBody);
    card.append(pick,note,evidence);
    card.addEventListener('click',()=>selectCandidate(c.candidateId));
    box.append(card);cardEls.push(card);
  });
  byId('confirm-route').hidden=false;
}
function valid(p){return Number.isFinite(p.lat)&&Number.isFinite(p.lng)&&p.lat>=37.41&&p.lat<=37.72&&p.lng>=126.76&&p.lng<=127.19;}
function loadScript(url){return new Promise((resolve,reject)=>{const script=document.createElement('script');script.src=url;script.onload=resolve;script.onerror=reject;document.head.append(script);});}
async function initMap(){
  const config=await (await fetch('/api/map-config')).json();
  const mapEl=byId('map');
  let switched=false;
  function vworldFallback(){
    if(switched)return;
    switched=true;
    try{map?.destroy?.();}catch(e){/* SDK failure must not block the secondary map. */}
    map=null;provider=null;markers=[];lines=[];facilityMarkers=[];mapEl.replaceChildren();
    if(window.L&&config.vworldKey){
      try{
      provider='vworld';map=L.map('map').setView([37.592,127.089],14);
      L.tileLayer(`https://api.vworld.kr/req/wmts/1.0.0/${encodeURIComponent(config.vworldKey)}/Base/{z}/{y}/{x}.png`,
        {attribution:'© VWorld',maxZoom:19}).addTo(map);
      map.on('click',e=>mapClicked(e.latlng));
      byId('map-status').textContent='네이버 지도 인증을 확인하지 못해 VWorld 보조 지도로 미리보기 중입니다.';
      drawMarkers();drawFacilities();return;
      }catch(e){map=null;provider=null;}
    }
    if(sampleMode&&window.L){
      try{
        provider='plain';map=L.map('map',{minZoom:10,maxZoom:19}).setView([37.592,127.089],15);
        map.on('click',e=>mapClicked(e.latlng));
        byId('map-status').textContent='지도 키가 없어 배경 지도 없이 예시 선만 표시합니다. (예시 자료 확인용)';
        drawMarkers();drawFacilities();return;
      }catch(e){map=null;provider=null;}
    }
    byId('map-status').textContent='지도 인증을 확인하지 못했어요. 아래 좌표로 첫 흐름을 시험할 수 있습니다.';
  }
  window.navermap_authFailure=vworldFallback;
  if(config.naverClientId){
    try{
      await loadScript(`https://oapi.map.naver.com/openapi/v3/maps.js?ncpKeyId=${encodeURIComponent(config.naverClientId)}`);
      if(!switched&&window.naver?.maps){
        provider='naver';map=new naver.maps.Map('map',{center:new naver.maps.LatLng(37.592,127.089),zoom:14});
        naver.maps.Event.addListener(map,'click',e=>mapClicked({lat:e.coord.lat(),lng:e.coord.lng()}));
        const observer=new MutationObserver(()=>{
          if(mapEl.textContent.includes('인증에 실패')||mapEl.textContent.includes('API 인증')){
            observer.disconnect();vworldFallback();
          }
        });
        observer.observe(mapEl,{childList:true,subtree:true,characterData:true});
        if(mapEl.textContent.includes('인증에 실패')){observer.disconnect();vworldFallback();}
      }
    }catch(e){vworldFallback();}
  }else vworldFallback();
  if(map&&provider==='naver'){
    byId('map-status').textContent='네이버 지도에서 출발지와 도착지를 차례로 선택하세요.';
    drawMarkers();drawFacilities();
  }
}
async function csrf(){const r=await fetch('/api/auth/csrf',{credentials:'same-origin'});if(!r.ok)throw new Error('보안 토큰을 확인하지 못했어요.');return r.json();}
async function search(which){const query=byId(`${which}-query`).value.trim(),box=byId(`${which}-results`),seq=++currentSearch;box.replaceChildren();if(query.length<2){status('검색어를 두 글자 이상 입력해 주세요.');return;}try{const response=await fetch(`/api/places/search?q=${encodeURIComponent(query)}`);const data=await response.json();if(seq!==currentSearch)return;if(!response.ok)throw new Error(data.error||'검색에 실패했어요.');if(!data.length){status('검색 결과가 없어요. 지도에서 직접 선택해 주세요.');return;}for(const item of data){const b=document.createElement('button');b.type='button';b.textContent=`${item.name||item.address} · ${item.provider}`;b.onclick=()=>{setPoint(which,item);box.replaceChildren();if(map){provider==='naver'?map.panTo(new naver.maps.LatLng(item.lat,item.lng)):map.panTo([item.lat,item.lng]);}};box.append(b);}}catch(e){status(e.message);}}
byId('start-search').onclick=()=>search('start');byId('end-search').onclick=()=>search('end');
byId('route-search').onclick=async()=>{
  const start=point('start'),end=point('end');
  if(!valid(start)||!valid(end)||JSON.stringify(start)===JSON.stringify(end)){status('서울 범위의 서로 다른 두 지점을 입력해 주세요.');return;}
  const button=byId('route-search');
  button.disabled=true;
  resetCandidates();
  status(sampleMode?'예시 후보를 불러오는 중이에요. (실제 경로 아님)':'실제 보행 경로를 요청 중이에요.');
  try{
    let response,data;
    if(sampleMode){
      response=await fetch(`/samples/candidates-${sampleMode}.json`);
      if(!response.ok)throw new Error('예시 자료 파일을 불러오지 못했어요.');
      data=await response.json();
    }else{
      const token=await csrf();
      response=await fetch('/api/routes/candidates',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json',[token.headerName]:token.token},body:JSON.stringify({start,end})});
      if (response.status === 401) {
        status('로그인이 필요합니다.');
        location.assign('/login.html');
        return;
      }
      data=await response.json();
    }
    const failed=sampleMode?(Boolean(data.error)||Number(data.httpStatus)>=400):!response.ok;
    if(failed){showWarnings(data.details);throw new Error(data.error||'경로를 가져오지 못했어요.');}
    const list=Array.isArray(data.candidates)?data.candidates:[];
    const warnings=data.warnings||[];
    if(!list.length){showWarnings(warnings);status('표시할 후보 경로가 없어요.');return;}
    candidateList=list;
    drawRoutes(list);
    renderCandidates(list);
    selectCandidate(list[0].candidateId);
    showWarnings(warnings);
    status(warnings.length?'일부 탐색 옵션은 실패했어요. 확인된 후보만 표시합니다.':sampleMode?`${list.length}개 예시 후보를 표시했어요. 실제 경로가 아니에요.`:`${list.length}개 실제 후보를 받았어요.`);
  }catch(e){
    status(e.message);
    if(!sampleMode&&e.message.includes('로그인'))location.assign('/login.html');
  }finally{button.disabled=false;}
};
byId('confirm-route').onclick=()=>{byId('confirm-message').textContent='저장 기능 연결 전이에요';};
(async()=>{try{const me=await (await fetch('/api/auth/me')).json();byId('user').textContent=me.authenticated?`${me.loginId} 로그인 중`:'로그인이 필요합니다';byId('logout').hidden=!me.authenticated;byId('logout').onclick=async()=>{const t=await csrf();await fetch('/api/auth/logout',{method:'POST',credentials:'same-origin',headers:{[t.headerName]:t.token}});location.reload();};const data=await (await fetch('/api/facilities/status')).json();byId('data-status').textContent=`횡단보도 ${data.crosswalkCount}건 · ${data.coverage}`;facilitySample=await (await fetch('/api/facilities/sample')).json();await initMap();}catch(e){status('초기 화면을 불러오지 못했어요.');}})();
