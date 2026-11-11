const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const context={};vm.createContext(context);
const source=fs.readFileSync('src/main/resources/static/js/map-data.js','utf8').replace('return {attach};','return {attach,readFacilities};');
vm.runInContext(source+'\nthis.read=MapData.readFacilities;',context);
test('지도 목록의 알수없는 종류·잘못된 좌표는 멈추기 전에 거절한다',()=>{
 for(const p of [{kind:'NEW_KIND',lat:37,lng:127},{kind:'CCTV',lat:NaN,lng:127},{kind:'CCTV',lat:37,lng:200},null])
  assert.throws(()=>context.read({facilities:[p]}),/시설 응답/);
 assert.throws(()=>context.read({facilities:{}}),/시설 응답/);
});
test('빈 시설과 정상 시설은 유지하고 잘못된 영역 목록을 거절한다',()=>{
 assert.equal(context.read({facilities:[]}).length,0);
 const p={kind:'CCTV',lat:37.5,lng:127};assert.equal(context.read({facilities:[p]})[0],p);
 assert.throws(()=>context.read({facilities:[p],areas:{}}),/사고 자료 응답/);
});
