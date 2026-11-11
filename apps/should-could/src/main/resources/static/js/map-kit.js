// 기존 화면 계약을 공식 네이버 SDK에 연결한다. Leaflet은 좌표 계산/키 없는 미리보기용으로만 유지.
window.MapKit = (() => {
  if (!window.naver?.maps) return L;
  const N = naver.maps;
  const ll = p => { const v=L.latLng(p); return new N.LatLng(v.lat,v.lng); };
  const from = p => L.latLng(p.lat(),p.lng());
  const nb = b => { const v=L.latLngBounds(b); return new N.LatLngBounds(ll(v.getSouthWest()),ll(v.getNorthEast())); };
  const style = o => ({strokeColor:o.color,strokeWeight:o.weight,strokeOpacity:o.opacity??1,strokeStyle:o.dashArray?'shortdash':'solid',fillColor:o.fillColor||o.color,fillOpacity:o.fillOpacity??.2});
  function openInfo(map,html,position){
    map.info?.close();
    const content=document.createElement('div');content.className='map-info';
    const close=document.createElement('button');close.type='button';close.className='map-info-close';close.textContent='×';close.setAttribute('aria-label','지도 정보 닫기');
    const body=document.createElement('div');body.innerHTML=html;content.append(close,body);
    const info=new N.InfoWindow({content,maxWidth:300});
    N.Event.addListener(info,'close',()=>map.el.classList.remove('map-info-open'));
    close.onclick=()=>info.close();map.info=info;map.el.classList.add('map-info-open');info.open(map.native,position);return info;
  }
  class MapView {
    constructor(el,opts) {
      this.el=typeof el==='string'?document.getElementById(el):el;
      this.el.classList.add('naver-map'); this.el.dataset.mapProvider='naver';
      this.native=new N.Map(this.el,{center:ll(opts.center),zoom:opts.zoom,minZoom:7,maxZoom:21,
        zoomControl:false,
        logoControlOptions:{position:N.Position.TOP_RIGHT},mapDataControlOptions:{position:N.Position.TOP_RIGHT},
        scaleControl:true});
      if(opts.zoomControl){
        const controls=document.createElement('div');controls.className='native-zoom';controls.setAttribute('aria-label','지도 확대·축소');
        for(const [text,label,delta] of [['+','지도 확대',1],['−','지도 축소',-1]]){
          const button=document.createElement('button');button.type='button';button.textContent=text;button.setAttribute('aria-label',label);
          button.onclick=e=>{e.stopPropagation();this.native.setZoom(Math.max(7,Math.min(21,this.native.getZoom()+delta)));};controls.append(button);
        }
        this.el.append(controls);
      }
      N.Event.addListener(this.native,'click',()=>this.info?.close());
      this.el.addEventListener('keydown',e=>{if(e.key==='Escape')this.info?.close();});
      this.panes={};
      this.attributionControl={setPosition:position=>{const spot=position==='topright'?N.Position.TOP_RIGHT:N.Position.BOTTOM_RIGHT;this.native.setOptions({logoControlOptions:{position:spot},mapDataControlOptions:{position:spot}});}};
      this.observer=new ResizeObserver(()=>this.native.autoResize());this.observer.observe(this.el);
    }
    setView(p,z){this.native.setCenter(ll(p));if(z!==undefined)this.native.setZoom(z);return this;}
    panTo(p){this.native.panTo(ll(p));return this;}
    distance(a,b){return L.latLng(a).distanceTo(L.latLng(b));}
    getZoom(){return this.native.getZoom();}
    getSize(){const s=this.native.getSize();return {x:s.width,y:s.height};}
    getBounds(){const b=this.native.getBounds();return L.latLngBounds(from(b.getSW()),from(b.getNE()));}
    fitBounds(points,o={}){const a=o.paddingTopLeft||o.padding||[0,0],b=o.paddingBottomRight||o.padding||[0,0];
      const bounds=L.latLngBounds(points),size=this.getSize(),crs=L.CRS.EPSG3857;
      const sw=crs.latLngToPoint(bounds.getSouthWest(),0),ne=crs.latLngToPoint(bounds.getNorthEast(),0);
      const width=Math.max(40,size.x-a[0]-b[0]),height=Math.max(40,size.y-a[1]-b[1]);
      const ratio=Math.min(width/Math.max(.000001,Math.abs(ne.x-sw.x)),height/Math.max(.000001,Math.abs(sw.y-ne.y)));
      const zoom=Math.max(7,Math.min(o.maxZoom??19,Math.floor(Math.log2(ratio))));
      const left=crs.latLngToPoint(bounds.getSouthWest(),zoom),right=crs.latLngToPoint(bounds.getNorthEast(),zoom);
      const center=L.point((left.x+right.x)/2+(b[0]-a[0])/2,(left.y+right.y)/2+(b[1]-a[1])/2);
      this.setView(crs.pointToLatLng(center,zoom),zoom);return this;}
    invalidateSize(){this.native.autoResize();return this;}
    containerPointToLatLng(p){return from(this.native.getProjection().fromOffsetToCoord(new N.Point(p[0],p[1])));}
    latLngToContainerPoint(p){return this.native.getProjection().fromCoordToOffset(ll(p));}
    on(events,fn){for(const e of events.split(' '))N.Event.addListener(this.native,({moveend:'idle',zoomend:'zoom_changed'})[e]||e,v=>fn({target:this,latlng:v?.coord?from(v.coord):undefined}));return this;}
    removeLayer(layer){layer.remove();return this;}
    getPane(name){return this.panes[name];}
    createPane(name){return this.panes[name]={style:{}};}
  }
  class Layer {
    constructor(make){this.make=make;this.events=[];}
    addTo(target){if(target instanceof Group){target.addLayer(this);return this;}
      this.map=target;this.native=this.make(target.native);this.native.setMap(target.native);
      for(const [name,fn] of this.events)this.listen(name,fn);
      if(this.popup)this.listen('click',()=>this.openPopup());
      if(this.draggable)this.native.setOptions({draggable:true});return this;}
    on(name,fn){this.events.push([name,fn]);if(this.native)this.listen(name,fn);return this;}
    listen(name,fn){N.Event.addListener(this.native,name,v=>fn({target:this,latlng:v?.coord?from(v.coord):undefined}));}
    bindPopup(html){this.popup=html;if(this.native)this.listen('click',()=>this.openPopup());return this;}
    openPopup(){const p=this.native.getPosition?.()||this.map.native.getCenter();this.info=openInfo(this.map,this.popup,p);return this;}
    remove(){this.info?.close();if(this.native){N.Event.clearInstanceListeners(this.native);this.native.setMap(null);}this.native=null;return this;}
    getLatLng(){return from(this.native.getPosition());}
    setLatLng(p){this.native.setPosition?this.native.setPosition(ll(p)):this.native.setCenter(ll(p));return this;}
    setRadius(r){this.native.setRadius(r);return this;}
  }
  class Group {
    constructor(){this.layers=[];}
    addTo(map){this.map=map;for(const layer of this.layers)layer.addTo(map);return this;}
    addLayer(layer){this.layers.push(layer);if(this.map)layer.addTo(this.map);return this;}
    clearLayers(){this.layers.forEach(l=>l.remove());this.layers=[];return this;}
    bindPopup(html){this.layers.forEach(l=>l.bindPopup(html));return this;}
    remove(){return this.clearLayers();}
  }
  function marker(p,o={}){
    const layer=new Layer(map=>{
      let icon;
      if(o.icon){
        const content=document.createElement('div');content.className=o.icon.className||'';content.innerHTML=o.icon.html;
        content.tabIndex=0;content.setAttribute('role','button');content.setAttribute('aria-label',o.title||content.textContent.trim()||'안전시설 정보');
        content.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();N.Event.trigger(layer.native,'click',{});}});
        icon={content,size:new N.Size(...(o.icon.iconSize||[44,44])),anchor:new N.Point(...(o.icon.iconAnchor||[22,22]))};
      }
      return new N.Marker({position:ll(p),map,title:o.title||'',zIndex:o.pane==='publicFacilities'?450:500,icon});
    });
    layer.dragging={enable(){layer.draggable=true;if(layer.native)layer.native.setOptions({draggable:true});}};return layer;
  }
  function geoJSON(data,o={}){
    const group=new Group();const s=style(o.style||{});
    function geometry(g){if(!g)return;
      if(g.type==='Polygon')group.addLayer(new Layer(map=>new N.Polygon({map,clickable:true,paths:g.coordinates.map(r=>r.map(c=>new N.LatLng(c[1],c[0]))),...s})));
      else if(g.type==='MultiPolygon')g.coordinates.forEach(c=>geometry({type:'Polygon',coordinates:c}));
      else if(g.type==='GeometryCollection')g.geometries.forEach(geometry);
      else if(g.type==='Point')group.addLayer(marker([g.coordinates[1],g.coordinates[0]]));
    }
    if(data.type==='FeatureCollection')data.features.forEach(f=>geometry(f.geometry));else geometry(data.type==='Feature'?data.geometry:data);
    return group;
  }
  return {map:(el,o)=>new MapView(el,o),latLngBounds:L.latLngBounds,divIcon:o=>o,marker,
    layerGroup:()=>new Group(),polyline:(points,o={})=>new Layer(map=>new N.Polyline({map,path:points.map(ll),clickable:true,...style(o)})),
    circle:(p,o={})=>new Layer(map=>new N.Circle({map,center:ll(p),radius:o.radius,...style(o)})),
    circleMarker:(p,o={})=>marker(p,{icon:{className:'',html:`<span class="location-dot" style="width:${o.radius*2}px;height:${o.radius*2}px;background:${o.fillColor};border:${o.weight}px solid ${o.color}"></span>`,iconSize:[o.radius*2+6,o.radius*2+6],iconAnchor:[o.radius+3,o.radius+3]}}),
    geoJSON,imageOverlay:(url,b,o={})=>new Layer(map=>new N.GroundOverlay(url,nb(b),{map,opacity:o.opacity??1,clickable:false})),
    popup:()=>({setLatLng(p){this.p=p;return this;},setContent(html){this.html=html;return this;},openOn(map){openInfo(map,this.html,ll(this.p));return this;}})
  };
})();
