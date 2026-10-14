const $ = id => document.getElementById(id);
let result = null;
const available = window.isSecureContext && !!navigator.geolocation;
$('secure').textContent = location.protocol === 'https:' ? 'HTTPS 연결 정상' : window.isSecureContext ? '로컬 보안 컨텍스트 (HTTPS 시험 아님)' : 'HTTPS 연결 필요';
$('support').textContent = navigator.geolocation ? '지원함' : '지원하지 않음';
$('read').disabled = !available;
async function permission() {
  try {
    const p = await navigator.permissions.query({ name: 'geolocation' });
    $('permission').textContent = ({ granted: '허용', denied: '거부', prompt: '선택 필요' })[p.state] || p.state;
  } catch { $('permission').textContent = '버튼을 눌러 권한을 확인하세요'; }
}
permission();
$('read').addEventListener('click', () => {
  if (!available) return;
  $('read').disabled = true; $('save').disabled = true; result = null;
  $('coordinates').textContent = '—'; $('accuracy').textContent = '—';
  $('status').textContent = '권한 선택 후 위치를 확인하고 있습니다…';
  const finish = value => {
    result = { testedAt: new Date().toISOString(), https: location.protocol === 'https:', secureContext: window.isSecureContext, ...value };
    $('status').textContent = value.message; $('read').disabled = false; $('save').disabled = false; permission();
  };
  navigator.geolocation.getCurrentPosition(p => {
    $('coordinates').textContent = `${p.coords.latitude.toFixed(6)} / ${p.coords.longitude.toFixed(6)}`;
    $('accuracy').textContent = `약 ${Math.round(p.coords.accuracy)}m / ${new Date(p.timestamp).toLocaleTimeString('ko-KR')}`;
    finish({ outcome: 'success', accuracyMetres: p.coords.accuracy, message: '위치 읽기 성공' });
  }, e => {
    const messages = { 1: '위치 권한이 거부되었습니다. 사이트 위치 권한을 허용한 뒤 다시 시도하세요.', 2: '현재 위치를 확인할 수 없습니다. 위치 서비스를 확인하고 다시 시도하세요.', 3: '위치 확인 시간이 초과되었습니다. 야외나 창가에서 다시 시도하세요.' };
    finish({ outcome: 'error', errorCode: e.code, message: messages[e.code] || '위치 확인에 실패했습니다. 다시 시도하세요.' });
  }, { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 });
});
$('save').addEventListener('click', () => {
  if (!result) return;
  const record = { ...result, device: $('device').value.trim(), browser: $('browser').value.trim(), verification: '기록한 사용자가 실기기 여부를 확인해야 함' };
  const url = URL.createObjectURL(new Blob([JSON.stringify(record, null, 2)], { type: 'application/json' }));
  const a = document.createElement('a'); a.href = url; a.download = `${new Date().toLocaleDateString('sv-SE').replaceAll('-', '')}_휴대폰위치시험결과_v1.json`; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
});
