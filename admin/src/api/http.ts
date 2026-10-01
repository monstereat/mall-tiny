export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
}

export async function request<T>(
  url: string,
  options: RequestInit = {},
  auth = true
): Promise<T> {
  const headers = new Headers(options.headers);
  if (!headers.has('Content-Type') && options.body && !(options.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }

  if (auth) {
    const token = localStorage.getItem('monitor-token');
    const tokenHead = localStorage.getItem('monitor-token-head') || 'Bearer ';
    if (token) headers.set('Authorization', tokenHead + token);
  }

  const response = await fetch(url, { ...options, headers });
  if (response.status === 401) {
    localStorage.removeItem('monitor-token');
    location.href = '/login';
    throw new Error('登录已过期');
  }
  const result = await response.json() as ApiResult<T>;
  if (!response.ok || result.code !== 200) {
    throw new Error(result.message || `HTTP ${response.status}`);
  }
  return result.data;
}
