const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const source=fs.readFileSync('src/main/resources/static/js/route-new.js','utf8');
// 실제 저장 이벤트를 가짜 화면/지연 API에 연결해 동시 클릭과 응답 유실을 재현한다.
function screen(api){
 const nodes={};const node=s=>nodes[s]??=( {value:'통학',disabled:false,hidden:true,textContent:'',focus(){},setAttribute(){},addEventListener(_,fn){this.click=fn;}} );
 const controls=['#routeName','#nameChips button','#toneSeg button','#backToCompare','#draftRetry'].map(node);
 const context={App:{api},CFG:{},places:{start:{fromMine:true},end:{fromMine:true}},authRecovery:()=>false,newId:()=> 'request-1',location:{},saving:false,draftId:'draft-1',saveRequestId:null,$:node,$$:()=>controls};
 vm.createContext(context);
 const lock=source.slice(source.indexOf('  function lockSaveControls'),source.indexOf('  let placeVersion'));
 const save=source.slice(source.indexOf("  $('#saveBtn')?.addEventListener"),source.indexOf("  $('#backToCompare')?.addEventListener"));
 vm.runInContext(lock+'\n'+save,context);return {context,nodes,controls,click:node('#saveBtn').click};
}
test('저장 대기 중 중복 클릭을 막고 입력·말투·뒤로 버튼을 잠근다',async()=>{
 let resolve,calls=0;const s=screen(()=>{calls++;return new Promise(r=>resolve=r);});
 const pending=s.click();assert.equal(s.context.saving,true);assert.ok(s.controls.every(c=>c.disabled));
 await s.click();assert.equal(calls,1);resolve({id:12});await pending;assert.equal(s.context.location.href,'/routes/12?saved=1');
});
test('저장 응답 유실 뒤 조작을 풀고 동일 요청 번호로 재시도한다',async()=>{
 const requests=[];const s=screen(async(_,o)=>{requests.push(o.body.requestId);if(requests.length===1)throw new Error('연결 오류');return {id:12};});
 await s.click();assert.equal(s.context.saving,false);assert.ok(s.controls.every(c=>!c.disabled));
 await s.click();assert.deepEqual(requests,['request-1','request-1']);
});
test('초안 만료는 재생성 전 저장을 차단하고 잘못된 저장 결과로 이동하지 않는다',async()=>{
 const s=screen(async()=>{throw Object.assign(new Error('만료'),{code:'DRAFT_UNAVAILABLE'});});
 await s.click();assert.equal(s.context.draftId,null);assert.equal(s.nodes['#saveBtn'].disabled,true);assert.equal(s.nodes['#draftRetry'].hidden,false);
 const broken=screen(async()=>({id:'wrong'}));await broken.click();assert.equal(broken.context.location.href,undefined);assert.equal(broken.context.saving,false);
});
