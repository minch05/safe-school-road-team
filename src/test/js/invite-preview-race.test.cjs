const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const html=fs.readFileSync('src/main/resources/templates/invite.html','utf8'),source=html.slice(html.indexOf('<script>')+8,html.indexOf('</script>'));
function screen(api){const nodes={};const node=s=>nodes[s]??={value:'123456',hidden:true,disabled:false,classList:{toggle(){}},focus(){},addEventListener(_,f){this.input=f;}};
 vm.runInNewContext(source,{document:{querySelector:node},App:{api,toast(){}},location:{search:''},URLSearchParams,setTimeout(){}});return {node};}
test('코드 발급 중 중복 클릭은 추가 발급 요청을 보내지 않는다',async()=>{
 let resolve,calls=0;const s=screen(()=>{calls++;return new Promise(r=>resolve=r);});
 const pending=s.node('#newCode').onclick();await s.node('#newCode').onclick();assert.equal(calls,1);assert.equal(s.node('#newCode').disabled,true);
 resolve({code:'234567'});await pending;assert.equal(s.node('#code').textContent,'234 567');assert.equal(s.node('#newCode').disabled,false);
});
test('새 코드 발급 결과가 불명확하면 기존 코드를 숨기고 재발급을 안내한다',async()=>{
 let calls=0;const s=screen(async()=>{if(++calls===1)return {code:'234567'};throw Error('통신 오류');});
 await s.node('#newCode').onclick();await s.node('#newCode').onclick();assert.equal(s.node('#code').textContent,'––– –––');assert.match(s.node('#err').textContent,/이전 코드는 사용할 수 없어요/);assert.equal(s.node('#newCode').disabled,false);
});
test('확인 대기 중 코드가 바뀌면 늦은 보호자 확인을 표시하지 않는다',async()=>{
 let resolve;const s=screen(()=>new Promise(r=>resolve=r));const pending=s.node('#check').onclick();s.node('#codeInput').value='654321';s.node('#codeInput').input();resolve({previewToken:'test-token',guardian:'이전 보호자'});await pending;assert.equal(s.node('#confirmBox').hidden,true);
});
test('확인 후 입력 변경은 연동 확인을 취소한다',async()=>{
 let calls=0;const s=screen(async()=>{calls++;return {previewToken:'test-token',guardian:'검수 보호자'};});await s.node('#check').onclick();assert.equal(s.node('#confirmBox').hidden,false);
 s.node('#codeInput').value='654321';s.node('#codeInput').input();await s.node('#accept').onclick();assert.equal(calls,1);assert.equal(s.node('#confirmBox').hidden,true);
});
test('연동 중 중복 클릭을 막고 확인한 코드만 요청한다',async()=>{
 let resolve;const requests=[];const s=screen((url,o)=>{requests.push([url,o.body.code]);return url.endsWith('preview')?Promise.resolve({previewToken:'test-token',guardian:'검수'}):new Promise(r=>resolve=r);});
 await s.node('#check').onclick();const pending=s.node('#accept').onclick();assert.equal(s.node('#codeInput').disabled,true);await s.node('#accept').onclick();assert.equal(requests.length,2);assert.equal(requests[1][1],'123456');resolve({ok:true});await pending;
});
test('연동 실패는 조작을 풀고 보호자를 다시 확인하게 한다',async()=>{
 const s=screen(async url=>{if(url.endsWith('preview'))return {previewToken:'test-token',guardian:'검수'};throw Error('만료된 코드예요');});
 await s.node('#check').onclick();await s.node('#accept').onclick();assert.equal(s.node('#codeInput').disabled,false);assert.equal(s.node('#confirmBox').hidden,true);assert.equal(s.node('#err').textContent,'만료된 코드예요');
});
test('연동 응답이 유실돼도 서버에서 연결됨을 확인하면 중복 연동을 보내지 않는다',async()=>{
 const calls=[];const s=screen(async url=>{calls.push(url);if(url.endsWith('preview'))return {previewToken:'test-token',guardian:'검수'};if(url.endsWith('status'))return {linked:true,guardian:'검수'};throw Error('통신 오류');});
 await s.node('#check').onclick();await s.node('#accept').onclick();await s.node('#accept').onclick();
 assert.deepEqual(calls,['/api/invite/preview','/api/invite/redeem','/api/invite/status']);assert.equal(s.node('#codeInput').disabled,true);
});
