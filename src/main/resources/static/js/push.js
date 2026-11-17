(() => {
  const $ = id => document.getElementById(id);
  const status = $('push-status');
  const on = $('push-enable'), off = $('push-disable'), test = $('push-test');
  const supported = () => 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
  const ios = /iPhone|iPad|iPod/.test(navigator.userAgent) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

  async function subscription() {
    const registration = await navigator.serviceWorker.getRegistration('/');
    return registration?.pushManager.getSubscription();
  }

  async function refresh() {
    on.hidden = off.hidden = test.hidden = true;
    if (!window.isSecureContext) {
      status.textContent = '알림은 HTTPS 연결에서 사용할 수 있어요.';
      return;
    }
    if (!supported()) {
      status.textContent = ios ? 'Safari에서 홈 화면에 설치한 앱을 열어 알림을 켜세요.' : '이 브라우저에서는 웹 알림을 사용할 수 없어요.';
      return;
    }
    const current = await subscription();
    const registered = current && (await TeamApi.request('/api/push/status', 'POST', { endpoint: current.endpoint })).registered;
    off.hidden = !current;
    if (Notification.permission === 'denied') {
      status.textContent = '알림 권한이 차단됐어요. 브라우저 또는 기기의 알림 설정에서 허용해 주세요.';
      return;
    }
    if (registered && Notification.permission === 'granted') {
      status.textContent = '이 기기의 알림이 켜져 있어요.';
      test.hidden = false;
    } else {
      on.hidden = false;
      status.textContent = '이 기기의 알림이 꺼져 있어요.';
    }
  }

  function handle(button, action) {
    button.onclick = async () => {
      for (const control of [on, off, test]) control.disabled = true;
      try {
        const result = await action();
        await refresh();
        if (result) status.textContent = result;
      } catch (error) {
        await refresh().catch(() => {});
        status.textContent = error.message;
      } finally {
        for (const control of [on, off, test]) control.disabled = false;
      }
    };
  }

  handle(on, async () => {
    const permission = await Notification.requestPermission();
    if (permission !== 'granted') throw new Error('알림을 켜려면 알림 권한을 허용해 주세요.');
    const { publicKey } = await TeamApi.request('/api/push/config');
    if (!publicKey) throw new Error('서버 알림 키가 준비되지 않았어요.');
    const registration = await navigator.serviceWorker.register('/sw.js');
    await navigator.serviceWorker.ready;
    const bytes = Uint8Array.from(atob(publicKey.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));
    let current = await registration.pushManager.getSubscription();
    if (current) {
      const previousKey = new Uint8Array(current.options.applicationServerKey || []);
      if (previousKey.length !== bytes.length || previousKey.some((byte, index) => byte !== bytes[index])) {
        if (!await current.unsubscribe()) throw new Error('이전 구독을 지우지 못했어요. 잠시 뒤 다시 시도해 주세요.');
        current = null;
      }
    }
    current ||= await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: bytes });
    await TeamApi.request('/api/push/subscribe', 'POST', current.toJSON());
  });

  handle(off, async () => {
    const current = await subscription();
    if (!current) return;
    await TeamApi.request('/api/push/unsubscribe', 'POST', { endpoint: current.endpoint });
    if (!await current.unsubscribe() && await subscription()) throw new Error('브라우저 구독 해제를 다시 시도해 주세요.');
  });

  handle(test, async () => {
    const current = await subscription();
    if (!current) throw new Error('이 기기의 알림을 먼저 켜 주세요.');
    const result = await TeamApi.request('/api/push/test', 'POST', { endpoint: current.endpoint });
    return result.sent > 0 ? '푸시 서비스가 시험 알림을 접수했어요. 이 기기의 알림을 확인하세요.' : '시험 알림을 접수하지 못했어요.';
  });

  refresh().catch(error => { status.textContent = error.message; });
})();
