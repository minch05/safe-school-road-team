// 서비스 워커: 홈 화면 설치(PWA)와 웹 푸시 받기 (FR-20).
// 화면은 캐시하지 않는다 (경로·로그인 상태가 늘 최신이어야 해서).
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));

// 서버가 보낸 출발·도착·도착 지연 알림
self.addEventListener('push', e => {
  let data = { title: '안전한 통학길', body: '', url: '/' };
  try { data = { ...data, ...e.data.json() }; } catch (err) { if (e.data) data.body = e.data.text(); }
  e.waitUntil(self.registration.showNotification(data.title, {
    body: data.body,
    icon: '/icons/icon-192.png',
    badge: '/icons/icon-192.png',
    tag: typeof data.tag==='string' && /^pending-\d+$/.test(data.tag) ? data.tag : undefined,
    renotify: false,
    data: { url: data.url },
  }));
});

// 알림 목적지는 같은 서비스 안에서만 연다.
function notificationUrl(value) {
  try {
    const url = new URL(typeof value === 'string' ? value : '/', self.location.origin);
    if(url.origin !== self.location.origin || !['http:','https:'].includes(url.protocol) || url.username || url.password)return self.location.origin + '/';
    return url.href;
  } catch(e) { return self.location.origin + '/'; }
}

// 기존 창 이동을 기다린 뒤 포커스. 실패하면 다른 창 또는 새 창으로 이어 간다.
self.addEventListener('notificationclick', e => {
  e.notification.close();
  const url = notificationUrl(e.notification.data?.url);
  e.waitUntil((async () => {
    let list=[];
    try { list=await self.clients.matchAll({type:'window',includeUncontrolled:true}); } catch(e) {}
    for(const c of list){
      try {
        if(new URL(c.url).origin!==self.location.origin || typeof c.focus!=='function')continue;
        const moved = c.url===url ? c : await c.navigate(url);
        if(moved){await moved.focus();return;}
      } catch(e) {} // 닫힌 창 또는 이동 실패는 다음 창으로 이어 간다.
    }
    await self.clients.openWindow(url);
  })());
});
