window.TeamApi = {
  async request(path, method = 'GET', data) {
    const headers = {};
    if (method !== 'GET') {
      const response = await fetch('/api/auth/csrf', { credentials: 'same-origin' });
      if (!response.ok) throw new Error('보안 토큰을 확인하지 못했어요.');
      const token = await response.json();
      headers[token.headerName] = token.token;
    }
    if (data !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(path, {
      method, headers, credentials: 'same-origin',
      body: data === undefined ? undefined : JSON.stringify(data)
    });
    if (response.status === 204) return null;
    const result = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw Object.assign(new Error(result.error || '요청을 처리하지 못했어요.'), { status: response.status });
    }
    return result;
  }
};
