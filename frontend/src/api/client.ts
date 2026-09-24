const TOKEN_KEY = 'report_hub_token'
const USER_KEY = 'report_hub_user'

export type Role = 'admin' | 'member'

export type User = {
  id: string
  username: string
  displayName?: string
  role: Role
  token?: string
}

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function getUser(): User | null {
  const raw = localStorage.getItem(USER_KEY)
  return raw ? JSON.parse(raw) : null
}

export function setSession(user: User) {
  if (user.token) localStorage.setItem(TOKEN_KEY, user.token)
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

export class ApiError extends Error {
  status: number
  constructor(message: string, status = 400) {
    super(message)
    this.status = status
  }
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string> | undefined),
  }
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`

  const res = await fetch(path, { ...options, headers })
  const text = await res.text()
  let payload: any = null
  try {
    payload = text ? JSON.parse(text) : null
  } catch {
    throw new ApiError(text || res.statusText, res.status)
  }
  if (!res.ok || payload?.ok === false) {
    throw new ApiError(payload?.error || res.statusText || '请求失败', res.status)
  }
  return payload.data as T
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }),
  put: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) }),
  del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}
