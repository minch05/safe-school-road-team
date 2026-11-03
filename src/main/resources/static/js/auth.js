const $ = selector => document.querySelector(selector);
$('#age-choice')?.addEventListener('change', event => {
  const adult = event.target.value === 'adult';
  $('#signup-form').hidden = !adult;
  $('#underage-help').hidden = adult;
  $('#message').textContent = '';
});
async function csrf() {
  const response = await fetch('/api/auth/csrf', {credentials:'same-origin'});
  if (!response.ok) throw new Error('보안 토큰을 받지 못했어요.');
  return response.json();
}
$('#signup-form')?.addEventListener('submit', async event => {
  event.preventDefault();
  const data = {...Object.fromEntries(new FormData(event.currentTarget)), age14OrOlder: true};
  try {
    const token = await csrf();
    const response = await fetch('/api/auth/signup', {method:'POST', credentials:'same-origin',
      headers:{'Content-Type':'application/json',[token.headerName]:token.token}, body:JSON.stringify(data)});
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || '가입에 실패했어요.');
    location.assign('/login.html');
  } catch (error) { $('#message').textContent = error.message; }
});
$('#login-form')?.addEventListener('submit', async event => {
  event.preventDefault();
  const body = new URLSearchParams(new FormData(event.currentTarget));
  try {
    const token = await csrf();
    const response = await fetch('/api/auth/login', {method:'POST', credentials:'same-origin',
      headers:{'Content-Type':'application/x-www-form-urlencoded',[token.headerName]:token.token}, body});
    if (!response.ok) throw new Error('아이디 또는 비밀번호를 확인해 주세요.');
    location.assign('/');
  } catch (error) { $('#message').textContent = error.message; }
});
