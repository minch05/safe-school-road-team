# 켜 둔 앱으로 통학로 50개의 안내문을 만들어 결과를 모은다 (임시 계정, 끝나면 지움)
import json, re, sys, time, urllib.request, urllib.parse, http.cookiejar
BASE, USER, PW, ROUTES, OUT = sys.argv[1:6]
cj = http.cookiejar.CookieJar()
op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
def req(method, path, data=None, json_body=None, csrf=None):
    headers, body = {}, None
    if json_body is not None:
        body = json.dumps(json_body).encode(); headers['Content-Type'] = 'application/json'
    elif data is not None:
        body = urllib.parse.urlencode(data).encode(); headers['Content-Type'] = 'application/x-www-form-urlencoded'
    if csrf:
        # 앱의 JS 와 같이 XSRF-TOKEN 쿠키 값을 헤더로 보낸다
        cookie = next((c.value for c in cj if c.name == 'XSRF-TOKEN'), None)
        headers['X-XSRF-TOKEN'] = urllib.parse.unquote(cookie) if cookie else csrf
    r = urllib.request.Request(BASE + path, data=body, method=method, headers=headers)
    try:
        with op.open(r, timeout=180) as res: return res.status, res.read().decode()
    except urllib.error.HTTPError as e: return e.code, e.read().decode()
def token(html):
    m = re.search(r'name="_csrf" content="([^"]+)"', html) or re.search(r'name="_csrf" value="([^"]+)"', html)
    return m.group(1) if m else None
s, html = req('GET', '/login'); t = token(html)
s, html = req('POST', '/login', data={'username': USER, 'password': PW, '_csrf': t})
if 'error' in (s and html[:0] or '') or '/login?error' in html or 'name="password"' in html:
    s, html = req('GET', '/signup/form'); t = token(html)
    req('POST', '/signup', data={'username': USER, 'password': PW, 'passwordConfirm': PW, 'nickname': '시험', 'over14': 'true', '_csrf': t})
s, html = req('GET', '/routes/new'); t = token(html)
assert t, '로그인 실패'
results = []
for i, r in enumerate(json.load(open(ROUTES, encoding="utf-8"))[:int(sys.argv[6]) if len(sys.argv) > 6 else None]):
    t0 = time.time()
    s, body = req('POST', '/api/routes/draft', json_body={'start': r['start'], 'end': r['end'], 'option': 0, 'tone': r['tone']}, csrf=t)
    item = dict(gu=r['gu'], school=r['school'], tone=r['tone'], status=s, seconds=round(time.time() - t0, 1))
    if s == 200:
        d = json.loads(body)
        item.update(source=d['guideSource'], attempts=d['attempts'], points=[dict(kind=p['kindLabel'], name=p['name'], signal=p['signal'], reason=p['reason'], text=p['text']) for p in d['points']])
    else:
        item['error'] = body[:300]
    results.append(item)
    print(i + 1, r['gu'], r['school'], item.get('source'), item.get('attempts'), len(item.get('points', [])), item['seconds'], 's', flush=True)
    json.dump(results, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    time.sleep(float(__import__("os").environ.get("SLEEP", "6")))  # 무료 사용량의 분당 한도를 넘지 않게
