const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync('src/main/resources/templates/route-detail.html', 'utf8');
const script = html.slice(html.indexOf("    document.getElementById('checkRoute')?.addEventListener"), html.indexOf('    // 안내문 고치기 (FR-10)'));
const response = changed => ({changed, line: [[37.59,127.08],[37.60,127.09]], points: [], distance: 1400, time: 900});
function setup(api) {
  let handler;
  const button = {disabled: false, addEventListener: (_, callback) => {handler = callback;}};
  const shown = [];
  const status = {textContent: ''}, box = {hidden: false};
  vm.runInNewContext(script, {document: {getElementById: id => ({checkRoute: button, checkStatus: status, changeBox: box})[id]}, ROUTE: {id: 12}, App: {api}, showLine: d => shown.push(d)});
  return {button, status, box, shown, click: () => handler({currentTarget: button})};
}
test('경로 달라짐은 클릭할 때 POST로 확인하고 중복 요청을 막는다', async () => {
  let complete, calls = 0;
  const ui = setup((url, options) => {
    calls++;
    assert.equal(url, '/api/routes/12/check'); assert.equal(options.method, 'POST');
    return new Promise(resolve => {complete = resolve;});
  });
  assert.equal(calls, 0);
  const pending = ui.click(); await ui.click(); assert.equal(calls, 1);
  complete(response(true)); await pending;
  assert.equal(ui.box.hidden, false); assert.equal(ui.button.disabled, false);
  assert.equal(ui.shown.length, 1); assert.equal(ui.shown[0].distance, 1400);
});
test('확인 실패를 변경 없음으로 표시하지 않고 다시 확인할 수 있다', async () => {
  const ui = setup(async () => {throw new Error('조회 실패');});
  await ui.click();
  assert.match(ui.status.textContent, /확인하지 못했어요/);
  assert.equal(ui.box.hidden, true); assert.equal(ui.button.disabled, false);
});
test('차이 없음은 현장 안전 보장으로 설명하지 않는다', async () => {
  const ui = setup(async () => response(false));
  await ui.click();
  assert.match(ui.status.textContent, /안전이나 공사 여부를 보장하지는 않아요/);
  assert.equal(ui.box.hidden, true);
});

test('판정이 빠지거나 경로선이 잘못된 응답을 차이 없음으로 표시하지 않는다', async () => {
  for (const data of [{...response(false), changed: undefined}, {...response(false), line: []}, null]) {
    const ui = setup(async () => data);
    await ui.click();
    assert.match(ui.status.textContent, /확인하지 못했어요/);
    assert.equal(ui.shown.length, 0);
    assert.equal(ui.box.hidden, true);
    assert.equal(ui.button.disabled, false);
  }
});
