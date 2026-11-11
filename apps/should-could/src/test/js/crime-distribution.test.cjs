const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/js/route-new.js','utf8');
const fn=source.slice(source.indexOf('  function crimeDistribution('),source.indexOf('  function renderSelection('));
const ctx={};vm.createContext(ctx);vm.runInContext(fn+'this.render=crimeDistribution;',ctx);
test('등급 열은 제공기관 1~10 순서로 표시하며 길이는 앱 추정으로 설명한다',()=>{
 const html=ctx.render({gradeMeters:[0,5,10,0,0,0,20,0,0,1]});assert.equal((html.match(/scope="row"/g)||[]).length,10);assert.ok(html.indexOf('1등급')<html.indexOf('10등급'));assert.match(html,/약 20m/);assert.match(html,/앱의 근사 계산/);assert.match(html,/빈 영역·판독 실패/);
});
test('누락되거나 잘못된 분포는 가짜 0m 표를 만들지 않는다',()=>{
 for(const d of [null,{}, {gradeMeters:[]},{gradeMeters:Array(10).fill(-1)},{gradeMeters:Array(10).fill(NaN)},{gradeMeters:Array(10).fill('0')}])assert.equal(ctx.render(d),'');
});
