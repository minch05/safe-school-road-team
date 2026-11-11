// 웹 푸시 구독 (보호자 폰). 아이폰은 iOS 16.4 이상 + 홈 화면에 설치한 앱에서만 된다 (사전 시험 확인).
const Push = (() => {
  const supported = () => 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
  const isIos = () => /iPhone|iPad|iPod/.test(navigator.userAgent);
  const standalone = () => window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;

  function keyBytes(base64) {
    const pad = '='.repeat((4 - base64.length % 4) % 4);
    const raw = atob((base64 + pad).replace(/-/g, '+').replace(/_/g, '/'));
    return Uint8Array.from([...raw].map(c => c.charCodeAt(0)));
  }

  /** 'on' | 'off' | 'denied' | 'unsupported' | 'need-install' */
  async function status() {
    if (!window.isSecureContext) return 'need-https';
    if (!supported()) return isIos() && !standalone() ? 'need-install' : 'unsupported';
    if (Notification.permission === 'denied') return 'denied';
    const reg = await navigator.serviceWorker.getRegistration('/');
    const sub = reg && await reg.pushManager.getSubscription();
    if(!sub || Notification.permission!=='granted')return 'off';
    try {
      const state=await App.api('/api/push/status',{method:'POST',redirectOnAuth:false,body:{endpoint:sub.endpoint}});
      return state.registered===true?'on':state.registered===false?'unregistered':'unconfirmed';
    } catch(e) { return 'unconfirmed'; }
  }

  async function enable() {
    if (!window.isSecureContext) throw new Error('알림은 HTTPS 연결에서 사용할 수 있어요.');
    if (!supported()) throw new Error(isIos() ? '아이폰은 홈 화면에 설치한 앱에서 알림을 켤 수 있어요. (공유 → 홈 화면에 추가)' : '이 브라우저는 알림을 받을 수 없어요.');
    const key = App.meta('vapid-key');
    if (!key) throw new Error('서버에 알림 키가 설정되지 않았어요.');
    const permission = await Notification.requestPermission();
    if (permission !== 'granted') throw new Error('알림이 허용되지 않았어요. 브라우저 설정에서 알림을 허용해 주세요.');
    const reg = await navigator.serviceWorker.register('/sw.js');
    await navigator.serviceWorker.ready;
    let sub = await reg.pushManager.getSubscription();
    if (!sub) sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(key) });
    await App.api('/api/push/subscribe', { method: 'POST', body: sub.toJSON() });
  }

  return { status, enable, supported };
})();
