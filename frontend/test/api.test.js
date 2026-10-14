import test from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import { createApi, ApiError, StaleResponseError } from '../src/api.js'

const actor = { userId: 1, tenantId: 1, username: 'demo_agent', displayName: '演示客服', role: 'agent', workspaceName: '青禾数码（虚拟企业）' }
async function service(t, handler) {
  const server = http.createServer(async (req, res) => {
    let body = ''
    for await (const chunk of req) body += chunk
    await handler(req, res, body)
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  t.after(() => new Promise(resolve => server.close(resolve)))
  return `http://127.0.0.1:${server.address().port}`
}
function json(res, data, status = 200) {
  res.writeHead(status, { 'content-type': 'application/json' })
  res.end(JSON.stringify(data))
}

test('login consumes the server principal and rotates CSRF before the next write', async t => {
  let loggedIn = false
  const base = await service(t, (req, res, body) => {
    if (req.url === '/auth/session') return json(res, { data: loggedIn ? actor : null, csrf: { headerName: 'X-CSRF-TOKEN', token: loggedIn ? 'rotated' : 'initial' } })
    if (req.url === '/auth/login') {
      if (req.headers['x-csrf-token'] !== 'initial') return json(res, { error: { code: 'CSRF', message: 'bad csrf' } }, 403)
      const form = new URLSearchParams(body)
      loggedIn = form.get('username') === 'demo_agent' && form.get('password') === 'test-only-input'
      return json(res, { data: null })
    }
    json(res, { data: { csrf: req.headers['x-csrf-token'], body: JSON.parse(body) } })
  })
  const api = createApi({ base })
  assert.deepEqual(await api.login('demo_agent', 'test-only-input'), actor)
  const result = await api.request('/tickets/7/messages', { method: 'POST', body: { body: '检查订单', version: 3 } })
  assert.deepEqual(result.data, { csrf: 'rotated', body: { body: '检查订单', version: 3 } })
})
test('401 invalidates in-memory session and informs the UI', async t => {
  let unauthorized = 0
  const base = await service(t, (req, res) => req.url === '/auth/session'
    ? json(res, { data: actor, csrf: { headerName: 'X-CSRF-TOKEN', token: 'initial' } })
    : json(res, { error: { code: 'AUTH_REQUIRED', message: '请重新登录' } }, 401))
  const api = createApi({ base, onUnauthorized: () => unauthorized++ })
  await api.refreshSession()
  await assert.rejects(api.request('/tickets'), error => error instanceof ApiError && error.status === 401)
  assert.equal(api.actor(), null)
  assert.equal(unauthorized, 1)
})
test('CSRF rejection refreshes the token but never replays a mutation', async t => {
  let writes = 0
  let sessions = 0
  const base = await service(t, (req, res) => {
    if (req.url === '/auth/session') return json(res, { data: actor, csrf: { headerName: 'X-CSRF-TOKEN', token: `token-${++sessions}` } })
    writes++
    json(res, { error: { code: 'CSRF', message: '请求验证已更新，请重新操作' } }, 403)
  })
  const api = createApi({ base })
  await api.refreshSession()
  await assert.rejects(api.request('/tickets/7/messages', { method: 'POST', body: { body: '检查', version: 3 } }), error => error.status === 403)
  assert.equal(writes, 1)
  assert.equal(sessions, 2)
})
test('late session response cannot restore an actor after logout invalidation', async t => {
  let release
  let arrived
  const waiting = new Promise(resolve => { arrived = resolve })
  const base = await service(t, async (req, res) => {
    arrived()
    await new Promise(resolve => { release = resolve })
    json(res, { data: actor, csrf: { headerName: 'X-CSRF-TOKEN', token: 'late' } })
  })
  const api = createApi({ base })
  const refresh = api.refreshSession()
  await waiting
  api.reset()
  release()
  await assert.rejects(refresh, StaleResponseError)
  assert.equal(api.actor(), null)
})
test('late 401 from the previous account cannot clear a newly refreshed actor', async t => {
  let release
  let arrived
  const waiting = new Promise(resolve => { arrived = resolve })
  const base = await service(t, async (req, res) => {
    if (req.url === '/auth/session') return json(res, { data: actor, csrf: { headerName: 'X-CSRF-TOKEN', token: 'fresh' } })
    arrived()
    await new Promise(resolve => { release = resolve })
    json(res, { error: { code: 'AUTH_REQUIRED', message: '旧会话已过期' } }, 401)
  })
  let unauthorized = 0
  const api = createApi({ base, onUnauthorized: () => unauthorized++ })
  await api.refreshSession()
  const old = api.request('/tickets')
  await waiting
  api.reset()
  await api.refreshSession()
  release()
  await assert.rejects(old, StaleResponseError)
  assert.deepEqual(api.actor(), actor)
  assert.equal(unauthorized, 0)
})
test('409 is surfaced for explicit reload and is never retried', async t => {
  let writes = 0
  const base = await service(t, (req, res) => {
    if (req.url === '/auth/session') return json(res, { data: actor, csrf: { headerName: 'X-CSRF-TOKEN', token: 'token' } })
    writes++
    json(res, { error: { code: 'STALE_VERSION', message: '工单已更新，请重新加载' } }, 409)
  })
  const api = createApi({ base })
  await api.refreshSession()
  await assert.rejects(api.request('/tickets/7/status', { method: 'PATCH', body: { status: 'resolved', version: 3 } }), error => error instanceof ApiError && error.status === 409)
  assert.equal(writes, 1)
})

test('successful server logout clears the actor even if the next session refresh would fail', async t => {
  let sessions = 0
  let loggedOut = false
  const base = await service(t, (req, res) => {
    if (req.url === '/auth/logout') {
      loggedOut = true
      return json(res, { data: { authenticated: false } })
    }
    sessions++
    if (loggedOut) { res.destroy(); return }
    json(res, { data: actor, csrf: { headerName: 'X-CSRF-TOKEN', token: 'before-logout' } })
  })
  const api = createApi({ base })
  await api.refreshSession()
  const outcome = await api.logout().then(() => 'logged-out', failure => failure.code)
  assert.equal(outcome, 'logged-out')
  assert.equal(api.actor(), null)
  assert.equal(sessions, 1)
})

test('a failed server logout keeps the existing session available for explicit retry', async t => {
  const base = await service(t, (req, res) => req.url === '/auth/session'
    ? json(res, { data: actor, csrf: { headerName: 'X-CSRF-TOKEN', token: 'existing' } })
    : json(res, { error: { code: 'TEMPORARY_FAILURE', message: '退出未完成，请重试' } }, 503))
  const api = createApi({ base })
  await api.refreshSession()
  await assert.rejects(api.logout(), failure => failure.status === 503)
  assert.deepEqual(api.actor(), actor)
})
