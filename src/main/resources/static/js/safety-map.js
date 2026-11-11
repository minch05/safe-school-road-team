(() => {
  const map=App.makeMap('safetyMap',{zoom:16});
  MapData.attach(map,'#safetyLayers',()=>map.getBounds());
  const panelToggle=document.querySelector('#atlasPanelToggle');
  if(panelToggle){
    const media=matchMedia('(max-width:700px)'), controls=document.querySelector('#atlasControls');
    let expanded=false;
    function syncPanel(){
      panelToggle.hidden=!media.matches;controls.hidden=media.matches&&!expanded;
      panelToggle.setAttribute('aria-expanded',expanded);
      panelToggle.innerHTML=(expanded?'시설·사고 정보 접기':'시설·사고 정보 보기')+' <span aria-hidden="true">'+(expanded?'⌄':'⌃')+'</span>';
      map.attributionControl.setPosition(media.matches?'topright':'bottomright');
      map.invalidateSize();
    }
    panelToggle.onclick=()=>{expanded=!expanded;syncPanel();};
    media.addEventListener('change',syncPanel);syncPanel();
  }
  const form=document.querySelector('#mapSearch'), results=document.querySelector('#mapResults');
  const queryInput=document.querySelector('#mapQuery'), clear=document.querySelector('#mapClear');
  const selection=document.querySelector('#mapSelection');
  let searchId=0, selectedPlace=null, selectedMarker=null;
  queryInput.oninput=()=>{searchId++;clear.hidden=!queryInput.value;results.hidden=true;form.querySelector('[type=submit]').disabled=false;selection.hidden=true;selectedPlace=null;if(selectedMarker)map.removeLayer(selectedMarker);};
  clear.onclick=()=>{searchId++;form.querySelector('[type=submit]').disabled=false;queryInput.value='' ;clear.hidden=true;results.hidden=true;selection.hidden=true;selectedPlace=null;if(selectedMarker)map.removeLayer(selectedMarker);queryInput.focus();};
  queryInput.onkeydown=e=>{if(e.key==='Escape'){searchId++;results.hidden=true;form.querySelector('[type=submit]').disabled=false;}};
  document.querySelector('#mapDirections').onclick=()=>{
    if(!selectedPlace)return;
    try{sessionStorage.setItem('ssr-map-destination',JSON.stringify({place:selectedPlace,created:Date.now()}));}catch(e){}
  };
  form.onsubmit=async e=>{
    e.preventDefault(); const query=document.querySelector('#mapQuery').value.trim();
    if(query.length<2){App.toast('두 글자 이상으로 검색해 주세요.');return;}
    const token=++searchId, button=form.querySelector('[type=submit]');button.disabled=true;results.hidden=false;results.textContent='장소를 찾는 중이에요…';
    try {
      const data=await App.api('/api/places/search?q='+encodeURIComponent(query));
      if(token!==searchId)return;
      results.replaceChildren();
      data.forEach(p=>{
        const item=document.createElement('button');item.type='button';item.className='facility-list-item';item.textContent=`${p.title||p.address}${p.address?' · '+p.address:''}`;
        item.onclick=()=>{
          map.setView([p.lat,p.lng],17);results.hidden=true;queryInput.value=p.title||p.address;clear.hidden=false;
          selectedPlace={name:p.isPlace?(p.title||'').slice(0,30):'',lat:p.lat,lng:p.lng};
          if(selectedMarker)map.removeLayer(selectedMarker);
          selectedMarker=App.flagMarker([p.lat,p.lng],'🚩').addTo(map);
          document.querySelector('#mapSelectedName').textContent=p.title||p.address;
          selection.hidden=false;
        };results.append(item);
      });
      if(!data.length)results.textContent='찾은 장소가 없어요. 학교 이름이나 도로명으로 다시 찾아 주세요.';
    }catch(error){if(token===searchId)results.textContent=error.message;}
    finally{if(token===searchId)button.disabled=false;}
  };
})();
