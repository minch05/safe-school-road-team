const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
function worker(clients){
 const events={};const ctx={URL,self:{location:{origin:'https://school.example'},clients,addEventListener:(type,fn)=>events[type]=fn}};
 vm.createContext(ctx);vm.runInContext(fs.readFileSync('src/main/resources/static/sw.js','utf8'),ctx);
 return async url=>{let done,closed=false;events.notificationclick({notification:{data:{url},close(){closed=true;}},waitUntil(p){done=p;}});await done;assert.ok(closed);};
}
test('알림은 창 이동이 끝난 뒤 해당 창에 포커스를 준다',async()=>{
 const order=[];const client={url:'https://school.example/',async navigate(url){order.push('navigate:'+url);return {async focus(){order.push('focus');}};},focus(){throw Error('old focus');}};
 await worker({matchAll:async()=>[client],openWindow:async()=>{throw Error('unexpected new window');}})('/children/1');
 assert.deepEqual(order,['navigate:https://school.example/children/1','focus']);
});
test('닫힌 창이나 창 목록 조회 실패도 새 창 열기로 이어진다',async()=>{
 for(const listFailure of [false,true]){
  const opened=[];const client={url:'https://school.example/',focus(){},navigate:async()=>{throw Error('closed');}};
  await worker({matchAll:async()=>{if(listFailure)throw Error('unavailable');return [client];},openWindow:async url=>opened.push(url)})('/children/1');
  assert.deepEqual(opened,['https://school.example/children/1']);
 }
});
test('외부주소와 잘못된 알림 주소는 서비스 홈으로 이동한다',async()=>{
 for(const url of ['https://other.example/','//other.example/','javascript:alert(1)','https://user:pass@school.example/','http://school.example/',{}]){
  const opened=[];await worker({matchAll:async()=>[],openWindow:async url=>opened.push(url)})(url);
  assert.deepEqual(opened,['https://school.example/']);
 }
});
