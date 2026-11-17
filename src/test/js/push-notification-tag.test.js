const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const source=fs.readFileSync('src/main/resources/static/sw.js','utf8');
test('같은 대기 알림의 재전송은 같은 표시 태그를 사용하고 다른 대기는 구분한다',async()=>{
 const handlers={},shown=[];const self={addEventListener(n,f){handlers[n]=f;},registration:{showNotification(title,options){shown.push(options);return Promise.resolve();}}};
 vm.runInNewContext(source,{self,URL});
 for(const tag of ['pending-58','pending-58','pending-59','invalid']){let done;handlers.push({data:{json(){return {title:'안내',tag};}},waitUntil(p){done=p;}});await done;}
 assert.equal(shown[0].tag,shown[1].tag);assert.notEqual(shown[0].tag,shown[2].tag);assert.equal(shown[3].tag,undefined);assert.ok(shown.every(x=>x.renotify===false));
});
