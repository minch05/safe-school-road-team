(() => {
  const install = document.getElementById('install-app');
  if (window.isSecureContext && 'serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').catch(() => {});
  }
  if (!install) return;
  window.addEventListener('beforeinstallprompt', event => {
    event.preventDefault();
    install.hidden = false;
    install.onclick = async () => {
      install.hidden = true;
      await event.prompt();
    };
  });
  window.addEventListener('appinstalled', () => { install.hidden = true; });
})();
