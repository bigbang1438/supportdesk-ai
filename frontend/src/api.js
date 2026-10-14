export class ApiError extends Error {
  constructor(status, code, message, details = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.details = details
  }
}
export class StaleResponseError extends Error {
  constructor() { super('该响应已失效'); this.name = 'StaleResponseError' }
}

export function createApi({ base = '/api/v1', fetcher = globalThis.fetch, onUnauthorized = () => {} } = {}) {
  let csrf = null
  let principal = null
  let epoch = 0
  const reset = () => { epoch++; csrf = null; principal = null }

  async function request(path, { method = 'GET', body, form = false } = {}) {
    const started = epoch
    const mutation = method !== 'GET' && method !== 'HEAD'
    const headers = { Accept: 'application/json' }
    if (mutation && csrf) headers[csrf.headerName] = csrf.token
    if (body !== undefined) headers['Content-Type'] = form ? 'application/x-www-form-urlencoded;charset=UTF-8' : 'application/json'
    let response
    try {
      response = await fetcher(`${base}${path}`, {
        method, headers, credentials: 'same-origin', cache: 'no-store',
        body: body === undefined ? undefined : form ? new URLSearchParams(body).toString() : JSON.stringify(body)
      })
    } catch {
      if (started !== epoch) throw new StaleResponseError()
      throw new ApiError(0, 'NETWORK', '连接服务失败，请检查本地服务后重试。')
    }
    if (started !== epoch) throw new StaleResponseError()
    let payload
    try { payload = await response.json() } catch { payload = null }
    if (started !== epoch) throw new StaleResponseError()
    if (!response.ok) {
      const error = new ApiError(response.status, payload?.error?.code || 'REQUEST_FAILED', payload?.error?.message || '请求未完成，请稍后重试。', payload?.error?.details || [])
      if (response.status === 401) { reset(); onUnauthorized() }
      if (response.status === 403 && mutation) {
        try { await refreshSession() } catch (refreshError) { if (refreshError instanceof StaleResponseError) throw refreshError }
      }
      throw error
    }
    if (!payload || typeof payload !== 'object') throw new ApiError(502, 'INVALID_RESPONSE', '服务返回内容不可用，请重新加载。')
    return payload
  }

  async function refreshSession() {
    const started = epoch
    const session = await request('/auth/session')
    if (started !== epoch) throw new StaleResponseError()
    principal = session.data || null
    csrf = session.csrf || null
    return principal
  }

  async function login(username, password) {
    reset()
    await refreshSession()
    await request('/auth/login', { method: 'POST', form: true, body: { username, password } })
    return refreshSession()
  }

  async function logout() {
    await request('/auth/logout', { method: 'POST' })
    reset()
  }

  return { request, login, logout, refreshSession, reset, actor: () => principal }
}
