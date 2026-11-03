const byId = id => document.getElementById(id);
const points = {start:null,end:null};
let map, provider, markers=[], lines=[], facilityMarkers=[], selected='start', currentSearch=0;
let facilitySample=[];
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
function drawRoutes(candidates){for(const l of lines){if(provider==='naver')l.setMap(null);else map.removeLayer(l);}lines=[];if(!map)return;const colors=['#096c46','#c47a00','#a34081'];candidates.forEach((c,i)=>{const path=c.line.map(p=>[p.lat,p.lng]);lines.push(provider==='naver'?new naver.maps.Polyline({map,path:path.map(p=>new naver.maps.LatLng(p[0],p[1])),strokeColor:colors[i],strokeWeight:5}):L.polyline(path,{color:colors[i],weight:5}).addTo(map));});if(candidates.length){const path=candidates[0].line;if(provider==='naver'){const bounds=new naver.maps.LatLngBounds();path.forEach(p=>bounds.extend(new naver.maps.LatLng(p.lat,p.lng)));map.fitBounds(bounds);}else map.fitBounds(path.map(p=>[p.lat,p.lng]));}}
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
      map.on('click',e=>setPoint(selected,e.latlng));
      byId('map-status').textContent='네이버 지도 인증을 확인하지 못해 VWorld 보조 지도로 미리보기 중입니다.';
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
        naver.maps.Event.addListener(map,'click',e=>setPoint(selected,{lat:e.coord.lat(),lng:e.coord.lng()}));
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
byId('route-search').onclick=async()=>{const start=point('start'),end=point('end');if(!valid(start)||!valid(end)||JSON.stringify(start)===JSON.stringify(end)){status('서울 범위의 서로 다른 두 지점을 입력해 주세요.');return;}const button=byId('route-search');button.disabled=true;status('실제 보행 경로를 요청 중이에요.');byId('candidates').replaceChildren();try{const token=await csrf();const response=await fetch('/api/routes/candidates',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json',[token.headerName]:token.token},body:JSON.stringify({start,end})});const data=await response.json();if(!response.ok)throw new Error(data.error||'경로를 가져오지 못했어요.');drawRoutes(data.candidates);for(const c of data.candidates){const card=document.createElement('div');card.className='candidate';const title=document.createElement('strong');title.textContent=`${c.label} · ${(c.distanceM/1000).toFixed(1)}km · ${Math.ceil(c.durationSec/60)}분`;const note=document.createElement('p');note.textContent=c.dataCoverage;card.append(title,note);byId('candidates').append(card);}status(data.warnings.length?'일부 탐색 옵션은 실패했어요. 확인된 후보만 표시합니다.':`${data.candidates.length}개 실제 후보를 받았어요.`);}catch(e){status(e.message);if(e.message.includes('로그인'))location.assign('/login.html');}finally{button.disabled=false;}};
(async()=>{try{const me=await (await fetch('/api/auth/me')).json();byId('user').textContent=me.authenticated?`${me.loginId} 로그인 중`:'로그인이 필요합니다';byId('logout').hidden=!me.authenticated;byId('logout').onclick=async()=>{const t=await csrf();await fetch('/api/auth/logout',{method:'POST',credentials:'same-origin',headers:{[t.headerName]:t.token}});location.reload();};const data=await (await fetch('/api/facilities/status')).json();byId('data-status').textContent=`횡단보도 ${data.crosswalkCount}건 · ${data.coverage}`;facilitySample=await (await fetch('/api/facilities/sample')).json();await initMap();}catch(e){status('초기 화면을 불러오지 못했어요.');}})();
