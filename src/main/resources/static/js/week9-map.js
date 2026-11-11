const byId = id => document.getElementById(id);
const points = {start:null,end:null};
let map, provider, markers=[], lines=[], facilityMarkers=[], selected='start', currentSearch=0;
let facilitySample=[];
const SAMPLE_NAMES=['0','1','2','3','partial'];
const sampleParam=new URLSearchParams(location.search).get('sample');
const sampleMode=SAMPLE_NAMES.includes(sampleParam)?sampleParam:null;
const COLORS=['#096c46','#c47a00','#a34081'];
let candidateList=[], cardEls=[], selectedCandidateId=null, lastLineClick=0;
let searchSeq=0, searchedPoints=null, saving=false;
if(sampleMode){
  byId('sample-banner').hidden=false;
  byId('route-search').textContent='예시 후보 보기';
}
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
    const title=`중랑구 횡단보도 미리보기 ${site.originalId} · 원본 내려받은 날짜 ${site.sourceDownloaded}`;
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
  byId('route-name-label').hidden=true;
  confirmMessage('');
  searchedPoints=null;
}
function confirmMessage(text,isError){
  const box=byId('confirm-message');
  box.textContent=text;
  box.classList.toggle('error',Boolean(isError));
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
    const evidenceBody=document.createElement('div');evidenceBody.className='evidence-body';
    evidenceBody.textContent=sampleMode?'예시 자료에는 근거가 없어요 · 확인 안 됨':'근거를 불러오는 중이에요.';
    evidence.append(evidenceTitle,evidenceBody);
    card.append(pick,note,evidence);
    card.addEventListener('click',()=>selectCandidate(c.candidateId));
    box.append(card);cardEls.push(card);
  });
  byId('confirm-route').hidden=false;
  byId('route-name-label').hidden=false;
}
function evidenceText(card,text){
  const body=card.querySelector('.evidence-body');
  if(body)body.textContent=text;
}
function evidenceItem(list,text){
  const li=document.createElement('li');li.textContent=text;list.append(li);
}
function renderEvidence(card,e){
  const body=card.querySelector('.evidence-body');
  if(!body)return;
  if(!e){evidenceText(card,'이 후보의 근거를 찾지 못했어요 · 확인 안 됨');return;}
  const list=document.createElement('ul');list.className='evidence-list';
  const c=e.crossings||{};
  let crossing=`횡단보도 ${c.count??0}곳 · 신호 추정 ${c.signalEstimated??0}곳 · 신호 미확인 ${c.signalUnknown??0}곳`;
  if(c.unmatched)crossing+=` · 등록 자료 없음 ${c.unmatched}곳`;
  evidenceItem(list,crossing);
  const s=e.safety||{};
  evidenceItem(list,`경로 ${s.radiusM??50}m 안 CCTV ${s.cctv??0}곳 · 비상벨 ${s.emergencyBell??0}곳 (작동 여부는 확인하지 않았어요)`);
  const accidents=Array.isArray(e.accidentAreas)?e.accidentAreas:[];
  if(accidents.length){
    for(const a of accidents)evidenceItem(list,`${a.label} · ${a.name}${a.baseYear?` (${a.baseYear}년 기준)`:''}`);
  }else evidenceItem(list,'공공자료에서 확인된 사고 다발 지역이 없어요.');
  const missing=e.coverage&&Array.isArray(e.coverage.missingKinds)?e.coverage.missingKinds:[];
  if(missing.length)evidenceItem(list,`자료 부족: ${missing.join(', ')} 자료가 적재되지 않았어요.`);
  for(const text of Array.isArray(e.limitations)?e.limitations:[])evidenceItem(list,String(text));
  body.replaceChildren(list);
}
async function loadEvidence(seq,start,end){
  try{
    const token=await csrf();
    const response=await fetch('/api/routes/evidence',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json',[token.headerName]:token.token},body:JSON.stringify({start,end})});
    const data=await response.json().catch(()=>({}));
    if(seq!==searchSeq)return;
    if(!response.ok)throw new Error(data.error||'근거를 확인하지 못했어요.');
    const byCandidate=new Map((Array.isArray(data.evidence)?data.evidence:[]).map(item=>[item.candidateId,item]));
    for(const card of cardEls)renderEvidence(card,byCandidate.get(card.dataset.candidateId));
  }catch(e){
    if(seq!==searchSeq)return;
    for(const card of cardEls)evidenceText(card,'근거를 확인하지 못했어요 · 확인 안 됨');
  }
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
  const seq=++searchSeq;
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
    if(seq!==searchSeq)return;
    candidateList=list;
    searchedPoints={start,end};
    drawRoutes(list);
    renderCandidates(list);
    selectCandidate(list[0].candidateId);
    if(!sampleMode)loadEvidence(seq,start,end);
    showWarnings(warnings);
    status(warnings.length?'일부 탐색 옵션은 실패했어요. 확인된 후보만 표시합니다.':sampleMode?`${list.length}개 예시 후보를 표시했어요. 실제 경로가 아니에요.`:`${list.length}개 실제 후보를 받았어요.`);
  }catch(e){
    status(e.message);
    if(!sampleMode&&e.message.includes('로그인'))location.assign('/login.html');
  }finally{button.disabled=false;}
};
byId('confirm-route').onclick=async()=>{
  const button=byId('confirm-route'),nameInput=byId('route-name');
  if(saving)return;
  if(sampleMode){confirmMessage('예시 자료는 저장할 수 없어요. 실제 후보에서 저장해 주세요.',true);return;}
  if(!selectedCandidateId||!searchedPoints){confirmMessage('저장할 후보를 먼저 선택해 주세요.',true);return;}
  const name=nameInput.value.trim();
  if(!name||name.length>30){confirmMessage('경로 이름을 1~30자로 입력해 주세요.',true);nameInput.focus();return;}
  saving=true;button.disabled=true;confirmMessage('저장 중이에요.');
  try{
    const token=await csrf();
    const response=await fetch('/api/routes',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json',[token.headerName]:token.token},body:JSON.stringify({start:searchedPoints.start,end:searchedPoints.end,candidateId:selectedCandidateId,name})});
    const data=await response.json().catch(()=>({}));
    if(response.status===401){confirmMessage('로그인이 필요합니다. 로그인 화면으로 이동해요.',true);location.assign('/login.html');return;}
    if(response.status===201&&Number.isInteger(data.id)){location.assign(`/guide.html?id=${encodeURIComponent(data.id)}`);return;}
    const reason=data.error||'저장하지 못했어요.';
    if(response.status===400)confirmMessage(`${reason} 입력한 이름과 선택은 그대로 두었어요.`,true);
    else if(response.status===422)confirmMessage(`${reason} 입력한 이름은 그대로 두었어요.`,true);
    else if(response.status===503)confirmMessage(`${reason} 잠시 뒤 같은 버튼으로 다시 저장해 주세요.`,true);
    else confirmMessage(response.status===201?'저장 응답에 경로 번호가 없어요.':reason,true);
  }catch(e){
    confirmMessage(e.message||'저장하지 못했어요. 네트워크를 확인해 주세요.',true);
  }finally{saving=false;button.disabled=false;}
};
(async()=>{try{const me=await (await fetch('/api/auth/me')).json();byId('user').textContent=me.authenticated?`${me.loginId} 로그인 중`:'로그인이 필요합니다';byId('logout').hidden=!me.authenticated;byId('logout').onclick=async()=>{const t=await csrf();await fetch('/api/auth/logout',{method:'POST',credentials:'same-origin',headers:{[t.headerName]:t.token}});location.reload();};const data=await (await fetch('/api/facilities/status')).json();byId('data-status').textContent=`횡단보도 ${data.crosswalkCount}건 · ${data.coverage}`;facilitySample=await (await fetch('/api/facilities/sample')).json();await initMap();}catch(e){status('초기 화면을 불러오지 못했어요.');}})();
