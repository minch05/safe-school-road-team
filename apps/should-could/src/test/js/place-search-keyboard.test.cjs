const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const source=fs.readFileSync('src/main/resources/static/js/route-new.js','utf8');
const line=source.split('\n').find(l=>l.includes("q.addEventListener('keydown'"));
test('한글 조합 확정 Enter는 검색하지 않고 다음 Enter만 검색한다',()=>{
 let handler,calls=0,prevented=0;
 vm.runInNewContext(line,{q:{addEventListener(_,fn){handler=fn;}},run(){calls++;}});
 for(const event of [{key:'Enter',isComposing:true},{key:'Enter',keyCode:229},{key:'a'}])handler({...event,preventDefault(){prevented++;}});
 assert.equal(calls,0);assert.equal(prevented,0);
 handler({key:'Enter',isComposing:false,keyCode:13,preventDefault(){prevented++;}});
 assert.equal(calls,1);assert.equal(prevented,1);
});
test('장소 선택 탭은 방향키·Home·End로 선택과 초점을 함께 옮긴다',()=>{
 const start=source.indexOf('  function movePlaceTab'),end=source.indexOf('  // 탭:',start);
 const ctx={};vm.createContext(ctx);vm.runInContext(source.slice(start,end),ctx);
 const clicked=[],focused=[];const tabs=[0,1,2].map(i=>({click(){clicked.push(i);},focus(){focused.push(i);}}));
 for(const [key,current,expected] of [['ArrowRight',2,0],['ArrowLeft',0,2],['Home',1,0],['End',0,2]]){
  let prevented=false;ctx.movePlaceTab({key,preventDefault(){prevented=true;}},tabs,tabs[current]);
  assert.equal(prevented,true);assert.equal(clicked.at(-1),expected);assert.equal(focused.at(-1),expected);
 }
 const before=clicked.length;ctx.movePlaceTab({key:'Tab',preventDefault(){throw Error('기본 Tab을 막으면 안됨');}},tabs,tabs[0]);assert.equal(clicked.length,before);
});
