const test=require('node:test'),assert=require('node:assert/strict');
const guard=require('../../main/resources/static/js/navigation-guard.js');
const end=[37.6,127.09],line=[[37.599,127.09],end];
const fix=(at,accuracy=10,point=end)=>({timestamp:at,coords:{latitude:point[0],longitude:point[1],accuracy}});
test('한 번 튄 GPS와 같은 시각의 중복 응답으로 도착하지 않는다',()=>{
 const g=guard.create();assert.equal(g.inspect(fix(10000),end,line,10000).arrived,false);
 assert.equal(g.inspect(fix(10000),end,line,15000).arrived,false);
 assert.equal(g.inspect(fix(14000),end,line,14000).arrived,false);
 assert.equal(g.inspect(fix(15000),end,line,15000).arrived,true);
});
test('낮은 정확도·오래된 위치·잘못된 좌표는 자동 판단하지 않는다',()=>{
 const g=guard.create();g.inspect(fix(10000),end,line,10000);
 assert.equal(g.inspect(fix(15000,80),end,line,15000).usable,false);
 assert.equal(g.inspect(fix(16000),end,line,16000).arrived,false);
 assert.equal(g.inspect(fix(1000),end,line,20000).usable,false);
 assert.equal(guard.fresh(fix(20000,10,[NaN,127]),20000),false);
});
test('목적지에서 벗어나거나 화면 복귀로 초기화하면 연속 확인을 다시 시작한다',()=>{
 const g=guard.create();g.inspect(fix(10000),end,line,10000);g.inspect(fix(13000,10,[37.598,127.09]),end,line,13000);
 assert.equal(g.inspect(fix(16000),end,line,16000).arrived,false);g.reset();
 assert.equal(g.inspect(fix(22000),end,line,22000).arrived,false);
});
test('경로 이탈은 일시적인 위치 오차가 아니라 연속 확인으로 안내한다',()=>{
 const g=guard.create(),away=[37.6,127.093];
 assert.equal(g.inspect(fix(10000,10,away),end,line,10000).offRoute,false);
 assert.equal(g.inspect(fix(20000,10,away),end,line,20000).offRoute,true);
 assert.equal(g.inspect(fix(21000),end,line,21000).offRoute,false);
 assert.equal(g.inspect(fix(30000,10,away),end,line,30000).offRoute,false);
});
test('경로선의 선분 중간과 선분 끝까지의 거리를 계산한다',()=>{
 assert.ok(guard.lineDistance([37.5995,127.09],line)<1);
 assert.ok(guard.lineDistance([37.5995,127.091],line)>70);
 assert.equal(guard.lineDistance(end,null),Infinity);
 assert.ok(guard.lineDistance([37.601,127.09],line)>100);
});

test('중복·역순 위치 응답으로 안내 지점을 추가 진행하지 않는다',()=>{
 const g=guard.create();assert.equal(g.inspect(fix(10000),end,line,10000).usable,true);
 assert.equal(g.inspect(fix(10000),end,line,12000).usable,false);
 assert.equal(g.inspect(fix(9000),end,line,12000).usable,false);
 assert.equal(g.inspect(fix(15000),end,line,15000).arrived,true);
});
