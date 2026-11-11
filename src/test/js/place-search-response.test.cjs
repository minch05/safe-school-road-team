const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const source=fs.readFileSync('src/main/resources/static/js/route-new.js','utf8');
const code=source.slice(source.indexOf('  function checkPlaceResults('),source.indexOf('  let mineCache'));
const good={title:'학교',address:'서울',lat:37.59,lng:126.94,isPlace:true};
function screen(api){const result={};const ctx={App:{api,esc:s=>s},searchVersions:{end:0},$:()=>result,$$:()=>[],setPlace(){throw Error('잘못된 위치 선택');}};vm.createContext(ctx);vm.runInContext(code,ctx);return {ctx,result};}
test('잘못된 좌표·형식·이름을 지도 선택 전에 거절한다',async()=>{
 for(const response of [{},[null],[{...good,lat:null}],[{...good,lng:181}],[{...good,title:''}],[{...good,isPlace:'true'}]]){
  const s=screen(async()=>response);await s.ctx.search({},'end','학교');assert.match(s.result.innerHTML,/위치를 확인하지 못/);
 }
 const s=screen(async()=>[good]);await s.ctx.search({},'end','학교');assert.match(s.result.innerHTML,/data-i="0"/);
});
test('새 검색 뒤 도착한 이전 결과는 현재 결과를 덮어쓰지 않는다',async()=>{
 let finish;let calls=0;const s=screen(()=>++calls===1?new Promise(r=>finish=r):Promise.resolve([]));
 const old=s.ctx.search({},'end','학교');await s.ctx.search({},'end','공원');const current=s.result.innerHTML;
 finish([good]);await old;assert.equal(s.result.innerHTML,current);assert.match(current,/찾지 못/);
});
