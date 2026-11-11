const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
function push(api){const context={navigator:{serviceWorker:{getRegistration:async()=>({pushManager:{getSubscription:async()=>({endpoint:'https://push.example/device'})}})}},window:{isSecureContext:true,PushManager:{},Notification:{}},Notification:{permission:'granted'},App:{api}};vm.createContext(context);vm.runInContext(fs.readFileSync('src/main/resources/static/js/push.js','utf8')+'\nthis.push=Push;',context);return context.push;}
test('브라우저 구독만으로 켜짐을 단정하지 않고 서버 등록을 확인한다',async()=>{
 for(const [registered,label] of [[true,'on'],[false,'unregistered'],[null,'unconfirmed']]){
  const p=push(async(url,o)=>{assert.equal(url,'/api/push/status');assert.equal(o.body.endpoint,'https://push.example/device');assert.equal(o.redirectOnAuth,false);return {registered};});assert.equal(await p.status(),label);
 }
});
test('서버 상태 조회 실패는 꺼짐이나 켜짐 대신 확인 못함이다',async()=>{assert.equal(await push(async()=>{throw Error('offline');}).status(),'unconfirmed');});

test('끄기는 서버 등록을 먼저 해제한 뒤 현재 브라우저의 구독을 해제한다', async () => {
  const calls = [];
  const p = pushWithSubscription(async (url, options) => {
    calls.push(url);
    assert.equal(options.body.endpoint, 'https://push.example/device');
  }, async () => { calls.push('browser'); return true; });
  await p.disable();
  assert.deepEqual(calls, ['/api/push/unsubscribe', 'browser']);
});

test('서버 해제에 실패하면 같은 구독을 보존해 다시 시도할 수 있다', async () => {
  const p = pushWithSubscription(async () => { throw Error('offline'); },
    async () => assert.fail('브라우저 구독이 먼저 해제됨'));
  await assert.rejects(p.disable(), /offline/);
});

function pushWithSubscription(api, unsubscribe) {
  const subscription = { endpoint: 'https://push.example/device', unsubscribe };
  const context = {
    navigator: { serviceWorker: { getRegistration: async () => ({
      pushManager: { getSubscription: async () => subscription }
    }) } },
    window: { isSecureContext: true, PushManager: {}, Notification: {} },
    App: { api }
  };
  vm.createContext(context);
  vm.runInContext(fs.readFileSync('src/main/resources/static/js/push.js', 'utf8') +
    '\nthis.push = Push;', context);
  return context.push;
}
