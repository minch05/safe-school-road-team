const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/js/route-new.js','utf8');
const fn=source.slice(source.indexOf('  function crimePairShorter('),source.indexOf('  function crimeDistribution('));
const candidate=(upper,missing=0,coverage=100)=>({crime:{available:true,upperGradeMeters:upper,unknownMeters:missing,coveragePercent:coverage}});
function check(candidates){const context={candidates};vm.createContext(context);vm.runInContext(fn,context);return context;}
test('전체 최솟값 배지가 없는 후보도 비교 대상보다 분명히 짧으면 두 길 차이를 설명한다',()=>{
 const a=candidate(20),b=candidate(70),c=candidate(140);const s=check([a,b,c]);
 assert.equal(s.crimeBadge(b),'');assert.equal(s.crimePairShorter(b,c),true);assert.equal(s.crimePairShorter(c,b),false);
});
test('미확인 범위나 후보 자료 부족이 있으면 두 길 차이를 과장하지 않는다',()=>{
 const a=candidate(20,60),b=candidate(70);assert.equal(check([a,b]).crimePairShorter(a,b),false);
 const c=candidate(140,0,89);assert.equal(check([a,b,c]).crimePairShorter(b,c),false);
});
