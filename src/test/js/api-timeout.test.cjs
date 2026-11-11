const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const source=fs.readFileSync('src/main/resources/static/js/app.js','utf8');
function api(fetch){
 const context={fetch,AbortController,setTimeout,clearTimeout,navigator:{},document:{querySelector:()=>null,cookie:''}};
 vm.createContext(context);vm.runInContext(source+'\nthis.api=App.api;',context);return context.api;
}
test('응답이 멈추면 요청을 중단하고 사용자가 이해할 수 있는 오류를 준다',async()=>{
 const call=api((url,{signal})=>new Promise((resolve,reject)=>signal.addEventListener('abort',()=>reject(new Error('aborted')))));
 await assert.rejects(call('/api/trips/start',{method:'POST',body:{routeId:1},timeoutMs:5}),/시간이 초과/);
});
test('헤더 이후 응답 본문이 멈춰도 시간 제한이 적용된다',async()=>{
 const call=api(async(url,{signal})=>({json:()=>new Promise((resolve,reject)=>signal.addEventListener('abort',()=>reject(new Error('aborted'))))}));
 await assert.rejects(call('/api/trips/start',{timeoutMs:5}),/시간이 초과/);
});
test('정상 응답과 서버의 의미 있는 오류는 유지한다',async()=>{
 const call=api(async()=>({ok:true,json:async()=>({tripId:1})}));assert.equal((await call('/api/trips/start')).tripId,1);
 const failure=api(async()=>({ok:false,json:async()=>({error:'출발 기록 없음'})}));await assert.rejects(failure('/api/trips/start'),/출발 기록 없음/);
});
test('성공 상태라도 손상된 JSON은 성공으로 처리하지 않는다',async()=>{
 const call=api(async()=>({ok:true,json:async()=>{throw new Error('html')}}));await assert.rejects(call('/api/routes'),/응답을 읽지/);
});
test('로그인 만료를 입력 화면에서 처리할 수 있도록 AUTH 오류를 준다',async()=>{
 const call=api(async()=>({status:401,ok:false,json:async()=>({})}));await assert.rejects(call('/api/routes',{redirectOnAuth:false}),e=>e.code==='AUTH'&&e.status===401);
});
test('초안 만료와 접근 거부를 구분해 화면이 알맞게 복구하게 한다',async()=>{
 const expired=api(async()=>({status:409,ok:false,json:async()=>({code:'DRAFT_UNAVAILABLE',error:'안내문을 다시 만들어 주세요'})}));
 await assert.rejects(expired('/api/routes'),e=>e.code==='DRAFT_UNAVAILABLE'&&e.status===409);
 const forbidden=api(async()=>({status:403,ok:false,json:async()=>({error:'볼 수 없는 경로예요'})}));
 await assert.rejects(forbidden('/api/routes'),e=>e.code===undefined&&e.status===403);
});

test('사용자 취소는 시간 초과와 구분하고 입력 유지 메시지를 준다',async()=>{
 const controller=new AbortController();
 const call=api((url,{signal})=>new Promise((resolve,reject)=>{signal.addEventListener('abort',()=>reject(new Error('aborted')));controller.abort();}));
 await assert.rejects(call('/api/routes/candidates',{signal:controller.signal}),e=>e.code==='CANCELLED'&&e.message.includes('유지'));
});
test('이미 중단한 신호도 취소 오류로 처리한다',async()=>{
 const controller=new AbortController();controller.abort();
 const call=api(async(url,{signal})=>{assert.equal(signal.aborted,true);throw new Error('aborted');});
 await assert.rejects(call('/api/routes/candidates',{signal:controller.signal}),e=>e.code==='CANCELLED');
});
test('본문 대기 중에도 사용자가 조회를 중단할 수 있다',async()=>{
 const controller=new AbortController();
 const call=api(async(url,{signal})=>({json:()=>new Promise((resolve,reject)=>{signal.addEventListener('abort',()=>reject(new Error('aborted')));controller.abort();})}));
 await assert.rejects(call('/api/routes/candidates',{signal:controller.signal}),e=>e.code==='CANCELLED');
});

test('CSRF 검사 거부는 쿠키 갱신 후 같은 요청을 한 번만 재전송한다',async()=>{
 const requests=[];
 const call=api(async(url,options)=>{requests.push([url,options.body]);
  if(requests.length===1)return {status:403,ok:false,json:async()=>({code:'CSRF'})};
  return {status:200,ok:true,json:async()=>url==='/api/session'?{ready:true}:{id:12}};
 });
 assert.equal((await call('/api/routes',{method:'POST',body:{requestId:'same'}})).id,12);
 assert.deepEqual(requests,[['/api/routes','{"requestId":"same"}'],['/api/session',undefined],['/api/routes','{"requestId":"same"}']]);
});
test('CSRF 갱신 후에도 거부되면 반복 재시도하지 않는다',async()=>{
 let count=0;
 const call=api(async(url)=>{count++;return url==='/api/session'?{ok:true,json:async()=>({ready:true})}:{status:403,ok:false,json:async()=>({code:'CSRF',error:'보안 확인 실패'})};});
 await assert.rejects(call('/api/routes',{method:'POST'}),e=>e.code==='CSRF');assert.equal(count,3);
});
test('권한 거부와 저장 응답 유실은 자동으로 재전송하지 않는다',async()=>{
 for(const failure of ['forbidden','network']){
  let count=0;const call=api(async()=>{count++;if(failure==='network')throw new Error('network');return {status:403,ok:false,json:async()=>({code:'FORBIDDEN'})};});
  await assert.rejects(call('/api/routes',{method:'POST'}));assert.equal(count,1);
 }
});
