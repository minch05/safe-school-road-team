import http from 'node:http';
import { readFile } from 'node:fs/promises';

const pages = { '/': ['index.html', 'text/html; charset=utf-8'], '/app.js': ['app.js', 'text/javascript; charset=utf-8'] };
const server = http.createServer(async (req, res) => {
  const file = pages[req.url];
  const headers = {
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
    'Permissions-Policy': 'geolocation=(self)',
    'Content-Security-Policy': "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
  };
  if (!file || !['GET', 'HEAD'].includes(req.method)) { res.writeHead(404, headers); res.end(); return; }
  try {
    const body = await readFile(new URL(file[0], import.meta.url));
    res.writeHead(200, { ...headers, 'Content-Type': file[1] });
    res.end(req.method === 'HEAD' ? undefined : body);
  } catch { res.writeHead(500, headers); res.end('시험 페이지를 읽을 수 없습니다.'); }
});
server.listen(8097, '127.0.0.1', () => console.log('위치 시험 서버: http://127.0.0.1:8097'));
