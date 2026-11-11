// 공공데이터 레이어. 좌표는 사용자가 조회한 지도 범위 또는 고른 경로 범위만 요청한다.
// 위치 추적 지도에는 연결하지 않는다. 지도를 움직여도 자동 요청하지 않는다.
const MapData = (() => {
  const TYPES = [
    ['CROSSWALK','횡단보도','crosswalk','#b56b00'], ['PEDESTRIAN_SIGNAL','보행신호등','signal','#287d46'], ['CCTV','CCTV','cctv','#5c55b9'],
    ['EMERGENCY_BELL','비상벨','bell','#ce5272'], ['CHILD_SAFETY_HOUSE','지킴이집','shield','#087b78'],
    ['SCHOOL','학교','school','#287d46'], ['SCHOOL_ZONE','보호구역','zone','#ac6500'],
    ['SECURITY_LIGHT','보안등','light','#7d6820']
  ];
  function readFacilities(data){
    const validKinds=new Set(TYPES.map(t=>t[0]));
    if(!Array.isArray(data.facilities) || data.facilities.some(p=>!p || !validKinds.has(p.kind) ||
      !Number.isFinite(p.lat) || !Number.isFinite(p.lng) || p.lat < -90 || p.lat > 90 || p.lng < -180 || p.lng > 180))
      throw new Error('시설 응답을 확인하지 못했어요. 다시 조회해 주세요.');
    if(data.areas!==undefined && !Array.isArray(data.areas))throw new Error('사고 자료 응답을 확인하지 못했어요. 다시 조회해 주세요.');
    return data.facilities;
  }
  let catalogPromise;
  function attach(map, root, getBounds) {
    const panel=document.querySelector(root), selected=new Set(['CROSSWALK']);
    const layer=MapKit.layerGroup().addTo(map), areas=MapKit.layerGroup().addTo(map);
    if (!map.getPane('publicFacilities')) { map.createPane('publicFacilities').style.zIndex=450; }
    let points=[], request=0, abort, loadedBounds=null, coverage=[];
    panel.innerHTML=`<div class="layer-heading"><b>지도에 표시할 안전정보</b><span class="tag">공공데이터</span></div>
      <div class="layer-filters">${TYPES.map(([kind,label,icon])=>`<button type="button" data-kind="${kind}" aria-pressed="${selected.has(kind)}">${App.svg(icon)}<span>${label}</span><small data-count hidden></small></button>`).join('')}
      <button type="button" data-accidents aria-pressed="false">${App.svg('warning')}<span>사고다발</span></button></div>
      <div class="layer-actions"><button type="button" class="btn soft" data-load>이 지도에서 조회</button><button type="button" class="link" data-clear>지우기</button></div>
      <p class="hint" data-status role="status">시설을 골라 표시해 보세요. 위치 추적 정보는 사용하지 않아요.</p><p class="hint" data-coverage></p>
      <details class="facility-list"><summary>표시한 시설 목록</summary><div data-list></div></details>
      <details class="map-source"><summary>데이터 출처와 알아둘 점</summary><p class="hint">출처: 서울시·공공데이터포털·생활안전지도. 횡단보도 신호는 추정이며 실제 신호를 확인해 주세요. 사고 영역을 누르면 자료 기준 연도를 볼 수 있어요. 보호구역 핀은 대상 시설 위치이며 정확한 구역 경계가 아니에요. CCTV·비상벨·보안등은 지역별 누락이 있을 수 있어요. 준비 중인 시설 종류는 자료가 적재되면 표시돼요. 시설이 표시되지 않아도 안전하거나 시설이 없다는 뜻은 아니에요.</p></details>`;
    const status=panel.querySelector('[data-status]'), load=panel.querySelector('[data-load]');
    catalogPromise ||= App.api('/api/facilities/catalog');
    catalogPromise.then(catalog=>{
      coverage=catalog.coverage||[];renderCoverage();
      const counts={}; catalog.counts.forEach(row=>{counts[row.kind]=(counts[row.kind]||0)+Number(row.n);});
      panel.querySelectorAll('[data-kind]').forEach(button=>{
        if(!counts[button.dataset.kind]) {
          selected.delete(button.dataset.kind); button.disabled=true; button.setAttribute('aria-pressed','false');
          button.title='서울 좌표 자료가 아직 준비되지 않았어요.';
          button.querySelector('[data-count]').hidden=false;button.querySelector('[data-count]').textContent='준비 중';
        }
      });
    }).catch(()=>{catalogPromise=null;});
    function renderCoverage(){
      const notes=coverage.filter(row=>selected.has(row.kind)).map(row=>{
        const districts=(row.districts||'').split(',').filter(Boolean), name=TYPES.find(t=>t[0]===row.kind)?.[1]||row.kind;
        if(districts.length>0&&districts.length<=3)return `${name} 자료가 있는 지역: ${districts.join('·')} (다른 지역 미확인)`;
        return '';
      }).filter(Boolean);
      panel.querySelector('[data-coverage]').textContent=notes.join(' · ');
    }
    let showAccidents=false;
    function invalidate() {
      request++; abort?.abort(); load.disabled=false; points=[]; loadedBounds=null; layer.clearLayers(); areas.clearLayers();
      panel.querySelector('[data-list]').replaceChildren();
      panel.querySelectorAll('[data-kind]:not(:disabled) [data-count]').forEach(el=>el.hidden=true);
    }
    panel.querySelectorAll('[data-kind]').forEach(button=>button.onclick=()=>{
      const kind=button.dataset.kind;
      selected.has(kind)?selected.delete(kind):selected.add(kind);
      button.setAttribute('aria-pressed',selected.has(kind));
      renderCoverage();
      invalidate(); load.click();
    });
    panel.querySelector('[data-accidents]').onclick=e=>{
      showAccidents=!showAccidents; e.currentTarget.setAttribute('aria-pressed',showAccidents);
      invalidate(); load.click();
    };
    panel.querySelector('[data-clear]').onclick=()=>{invalidate();status.textContent='안전정보 표시를 지웠어요.';};
    function description(p) {
      const sourceNames={SMART_POLE_LIGHT:'서울시 스마트폴 보안등 기능(일부 시설)',SEOUL_CROSSWALK:'서울시 횡단보도',SEOUL_PED_SIGNAL:'서울시 보행등 위치',SCHOOL_STD:'전국 학교 위치',SECURITY_LIGHT_DISTRICT:'구별 보안등 공식 자료',SECURITY_LIGHT_STD:'보안등 공개 자료',CCTV_STD:'CCTV 공개 자료',BELL_STD:'비상벨 공개 자료',BELL_DISTRICT:'구별 비상벨 공식 자료(점검 기록 기준)',CHILD_SAFETY_HOUSE:'생활안전지도 지킴이집',SCHOOL_ZONE_STD:'어린이보호구역 대상시설'};
      return `<b>${App.esc(p.kindLabel)}</b>${p.name?`<br>${App.esc(p.name)}`:''}${p.signal?`<br>${App.esc(p.signal)}`:''}${p.kind==='PEDESTRIAN_SIGNAL'?'<br>보행등 위치 자료예요. 어느 건널목의 신호인지는 현장에서 확인해 주세요.':''}${p.kind==='SCHOOL_ZONE'?'<br>보호구역 대상 시설 위치예요. 실제 지정 도로 경계가 아니에요.':''}${p.kind==='SCHOOL'?'<br>학교 대표 위치예요. 실제 정문·후문 위치를 확인해 주세요.':''}${p.kind==='CHILD_SAFETY_HOUSE'?'<br>지정 위치 자료예요. 지금 영업 중이거나 도움을 받을 수 있다는 확인은 아니에요.':''}${['CCTV','EMERGENCY_BELL','SECURITY_LIGHT'].includes(p.kind)?'<br>설치 위치 자료예요. 현재 작동·연결·밝기를 확인한 정보는 아니에요.':''}<br><small>${App.esc(p.baseDate ? (p.baseDateLabel||'자료 기준일')+': '+(p.kind==='CHILD_SAFETY_HOUSE'?p.baseDate.slice(0,4)+'년':p.baseDate) : '자료 기준일 미확인')}<br>출처: ${App.esc(sourceNames[p.source]||p.source)}<br>현장 확인일은 아니에요 · 실제 시설 상태를 확인해 주세요</small>`;
    }
    function draw() {
      layer.clearLayers();
      const bins=new Map();
      points.forEach(p=>{
        const xy=map.latLngToContainerPoint([p.lat,p.lng]);
        const key=`${Math.floor(xy.x/64)},${Math.floor(xy.y/64)}`;
        if(!bins.has(key)) bins.set(key,[]); bins.get(key).push(p);
      });
      bins.forEach(group=>{
        const p=group[0], type=TYPES.find(t=>t[0]===p.kind)||TYPES[0];
        const color=new Set(group.map(x=>x.kind)).size>1?'#596579':type[3];
        const many=group.length>1;
        const ll=many?[group.reduce((s,x)=>s+x.lat,0)/group.length,group.reduce((s,x)=>s+x.lng,0)/group.length]:[p.lat,p.lng];
        const marker=MapKit.marker(ll,{pane:'publicFacilities',keyboard:true,
          title:many?`시설 ${group.length}개 · 확대해서 보기`:p.kindLabel,
          icon:MapKit.divIcon({className:'map-poi',html:`<span style="--poi-color:${color}">${many?group.length:App.svg(type[2])}</span>`,iconSize:[44,44],iconAnchor:[22,22]})});
        if(many && map.getZoom()<18) marker.on('click',()=>map.setView(ll,map.getZoom()+1));
        else marker.bindPopup(group.slice(0,8).map(description).join('<hr>')+(group.length>8?'<br>나머지는 시설 목록에서 확인해 주세요.':''));
        marker.addTo(layer);
      });
    }
    map.on('moveend zoomend',()=>{draw();if(loadedBounds&&!loadedBounds.contains(getBounds()))status.textContent='지도를 옮겼어요. 새 범위의 안전정보를 다시 표시해 주세요.';});
    load.onclick=async()=>{
      invalidate(); const token=request;
      if(!selected.size&&!showAccidents) {status.textContent='표시할 시설이나 사고다발지역을 하나 이상 골라 주세요.';return;}
      const controller=new AbortController(); abort=controller; load.disabled=true;status.textContent='이 범위의 안전정보를 확인하고 있어요…';
      const b=getBounds();
      const query=new URLSearchParams({south:b.getSouth(),west:b.getWest(),north:b.getNorth(),east:b.getEast(),kinds:[...selected].join(','),accidents:showAccidents});
      const timer=setTimeout(()=>controller.abort(),12000);
      try {
        const response=await fetch('/api/facilities?'+query,{signal:controller.signal});
        const data=await response.json();
        if(token!==request)return;
        if(!response.ok)throw new Error(data.error||'안전정보를 불러오지 못했어요.');
        if(data.tooWide) {status.textContent='범위가 넓어요. 지도를 확대하거나 더 짧은 경로에서 확인해 주세요.';return;}
        points=readFacilities(data); loadedBounds=b; draw();
        panel.querySelectorAll('[data-kind]:not(:disabled)').forEach(button=>{
          const count=button.querySelector('[data-count]');count.hidden=!selected.has(button.dataset.kind);
          count.textContent=points.filter(p=>p.kind===button.dataset.kind).length;
        });
        let drawnAreas=0,failedAreas=0;
        (data.areas||[]).forEach(area=>{
          const areaType={CHILD_ACCIDENT_SCHOOLZONE:'보호구역 내 어린이 사고',CHILD_PEDESTRIAN_ACCIDENT:'보행 어린이 사고',ELDERLY_PEDESTRIAN_ACCIDENT:'노인 보행자 사고'}[area.kind]||'사고다발지역';
          try {MapKit.geoJSON(JSON.parse(area.geoJson),{style:{color:'#bb560b',weight:2,fillOpacity:.12}})
            .bindPopup(`<b>${App.esc(areaType)}</b><br>${App.esc(area.name)}<br><small>한국도로교통공단 · ${App.esc(area.baseYear?area.baseYear+'년 사고 자료':'기준 연도 미확인')}<br>현재 사고 확률이 아니에요. 여러 종류의 영역이 겹칠 수 있어요.</small>`).addTo(areas);drawnAreas++;}catch(e){failedAreas++;}
        });
        status.textContent=`시설 ${points.length}개 · 사고 영역 ${drawnAreas}곳 표시${failedAreas?` · 사고 영역 ${failedAreas}곳은 그리지 못했어요. 다시 조회해 주세요.`:''}${data.truncated?' · 시설이 많은 종류는 일부만 표시했어요. 확대해서 다시 조회해 주세요.':points.length===0?' · 표시할 시설이 없거나 원본이 누락되었을 수 있어요.':''}`;
        if(!b.contains(getBounds()))status.textContent+=' · 조회 중 지도가 이동했어요. 표시한 자료는 이전 조회 범위예요. 새 범위에서 다시 조회해 주세요.';
        const list=panel.querySelector('[data-list]');
        const buckets=TYPES.map(([kind])=>points.filter(p=>p.kind===kind));
        const visible=[];
        for(let i=0;visible.length<Math.min(50,points.length);i++){
          buckets.forEach(bucket=>{if(bucket[i]&&visible.length<50)visible.push(bucket[i]);});
        }
        visible.forEach(p=>{
          const button=document.createElement('button');button.type='button';button.className='facility-list-item';
          button.textContent=`${p.kindLabel} · ${p.name||'위치 보기'}${p.signal?' · '+p.signal:''}`;
          button.onclick=()=>{map.setView([p.lat,p.lng],18);MapKit.popup().setLatLng([p.lat,p.lng]).setContent(description(p)).openOn(map);};
          list.append(button);
        });
        if(points.length>50){const hint=document.createElement('p');hint.className='hint';hint.textContent='목록에는 종류별로 나누어 50개까지 보여 줘요. 나머지는 지도를 확대해 확인해 주세요.';list.append(hint);}
      }catch(e){if(token===request)status.textContent=e.name==='AbortError'?'응답이 늦어요. 잠시 뒤 다시 확인해 주세요.':e.message;}
      finally{clearTimeout(timer);if(token===request)load.disabled=false;}
    };
    return {clear:invalidate};
  }
  return {attach};
})();
