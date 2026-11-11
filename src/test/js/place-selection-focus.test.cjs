const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const source=fs.readFileSync('src/main/resources/static/js/route-new.js','utf8');
function setup(){
 const nodes={},events=[];const node=s=>nodes[s]??={hidden:true,open:false,classList:{add(){}},focus(){events.push({focus:s,open:node('[data-chosen]').open});}};
 const context={places:{start:null,end:null},markers:{start:null,end:null},searchVersions:{start:0,end:0},placeVersion:0,candidateRequest:null,
 $:node,ICON:{},App:{flagMarker(){return {addTo(){return this;},dragging:{enable(){}},on(){}};}},pinMap:{},fitPins(){},syncJourney(){},editPlace(which,focus){events.push({edit:which,focus});}};
 vm.createContext(context);vm.runInContext(source.slice(source.indexOf('  function setPlace('),source.indexOf('  function fitPins(')),context);
 return {context,events,nodes};
}
test('주소 핀 선택은 이름 입력을 펼친 다음 초점을 준다',()=>{
 const s=setup();s.context.setPlace('end',{name:'',lat:37.59,lng:126.94});
 assert.deepEqual(s.events,[{focus:'[data-name]',open:true}]);
 assert.equal(s.context.places.end.name,'');
});
test('이름 있는 목적지 선택 뒤 출발 입력으로 이동하고 두 장소 선택 후 비교 버튼으로 이동한다',()=>{
 const s=setup();s.context.setPlace('end',{name:'학교',lat:37.59,lng:126.94});
 assert.deepEqual(s.events,[{edit:'start',focus:true}]);
 s.context.setPlace('start',{name:'공원',lat:37.6,lng:126.95});
 assert.deepEqual(s.events.slice(1),[{edit:null,focus:false},{focus:'#findBtn',open:false}]);
 assert.equal(s.context.places.end.name,'학교');assert.equal(s.context.places.start.name,'공원');
});
test('비교 버튼은 두 위치가 선택된 뒤 나타난다',()=>{
 const nodes={};const ctx={places:{start:null,end:null},$:s=>nodes[s]??={}};
 vm.createContext(ctx);vm.runInContext(source.slice(source.indexOf('  function syncJourney('),source.indexOf("  $$('[data-edit]')",source.indexOf('  function syncJourney('))),ctx);
 ctx.syncJourney();assert.equal(nodes['#findBtn'].hidden,true);
 ctx.places.end={name:'학교'};ctx.syncJourney();assert.equal(nodes['#findBtn'].hidden,true);
 ctx.places.start={name:'공원'};ctx.syncJourney();assert.equal(nodes['#findBtn'].hidden,false);
});
