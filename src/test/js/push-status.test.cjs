const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
function push(api){const context={navigator:{serviceWorker:{getRegistration:async()=>({pushManager:{getSubscription:async()=>({endpoint:'https://push.example/device'})}})}},window:{isSecureContext:true,PushManager:{},Notification:{}},Notification:{permission:'granted'},App:{api}};vm.createContext(context);vm.runInContext(fs.readFileSync('src/main/resources/static/js/push.js','utf8')+'\nthis.push=Push;',context);return context.push;}
test('브라우저 구독만으로 켜짐을 단정하지 않고 서버 등록을 확인한다',async()=>{
 for(const [registered,label] of [[true,'on'],[false,'unregistered'],[null,'unconfirmed']]){
  const p=push(async(url,o)=>{assert.equal(url,'/api/push/status');assert.equal(o.body.endpoint,'https://push.example/device');assert.equal(o.redirectOnAuth,false);return {registered};});assert.equal(await p.status(),label);
 }
});
test('서버 상태 조회 실패는 꺼짐이나 켜짐 대신 확인 못함이다',async()=>{assert.equal(await push(async()=>{throw Error('offline');}).status(),'unconfirmed');});
