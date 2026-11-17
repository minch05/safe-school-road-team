(() => {
  const $ = id => document.getElementById(id);
  const request = TeamApi.request;
  const message = text => { $('family-status').textContent = text; };
  let preview;
  let invite;
  let previewVersion = 0;

  function element(tag, text) {
    const node = document.createElement(tag);
    node.textContent = text;
    return node;
  }

  function run(action, control) {
    return async event => {
      event?.preventDefault();
      if (control?.disabled) return;
      if (control) control.disabled = true;
      try { await action(); }
      catch (error) {
        if (error.status === 401) location.assign('/login.html');
        else message(error.message);
      } finally {
        if (control) control.disabled = false;
      }
    };
  }

  function button(text, action) {
    const control = element('button', text);
    control.type = 'button';
    control.onclick = run(action, control);
    return control;
  }

  function link(text, path) {
    const anchor = element('a', text);
    anchor.href = path;
    return anchor;
  }

  async function unlink(person) {
    if (!confirm('이 가족 계정과의 연결을 해제할까요?')) return;
    await request('/api/family/links/' + person.id, 'DELETE');
    await load();
    message('가족 연결을 해제했어요.');
  }

  async function showChild(info, child) {
    const { routes, trips } = await request('/api/family/children/' + child.id + '/routes');
    const list = element('ul', '');
    for (const route of routes) {
      const item = element('li', '');
      item.append(link(route.name, '/guide.html?id=' + route.id + '&owner=' + child.id));
      list.append(item);
    }
    const history = element('ul', '');
    for (const trip of trips) {
      const when = new Date(trip.arrivedAt || trip.startedAt);
      history.append(element('li', (trip.routeName || '삭제된 경로') + ' · ' +
        (trip.arrivedAt ? '도착' : '출발') + ' · ' + when.toLocaleString('ko-KR')));
    }
    info.replaceChildren(link('자녀 경로 등록', '/?owner=' + child.id),
      element('h4', '저장한 경로'), routes.length ? list : element('p', '저장한 경로가 없어요.'),
      element('h4', '최근 통학 기록'), trips.length ? history : element('p', '아직 통학 기록이 없어요.'));
  }

  function childCard(child) {
    const card = element('article', '');
    const info = element('div', '');
    const actions = element('div', '');
    actions.className = 'family-actions';
    actions.append(button('경로와 기록 보기', () => showChild(info, child)));
    if (child.type === 'INVITED') {
      actions.append(button('연결 해제', () => unlink(child)));
    } else {
      const password = document.createElement('input');
      password.type = 'password';
      password.autocomplete = 'new-password';
      const label = element('label', '새 비밀번호');
      label.append(password);
      actions.append(button('비밀번호 변경', () => {
        info.replaceChildren(label, button('새 비밀번호 저장', async () => {
          await request('/api/family/children/' + child.id + '/password', 'POST', { password: password.value });
          password.value = '';
          info.replaceChildren();
          message('비밀번호를 변경했어요. 아이 계정은 다시 로그인해야 해요.');
        }));
        password.focus();
      }));
      actions.append(button('아이 계정 삭제', async () => {
        const nickname = prompt('경로와 기록도 삭제됩니다. 확인하려면 아이 별명을 입력해 주세요.');
        if (nickname === null) return;
        await request('/api/family/children/' + child.id, 'DELETE', { nickname });
        await load();
        message('아이 계정을 삭제했어요.');
      }));
    }
    card.append(element('h3', child.nickname), actions, info);
    return card;
  }

  function clearPreview() {
    ++previewVersion;
    preview = null;
    $('preview-result').textContent = '';
    $('redeem-invite').hidden = true;
  }

  async function load() {
    const family = await request('/api/family');
    $('children-panel').hidden = !family.canBeGuardian;
    $('accept-panel').hidden = !family.canBeGuardian || family.children.length > 0;
    $('my-guardian').hidden = !family.guardian;
    $('guardian-name').textContent = family.guardian?.nickname || '';
    $('unlink-guardian').hidden = family.guardian?.type !== 'INVITED';
    $('unlink-guardian').onclick = run(() => unlink(family.guardian), $('unlink-guardian'));
    $('children').replaceChildren(...(family.children.length
      ? family.children.map(childCard) : [element('p', '연결된 자녀가 없어요.')]));
    clearPreview();
    message('');
  }

  const create = $('new-child');
  create.onsubmit = run(async () => {
    await request('/api/family/children', 'POST', Object.fromEntries(new FormData(create)));
    create.reset();
    await load();
    message('아이 계정을 만들었어요.');
  }, create.querySelector('button'));

  $('issue-invite').onclick = run(async () => {
    const response = await request('/api/invite/issue', 'POST');
    invite = response.code;
    $('issued-code').textContent = invite + ' · ' + response.minutes + '분 동안 유효';
    $('copy-invite').hidden = !navigator.clipboard;
  }, $('issue-invite'));
  $('copy-invite').onclick = run(async () => {
    await navigator.clipboard.writeText(invite);
    message('초대 코드를 복사했어요.');
  }, $('copy-invite'));

  const form = $('preview-invite');
  form.elements.code.oninput = clearPreview;
  form.onsubmit = run(async () => {
    clearPreview();
    const version = previewVersion;
    const code = form.elements.code.value.trim();
    const result = await request('/api/invite/preview', 'POST', { code });
    if (version !== previewVersion) return;
    preview = { code, previewToken: result.previewToken };
    $('preview-result').textContent = '보호자: ' + result.guardian;
    $('redeem-invite').hidden = false;
  }, form.querySelector('button'));
  $('redeem-invite').onclick = run(async () => {
    if (!preview) return;
    await request('/api/invite/redeem', 'POST', preview);
    form.reset();
    await load();
    message('보호자 계정과 연결했어요.');
  }, $('redeem-invite'));

  run(load)();
})();
