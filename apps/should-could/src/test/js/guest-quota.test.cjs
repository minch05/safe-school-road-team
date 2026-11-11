const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const source=fs.readFileSync('src/main/resources/static/js/route-new.js','utf8');
function setup(api,guest=true){
 const node={textContent:3};const ctx={App:{api},CFG:{guest},$:()=>node};vm.createContext(ctx);
 vm.runInContext(source.slice(source.indexOf('  let quotaVersion=0;'),source.indexOf("  $('#retryCandidates').onclick")),ctx);
 return {node,refresh:()=>vm.runInContext('refreshGuestQuota()',ctx),invalidate:()=>vm.runInContext('++quotaVersion',ctx)};
}
test('실패 뒤 횟수만 읽고 경로는 재요청하지 않는다',async()=>{
 const urls=[];const s=setup(async url=>{urls.push(url);return {left:2};});await s.refresh();
 assert.equal(s.node.textContent,2);assert.deepEqual(urls,['/api/explore/quota']);
});
test('확인 실패와 잘못된 응답은 이전 횟수를 확정 표시하지 않는다',async()=>{
 for(const api of [async()=>{throw Error('연결 오류');},async()=>({left:-1}),async()=>({left:'2'})]){
  const s=setup(api);await s.refresh();assert.equal(s.node.textContent,'확인 못함');
 }
});
test('이전 응답은 새 조회 결과를 덮어쓰지 않는다',async()=>{
 let resolve;const s=setup(()=>new Promise(r=>resolve=r));const pending=s.refresh();s.invalidate();s.node.textContent=1;
 resolve({left:2});await pending;assert.equal(s.node.textContent,1);
});
test('로그인 사용자는 무료 횟수를 조회하지 않는다',async()=>{
 const s=setup(()=>{throw Error('호출하면 안됨');},false);await s.refresh();assert.equal(s.node.textContent,3);
});
