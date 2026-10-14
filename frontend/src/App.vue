<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { createApi, ApiError, StaleResponseError } from './api.js'
import { statusActions, statusLabels, orderLabels, actionLabels, validateTicket, approvalIssue, createResponseScope, dateLabel, moneyLabel } from './domain.js'
import { resetWorkspaceCounts } from './workspace-state.js'

const accounts = [
  { username: 'demo_customer', label: '演示客户', workspace: '青禾数码' },
  { username: 'demo_agent', label: '演示客服', workspace: '青禾数码' },
  { username: 'demo_customer2', label: '另一位客户', workspace: '青禾数码' },
  { username: 'demo_agent2', label: '协作客服', workspace: '青禾数码' },
  { username: 'demo_other', label: '外部客户', workspace: '星桥文具' },
  { username: 'demo_other_agent', label: '外部客服', workspace: '星桥文具' }
]
const actor = ref(null)
const authLoading = ref(true)
const loginBusy = ref(false)
const loginForm = reactive({ username: 'demo_customer', password: '' })
const notice = ref('')
const error = ref('')
const nav = ref('tickets')
const scope = createResponseScope()
let identity = 0
let selection = scope.begin('selection')
const api = createApi({ onUnauthorized: () => { clearWorkspace(); error.value = '登录已过期，请重新登录。' } })
const isStaff = computed(() => actor.value?.role === 'agent')
const ticketList = ref([])
const ticketMeta = reactive({ page: 0, size: 20, total: 0, totalPages: 0 })
const filters = reactive({ q: '', status: '' })
const summary = ref({ total: 0, open: 0, processing: 0, resolved: 0 })
const inboxLoading = ref(false)
const selectedId = ref(null)
const ticket = ref(null)
const messages = ref([])
const events = ref([])
const boundOrder = ref(null)
const detailLoading = ref(false)
const busyTicket = ref('')
const reply = ref('')
const conflict = ref(false)
const orders = ref([])
const ordersMeta = reactive({ page: 0, size: 20, total: 0, totalPages: 0 })
const ordersLoading = ref(false)
const orderQuery = ref('')
const createOpen = ref(false)
const ticketDraft = reactive({ orderId: '', subject: '', body: '' })
const createBusy = ref(false)
const createError = ref('')
const policies = ref([])
const policiesLoading = ref(false)
const policyEditor = ref(null)
const policyBusy = ref(false)
const policyError = ref('')
const capabilities = ref({ modelConfigured: false })
const assistantMode = ref('demo')
const question = ref('这张工单涉及哪些售后流程？')
const runs = ref([])
const selectedRunId = ref(null)
const assistantBusy = ref(false)
const assistantError = ref('')
const publishConfirm = ref(null)
const dialogVisible = computed(() => Boolean(createOpen.value || policyEditor.value || publishConfirm.value))
let previousFocus = null
watch(dialogVisible, async visible => {
  if (visible) {
    previousFocus = document.activeElement
    await nextTick()
    document.querySelector('.dialog input:not([disabled]), .dialog textarea:not([disabled]), .dialog select:not([disabled]), .dialog button:not([disabled])')?.focus()
  } else previousFocus?.focus()
})
function trapDialogFocus(event) {
  if (!dialogVisible.value || event.key !== 'Tab') return
  const controls = [...document.querySelectorAll('.dialog button:not([disabled]), .dialog input:not([disabled]), .dialog select:not([disabled]), .dialog textarea:not([disabled]), .dialog a[href]')]
  const first = controls[0]; const last = controls.at(-1)
  if (!first) { event.preventDefault(); return }
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
const now = ref(Date.now())
const clockTimer = setInterval(() => { now.value = Date.now() }, 1000)
onUnmounted(() => clearInterval(clockTimer))
const selectedRun = computed(() => runs.value.find(run => run.id === selectedRunId.value) || null)
const draftIssue = computed(() => conflict.value ? '工单已有新的处理记录，请重新加载后再核对草稿。' : approvalIssue(selectedRun.value, ticket.value, now.value, Boolean(busyTicket.value) || assistantBusy.value))
const availableActions = computed(() => statusActions(actor.value, ticket.value))
const heading = computed(() => nav.value === 'orders' ? '我的订单' : nav.value === 'policies' ? '售后资料' : isStaff.value ? '工单收件箱' : '我的售后工单')
const accountChoice = computed(() => accounts.find(account => account.username === loginForm.username))

function clearWorkspace() {
  identity++
  scope.invalidate()
  resetWorkspaceCounts({ summary: summary.value, ticketMeta, ordersMeta })
  orderQuery.value = ''
  actor.value = null
  ticketList.value = []; ticket.value = null; selectedId.value = null
  messages.value = []; events.value = []; boundOrder.value = null
  orders.value = []; policies.value = []; runs.value = []; selectedRunId.value = null
  capabilities.value = { modelConfigured: false }; assistantMode.value = 'demo'
  reply.value = ''; question.value = '这张工单涉及哪些售后流程？'
  createOpen.value = false; policyEditor.value = null; publishConfirm.value = null
  createBusy.value = false; policyBusy.value = false
  ticketDraft.orderId = ''; ticketDraft.subject = ''; ticketDraft.body = ''
  conflict.value = false; busyTicket.value = ''; assistantBusy.value = false
  detailLoading.value = false; inboxLoading.value = false; ordersLoading.value = false; policiesLoading.value = false
  notice.value = ''; assistantError.value = ''; policyError.value = ''; createError.value = ''
}
function captureContext() { return { identity, userId: actor.value?.userId, ticketId: selectedId.value, selection } }
function currentContext(context, withTicket = true) {
  return context.identity === identity && context.userId === actor.value?.userId && (!withTicket || (context.ticketId === selectedId.value && scope.accepts(context.selection)))
}
function report(failure, context, target = error) {
  if (failure instanceof StaleResponseError || (context && !currentContext(context, false))) return
  target.value = failure instanceof ApiError ? failure.message : '操作未完成，请稍后重试。'
  if (failure.status === 409 && (target === error || target === assistantError) && context?.ticketId === selectedId.value) { conflict.value = true; publishConfirm.value = null }
}
async function startWorkspace(principal) {
  clearWorkspace()
  actor.value = principal
  nav.value = 'tickets'; filters.q = ''; filters.status = ''; ticketMeta.page = 0
  if (!principal) return
  const context = captureContext()
  const jobs = [loadTickets(), loadSummary(), loadPolicies()]
  if (principal.role === 'agent') jobs.push(api.request('/assistant/capabilities').then(result => { if (currentContext(context, false)) capabilities.value = result.data }).catch(failure => report(failure, context)))
  await Promise.all(jobs)
}
async function login() {
  if (loginBusy.value) return
  loginBusy.value = true; error.value = ''
  const password = loginForm.password
  loginForm.password = ''
  try { await startWorkspace(await api.login(loginForm.username, password)) }
  catch (failure) { report(failure) }
  finally { loginBusy.value = false }
}
async function logout() {
  if (loginBusy.value) return
  loginBusy.value = true; error.value = ''
  try { await api.logout(); clearWorkspace() }
  catch (failure) { report(failure) }
  finally { loginBusy.value = false }
}
async function loadSummary() {
  const token = scope.begin('summary')
  const context = captureContext()
  try { const result = await api.request('/summary'); if (scope.accepts(token)) summary.value = result.data }
  catch (failure) { if (scope.accepts(token)) report(failure, context) }
}
async function loadTickets(resetPage = false) {
  if (!actor.value) return
  if (resetPage) ticketMeta.page = 0
  const token = scope.begin('inbox')
  const context = captureContext()
  inboxLoading.value = true
  const query = new URLSearchParams({ page: ticketMeta.page, size: 20, q: filters.q })
  if (filters.status) query.set('status', filters.status)
  try {
    const result = await api.request(`/tickets?${query}`)
    if (!scope.accepts(token)) return
    ticketList.value = result.data; Object.assign(ticketMeta, result.meta)
    if (nav.value === 'tickets' && !selectedId.value && result.data.length) await openTicket(result.data[0].id)
  } catch (failure) { if (scope.accepts(token)) report(failure, context) }
  finally { if (scope.accepts(token)) inboxLoading.value = false }
}
async function openTicket(id, keepDraft = false) {
  const retainedReply = keepDraft ? reply.value : ''
  selection = scope.begin('selection', id)
  const token = scope.begin('detail', id)
  selectedId.value = id; ticket.value = null; messages.value = []; events.value = []; boundOrder.value = null
  reply.value = retainedReply; runs.value = []; selectedRunId.value = null
  busyTicket.value = ''; assistantBusy.value = false; assistantError.value = ''; publishConfirm.value = null; conflict.value = false
  detailLoading.value = true
  const context = captureContext()
  try {
    const result = await Promise.all([api.request(`/tickets/${id}`), api.request(`/tickets/${id}/messages`), api.request(`/tickets/${id}/events`), ...(isStaff.value ? [api.request(`/tickets/${id}/assistant/runs`)] : [])])
    if (!scope.accepts(token)) return
    ticket.value = result[0].data; messages.value = result[1].data; events.value = result[2].data
    runs.value = result[3]?.data || []; selectedRunId.value = runs.value[0]?.id || null
    const order = await api.request(`/orders/${ticket.value.orderId}`)
    if (scope.accepts(token)) boundOrder.value = order.data
  } catch (failure) { if (scope.accepts(token)) report(failure, context) }
  finally { if (scope.accepts(token)) detailLoading.value = false }
}
async function reloadTicket() {
  error.value = ''; assistantError.value = ''
  if (selectedId.value) await openTicket(selectedId.value, true)
  await Promise.all([loadTickets(), loadSummary()])
}
async function mutateTicket(path, method, body, success) {
  if (busyTicket.value || !ticket.value || conflict.value) return
  const context = captureContext()
  busyTicket.value = path; error.value = ''; notice.value = ''
  try {
    await api.request(`/tickets/${context.ticketId}${path}`, { method, body })
    if (!currentContext(context)) return
    success?.(); notice.value = path === '/messages' ? '回复已发送。' : '工单状态已更新。'
    await openTicket(context.ticketId, true)
    await Promise.all([loadTickets(), loadSummary()])
  } catch (failure) { if (currentContext(context)) report(failure, context) }
  finally { if (currentContext(context)) busyTicket.value = '' }
}
async function sendReply() {
  if (!reply.value.trim() || reply.value.length > 1500 || ticket.value?.status === 'resolved') return
  await mutateTicket('/messages', 'POST', { body: reply.value, version: ticket.value.version }, () => { reply.value = '' })
}
async function changeStatus(status) {
  if (!availableActions.value.includes(status)) return
  await mutateTicket('/status', 'PATCH', { status, version: ticket.value.version })
}
async function navigate(view) {
  nav.value = view; error.value = ''; notice.value = ''
  if (view === 'orders') { ordersMeta.page = 0; await loadOrders() }
  else if (view === 'policies') await loadPolicies()
  else await loadTickets()
}
async function loadOrders() {
  const token = scope.begin('orders'); const context = captureContext()
  ordersLoading.value = true
  try {
    const query = new URLSearchParams({ page: ordersMeta.page, size: 20, q: orderQuery.value })
    const result = await api.request(`/orders?${query}`)
    if (scope.accepts(token)) { orders.value = result.data; Object.assign(ordersMeta, result.meta) }
  } catch (failure) { if (scope.accepts(token)) report(failure, context) }
  finally { if (scope.accepts(token)) ordersLoading.value = false }
}
async function showCreate(order = null) {
  const context = captureContext()
  createError.value = ''; ticketDraft.orderId = order?.id || ''; ticketDraft.subject = ''; ticketDraft.body = ''
  if (!orders.value.length) await loadOrders()
  if (!currentContext(context, false)) return
  createOpen.value = true
  await nextTick(); document.querySelector('#ticket-subject')?.focus()
}
async function createTicket() {
  createError.value = validateTicket(ticketDraft, orders.value)
  if (createError.value || createBusy.value) return
  const context = captureContext(); createBusy.value = true
  try {
    const result = await api.request('/tickets', { method: 'POST', body: { orderId: Number(ticketDraft.orderId), subject: ticketDraft.subject, body: ticketDraft.body } })
    if (!currentContext(context, false)) return
    createOpen.value = false; nav.value = 'tickets'; notice.value = '工单已提交，客服会在这里回复。'
    await openTicket(result.data.id)
    await Promise.all([loadTickets(true), loadSummary()])
  } catch (failure) { report(failure, context, createError) }
  finally { if (currentContext(context, false)) createBusy.value = false }
}
async function loadPolicies() {
  const token = scope.begin('policies'); const context = captureContext()
  policiesLoading.value = true
  try { const result = await api.request('/policies'); if (scope.accepts(token)) policies.value = result.data }
  catch (failure) { if (scope.accepts(token)) report(failure, context) }
  finally { if (scope.accepts(token)) policiesLoading.value = false }
}
async function editPolicy(policy = null) {
  policyError.value = ''
  policyEditor.value = policy ? { ...policy } : { title: '', filename: '', content: '' }
  await nextTick(); document.querySelector('#policy-title')?.focus()
}
async function savePolicy() {
  if (policyBusy.value || !policyEditor.value) return
  const policy = { ...policyEditor.value }; const context = captureContext()
  policyError.value = ''; policyBusy.value = true
  try {
    const body = { title: policy.title, filename: policy.filename, content: policy.content, ...(policy.id ? { version: policy.version } : {}) }
    await api.request(policy.id ? `/policies/${policy.id}` : '/policies', { method: policy.id ? 'PUT' : 'POST', body })
    if (!currentContext(context, false)) return
    policyEditor.value = null; notice.value = '售后资料已保存。'; await loadPolicies()
  } catch (failure) { report(failure, context, policyError) }
  finally { if (currentContext(context, false)) policyBusy.value = false }
}
async function runAssistant() {
  if (!ticket.value || assistantBusy.value || !question.value.trim() || question.value.length > 1500) return
  const context = captureContext(); assistantBusy.value = true; assistantError.value = ''; publishConfirm.value = null
  try {
    const result = await api.request(`/tickets/${context.ticketId}/assistant/runs`, { method: 'POST', body: { question: question.value, mode: assistantMode.value } })
    if (!currentContext(context)) return
    runs.value = [result.data, ...runs.value.filter(run => run.id !== result.data.id)].slice(0, 20)
    selectedRunId.value = result.data.id
  } catch (failure) { if (currentContext(context)) report(failure, context, assistantError) }
  finally { if (currentContext(context)) assistantBusy.value = false }
}
async function discardDraft() {
  const run = selectedRun.value
  if (!run || run.state !== 'pending' || busyTicket.value) return
  const context = captureContext(); busyTicket.value = 'discard'; assistantError.value = ''; publishConfirm.value = null
  try {
    const result = await api.request(`/tickets/${context.ticketId}/assistant/runs/${run.id}/discard`, { method: 'POST' })
    if (currentContext(context)) runs.value = runs.value.map(item => item.id === run.id ? result.data : item)
  } catch (failure) { if (currentContext(context)) report(failure, context, assistantError) }
  finally { if (currentContext(context)) busyTicket.value = '' }
}
async function publishDraft() {
  const run = selectedRun.value
  if (!publishConfirm.value || publishConfirm.value !== run?.id || draftIssue.value) return
  const context = captureContext(); busyTicket.value = 'publish'; assistantError.value = ''
  try {
    await api.request(`/tickets/${context.ticketId}/assistant/runs/${run.id}/publish`, { method: 'POST', body: { version: ticket.value.version } })
    if (!currentContext(context)) return
    publishConfirm.value = null; notice.value = '确认后的回复已发送给客户。'
    await openTicket(context.ticketId, true); await Promise.all([loadTickets(), loadSummary()])
  } catch (failure) { if (currentContext(context)) report(failure, context, assistantError) }
  finally { if (currentContext(context)) busyTicket.value = '' }
}
function closeDialogs() {
  if (!createBusy.value) createOpen.value = false
  if (!policyBusy.value) policyEditor.value = null
  if (!busyTicket.value) publishConfirm.value = null
}
function eventAction(action) { return ({ created: '提交工单', replied: '发送回复', status_changed: '更新状态', message: '发送回复', status: '更新状态' })[action] || '工单更新' }
function runState(state) { return ({ none: '检索记录', pending: '待确认回复', published: '已发送', discarded: '已放弃' })[state] || '处理记录' }

onMounted(async () => {
  document.addEventListener('keydown', trapDialogFocus)
  try { await startWorkspace(await api.refreshSession()) }
  catch (failure) { report(failure) }
  finally { authLoading.value = false }
})
onUnmounted(() => document.removeEventListener('keydown', trapDialogFocus))
</script>

<template>
  <a class="skip-link" href="#main-content">跳到主要内容</a>
  <div v-if="authLoading" class="boot-screen" role="status">正在连接售后工作台…</div>
  <main v-else-if="!actor" id="main-content" class="login-page">
    <section class="login-story" aria-labelledby="login-title">
      <div class="brand"><span class="brand-mark" aria-hidden="true">S</span><span>ServiceDesk</span></div>
      <div class="story-copy">
        <p class="project-label">原创全栈练习项目</p>
        <h1 id="login-title">每个问题，<br>都有处理记录。</h1>
        <p>从订单发起售后，围绕工单沟通。客服可查阅售后资料、核对订单，再确认助手给出的回复。</p>
        <ol class="workflow-preview"><li><span>客户</span>关联订单，提交问题</li><li><span>客服</span>回复并推进处理状态</li><li><span>资料助手</span>提供来源，回复由人确认</li></ol>
      </div>
      <p class="fiction-note">企业、账号、订单与流程均为虚拟演示内容。</p>
    </section>
    <section class="login-form-pane" aria-labelledby="access-title">
      <form class="login-form" @submit.prevent="login">
        <p class="project-label">进入工作台</p><h2 id="access-title">选择一个演示身份</h2>
        <p class="muted">{{ accountChoice?.workspace }} · {{ accountChoice?.label }}</p>
        <label for="login-account">演示账号</label>
        <select id="login-account" v-model="loginForm.username" autocomplete="username" :disabled="loginBusy"><option v-for="account in accounts" :key="account.username" :value="account.username">{{ account.workspace }} · {{ account.label }}（{{ account.username }}）</option></select>
        <label for="login-password">演示密码</label>
        <input id="login-password" v-model="loginForm.password" type="password" required autocomplete="current-password" :disabled="loginBusy" placeholder="输入本地启动时设置的演示密码" />
        <p class="field-help">密码由本地启动者设置，页面不会保存密码。</p>
        <p v-if="error" class="alert error" role="alert">{{ error }}</p>
        <button class="primary login-submit" :disabled="loginBusy || !loginForm.password">{{ loginBusy ? '正在登录…' : '进入售后工作台' }}</button>
        <div class="login-footnote"><strong>可直接体验资料检索</strong><p>默认资料检索演示不调用模型。配置本地模型后，客服可选择 AI 助手。</p></div>
      </form>
    </section>
  </main>

  <div v-else class="app-shell" @keydown.esc="closeDialogs">
    <aside class="sidebar" aria-label="主导航" :inert="dialogVisible">
      <div class="brand"><span class="brand-mark" aria-hidden="true">S</span><span>ServiceDesk</span></div>
      <div class="workspace-name"><span class="workspace-dot" aria-hidden="true"></span>{{ actor.workspaceName }}</div>
      <nav>
        <button :class="{ active: nav === 'tickets' }" :aria-current="nav === 'tickets' ? 'page' : undefined" @click="navigate('tickets')"><span aria-hidden="true">▤</span>{{ isStaff ? '工单收件箱' : '我的工单' }}</button>
        <button v-if="!isStaff" :class="{ active: nav === 'orders' }" :aria-current="nav === 'orders' ? 'page' : undefined" @click="navigate('orders')"><span aria-hidden="true">▣</span>我的订单</button>
        <button :class="{ active: nav === 'policies' }" :aria-current="nav === 'policies' ? 'page' : undefined" @click="navigate('policies')"><span aria-hidden="true">▧</span>售后资料</button>
      </nav>
      <div class="sidebar-note"><span class="small-indicator">练习环境</span><p>虚拟企业与订单<br>所有回复仅在本项目内展示</p></div>
      <div class="profile"><span class="avatar">{{ actor.displayName.slice(0, 1) }}</span><div><strong>{{ actor.displayName }}</strong><small>{{ isStaff ? '客服工作台' : '客户中心' }}</small></div><button class="logout" :disabled="loginBusy" @click="logout" aria-label="退出并切换演示身份" title="退出并切换身份">退出</button></div>
    </aside>

    <div class="main-shell" :inert="dialogVisible">
      <header class="topbar"><div><p class="breadcrumb">售后服务 / {{ isStaff ? '客服工作台' : '客户中心' }}</p><h1>{{ heading }}</h1></div><button v-if="!isStaff && nav !== 'policies'" class="primary" @click="showCreate()">＋ 提交售后工单</button><button v-else-if="isStaff && nav === 'policies'" class="primary" :disabled="policies.length >= 20" @click="editPolicy()">＋ 新增资料</button><span v-else class="environment-tag">虚拟演示企业</span></header>
      <div class="messages-strip" aria-live="polite"><p v-if="notice" class="alert success">{{ notice }}<button @click="notice = ''" aria-label="关闭提示">×</button></p><p v-if="error" class="alert error" role="alert">{{ error }}<button @click="error = ''" aria-label="关闭错误提示">×</button></p></div>

      <main id="main-content" class="workspace-content">
        <section v-if="nav === 'tickets'" class="ticket-workspace" :class="{ customer: !isStaff }" aria-label="工单处理工作区">
          <aside class="inbox-pane" aria-label="工单列表">
            <div class="inbox-heading"><h2>{{ isStaff ? '全部工单' : '我的工单' }} <span>{{ summary.total }}</span></h2><button class="icon-button" @click="loadTickets(); loadSummary()" :disabled="inboxLoading" aria-label="刷新工单列表">↻</button></div>
            <form class="search-box" @submit.prevent="loadTickets(true)"><label class="sr-only" for="ticket-search">搜索工单主题或编号</label><input id="ticket-search" v-model="filters.q" maxlength="100" placeholder="搜索主题或工单编号" /><button aria-label="搜索工单">⌕</button></form>
            <label class="filter-label" for="ticket-status-filter">处理状态</label><select id="ticket-status-filter" v-model="filters.status" @change="loadTickets(true)"><option value="">全部状态</option><option value="open">待处理（{{ summary.open }}）</option><option value="processing">处理中（{{ summary.processing }}）</option><option value="resolved">已解决（{{ summary.resolved }}）</option></select>
            <p class="list-caption">{{ inboxLoading ? '正在加载…' : `${ticketMeta.total} 张符合条件的工单` }}</p>
            <div class="ticket-list" :aria-busy="inboxLoading">
              <button v-for="item in ticketList" :key="item.id" class="ticket-row" :class="{ selected: selectedId === item.id }" :aria-pressed="selectedId === item.id" @click="openTicket(item.id)"><div class="ticket-row-meta"><span>{{ item.number }}</span><span class="status-dot" :class="item.status">{{ statusLabels[item.status] }}</span></div><strong>{{ item.subject }}</strong><p>{{ item.body }}</p><div class="ticket-row-foot"><span>{{ item.customerName }}</span><time>{{ dateLabel(item.updatedAt) }}</time></div></button>
              <div v-if="!inboxLoading && !ticketList.length" class="empty small"><strong>暂无工单</strong><p>{{ filters.q || filters.status ? '换一个关键词或状态再试试。' : isStaff ? '客户提交问题后会出现在这里。' : '从自己的订单提交售后问题。' }}</p></div>
            </div>
            <div v-if="ticketMeta.totalPages > 1" class="pagination"><button :disabled="inboxLoading || ticketMeta.page === 0" @click="ticketMeta.page--; loadTickets()">上一页</button><span>{{ ticketMeta.page + 1 }} / {{ ticketMeta.totalPages }}</span><button :disabled="inboxLoading || ticketMeta.page + 1 >= ticketMeta.totalPages" @click="ticketMeta.page++; loadTickets()">下一页</button></div>
          </aside>

          <section class="conversation-pane" aria-label="工单详情" :aria-busy="detailLoading">
            <div v-if="detailLoading && !ticket" class="empty" role="status"><span class="loading-ring" aria-hidden="true"></span><p>正在读取工单…</p></div>
            <div v-else-if="!ticket" class="empty"><div class="empty-desk" aria-hidden="true">▤</div><h2>选择一张工单</h2><p>在这里查看订单、交流记录和处理进展。</p></div>
            <template v-else>
              <header class="detail-header"><div class="detail-number"><span>{{ ticket.number }}</span><span class="status-tag" :class="ticket.status">{{ statusLabels[ticket.status] }}</span></div><h2>{{ ticket.subject }}</h2><p>{{ ticket.customerName }} 提交于 {{ dateLabel(ticket.createdAt) }}</p><div class="detail-actions"><button v-for="action in availableActions" :key="action" class="secondary" :disabled="!!busyTicket || conflict" @click="changeStatus(action)">{{ actionLabels[action] }}</button><button class="text-button" :disabled="detailLoading || !!busyTicket" @click="reloadTicket">重新加载</button></div></header>
              <div v-if="conflict" class="conflict-banner" role="alert"><strong>工单已有新的处理记录</strong><p>先重新加载最新内容，再继续回复或确认草稿。</p><button class="secondary" @click="reloadTicket">重新加载工单</button></div>
              <details class="order-details" open><summary><span>关联订单</span><span>{{ ticket.orderNumber }}</span></summary><div v-if="boundOrder" class="order-inline"><strong>{{ boundOrder.itemName }}</strong><span>{{ boundOrder.quantity }} 件 · {{ moneyLabel(boundOrder.totalCents) }}</span><span class="order-status">{{ orderLabels[boundOrder.status] }}</span></div><p v-else class="muted">正在读取关联订单…</p></details>
              <div class="thread">
                <article class="thread-entry"><div class="thread-avatar customer-avatar" aria-hidden="true">客</div><div class="thread-body"><div class="thread-meta"><strong>{{ ticket.customerName }}</strong><span>问题描述</span><time>{{ dateLabel(ticket.createdAt) }}</time></div><p class="plain-text">{{ ticket.body }}</p></div></article>
                <article v-for="message in messages" :key="message.id" class="thread-entry" :class="{ staff: message.role === 'agent' }"><div class="thread-avatar" :class="message.role === 'agent' ? 'agent-avatar' : 'customer-avatar'" aria-hidden="true">{{ message.role === 'agent' ? '服' : '客' }}</div><div class="thread-body"><div class="thread-meta"><strong>{{ message.author }}</strong><span>{{ message.role === 'agent' ? '客服回复' : '客户补充' }}</span><time>{{ dateLabel(message.createdAt) }}</time></div><p class="plain-text">{{ message.body }}</p></div></article>
              </div>
              <form class="reply-composer" @submit.prevent="sendReply"><label for="reply-body">{{ isStaff ? '回复客户' : '补充问题' }}</label><textarea id="reply-body" v-model="reply" maxlength="1500" rows="4" :disabled="ticket.status === 'resolved' || !!busyTicket || conflict" :placeholder="ticket.status === 'resolved' ? '工单已解决，客户可重新打开后继续沟通。' : isStaff ? '填写处理建议，回复会显示在客户工单中。' : '补充问题细节，帮助客服继续处理。'"></textarea><div class="composer-footer"><span>{{ reply.length }} / 1500</span><button class="primary" :disabled="!reply.trim() || !!busyTicket || conflict || ticket.status === 'resolved'">{{ busyTicket === '/messages' ? '正在发送…' : '发送回复' }}</button></div></form>
              <details class="event-history"><summary>处理记录 <span>{{ events.length }}</span></summary><ol><li v-for="event in events" :key="event.id"><span>{{ eventAction(event.action) }}</span><p>{{ event.actor }} · {{ event.detail }}</p><time>{{ dateLabel(event.createdAt) }}</time></li></ol></details>
            </template>
          </section>

          <aside v-if="isStaff" class="assistant-pane" aria-labelledby="assistant-title">
            <header class="assistant-heading"><span class="assistant-symbol" aria-hidden="true">✧</span><div><h2 id="assistant-title">资料与回复助手</h2><p>先核对资料，再确认回复</p></div></header>
            <div v-if="!ticket" class="empty small"><p>选择工单后，助手会围绕该工单和关联订单查阅资料。</p></div>
            <template v-else>
              <form class="assistant-form" @submit.prevent="runAssistant"><label for="assistant-mode">运行方式</label><select id="assistant-mode" v-model="assistantMode" :disabled="assistantBusy"><option value="demo">资料检索演示（未调用模型）</option><option value="ollama" :disabled="!capabilities.modelConfigured">{{ capabilities.modelConfigured ? '本地 AI 助手（调用已配置模型）' : '本地 AI 助手（尚未配置模型）' }}</option></select><p class="mode-note">{{ assistantMode === 'demo' ? '仅检索资料并展示原文，不生成回复草稿。' : '助手可检索资料、读取关联订单并提出回复草稿。发送前需要人工确认。' }}</p><label for="assistant-question">要核对的问题</label><textarea id="assistant-question" v-model="question" rows="3" maxlength="1500" :disabled="assistantBusy" placeholder="例如：这张工单涉及哪些换货流程？"></textarea><button class="primary assistant-submit" :disabled="assistantBusy || !question.trim() || !!busyTicket || conflict">{{ assistantBusy ? '正在核对资料…' : assistantMode === 'demo' ? '检索售后资料' : '请助手核对并建议' }}</button><p v-if="assistantBusy" class="field-help" role="status">模型处理可能需要一点时间，可继续查看工单。</p></form>
              <p v-if="assistantError" class="alert error" role="alert">{{ assistantError }}</p>
              <div v-if="runs.length" class="run-select"><label for="run-history">本账号的处理记录</label><select id="run-history" v-model="selectedRunId" :disabled="!!busyTicket" @change="publishConfirm = null"><option v-for="run in runs" :key="run.id" :value="run.id">{{ dateLabel(run.createdAt) }} · {{ runState(run.state) }} · {{ run.mode === 'demo' ? '检索演示' : '本地模型' }}</option></select></div>
              <section v-if="selectedRun" class="assistant-result" aria-label="助手结果"><span class="result-mode">{{ selectedRun.mode === 'demo' ? '资料检索演示，未调用模型' : '本地 AI 助手结果' }}</span><p class="run-question">{{ selectedRun.question }}</p><p class="plain-text answer">{{ selectedRun.answer }}</p>
                <div v-if="selectedRun.citations?.length" class="sources"><h3>参考资料 <span>{{ selectedRun.citations.length }}</span></h3><details v-for="(citation, index) in selectedRun.citations" :key="citation.chunkId" class="source" open><summary><span class="source-number">{{ index + 1 }}</span><span>{{ citation.filename }}<small>第 {{ citation.lineStart }}–{{ citation.lineEnd }} 行</small></span></summary><p class="plain-text">{{ citation.text }}</p></details></div>
                <div v-else class="no-sources">本次没有找到可引用的售后资料。</div>
                <details v-if="selectedRun.steps?.length" class="assistant-steps"><summary>查看处理步骤</summary><ol><li v-for="(step, index) in selectedRun.steps" :key="index"><span class="step-state">{{ step.status === 'ok' || step.status === 'success' ? '完成' : step.status === 'error' ? '未完成' : '记录' }}</span><p>{{ step.summary }}</p></li></ol></details>
                <section v-if="selectedRun.reply" class="draft-box"><div class="draft-title"><h3>建议回复</h3><span>{{ runState(selectedRun.state) }}</span></div><p class="plain-text">{{ selectedRun.reply }}</p><template v-if="selectedRun.state === 'pending'"><p class="field-help">有效至 {{ dateLabel(selectedRun.expiresAt) }} · 发送前请核对上述资料与订单。</p><p v-if="draftIssue" class="draft-warning" role="status">{{ draftIssue }}</p><div class="draft-actions"><button class="secondary" :disabled="!!busyTicket" @click="discardDraft">放弃草稿</button><button class="primary" :disabled="!!draftIssue" @click="publishConfirm = selectedRun.id">核对并发送</button></div></template></section>
              </section>
              <div v-else-if="!assistantBusy" class="assistant-empty"><div aria-hidden="true">▧</div><h3>答案要有出处</h3><p>检索结果会展示文件和原文位置，便于逐条核对。</p></div>
            </template>
          </aside>
        </section>

        <section v-else-if="nav === 'orders'" class="orders-page" aria-labelledby="orders-heading"><div class="page-intro"><div><h2 id="orders-heading">从订单开始售后</h2><p>只展示当前账号的虚拟订单，提交工单后可在工作台跟进。</p></div><form class="search-box order-search" @submit.prevent="ordersMeta.page = 0; loadOrders()"><label class="sr-only" for="order-search">搜索订单</label><input id="order-search" v-model="orderQuery" maxlength="100" placeholder="搜索订单或商品" /><button aria-label="搜索订单">⌕</button></form></div><div class="order-table-wrap"><table><thead><tr><th scope="col">订单 / 商品</th><th scope="col">数量</th><th scope="col">金额</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead><tbody><tr v-for="order in orders" :key="order.id"><td><span class="order-number">{{ order.number }}</span><strong>{{ order.itemName }}</strong><small>更新于 {{ dateLabel(order.updatedAt) }}</small></td><td>{{ order.quantity }} 件</td><td>{{ moneyLabel(order.totalCents) }}</td><td><span class="order-status">{{ orderLabels[order.status] }}</span></td><td><button class="secondary" :disabled="order.status === 'cancelled'" @click="showCreate(order)">提交售后</button></td></tr></tbody></table><div v-if="ordersLoading" class="empty small" role="status">正在读取订单…</div><div v-else-if="!orders.length" class="empty"><h3>没有符合条件的订单</h3><p>请检查搜索关键词。</p></div></div><div v-if="ordersMeta.totalPages > 1" class="pagination"><button :disabled="ordersMeta.page === 0 || ordersLoading" @click="ordersMeta.page--; loadOrders()">上一页</button><span>{{ ordersMeta.page + 1 }} / {{ ordersMeta.totalPages }}</span><button :disabled="ordersMeta.page + 1 >= ordersMeta.totalPages || ordersLoading" @click="ordersMeta.page++; loadOrders()">下一页</button></div></section>

        <section v-else class="policies-page" aria-labelledby="policies-heading"><div class="page-intro"><div><h2 id="policies-heading">同一份资料，同一套处理依据</h2><p>当前企业的共享售后说明。内容为项目虚拟流程，{{ isStaff ? '可由客服维护，助手检索时会引用原文。' : '可在提交问题前查阅。' }}</p></div><span class="document-count">{{ policies.length }} / 20 份资料</span></div><div v-if="policiesLoading" class="empty" role="status">正在读取售后资料…</div><div v-else-if="!policies.length" class="empty"><h3>还没有售后资料</h3><p>{{ isStaff ? '添加流程说明，让团队和助手有据可查。' : '客服添加的售后说明会显示在这里。' }}</p></div><article v-for="policy in policies" v-else :key="policy.id" class="policy-document"><header><span class="document-icon" aria-hidden="true">▧</span><div><h3>{{ policy.title }}</h3><p>{{ policy.filename }} · 更新于 {{ dateLabel(policy.updatedAt) }}</p></div><button v-if="isStaff" class="secondary" @click="editPolicy(policy)">编辑资料</button></header><p class="plain-text">{{ policy.content }}</p></article></section>
      </main>
    </div>

    <div v-if="createOpen" class="modal-backdrop" @click.self="!createBusy && (createOpen = false)"><section class="dialog" role="dialog" aria-modal="true" aria-labelledby="create-title"><header><div><p class="project-label">关联订单</p><h2 id="create-title">提交售后工单</h2></div><button class="icon-button" :disabled="createBusy" @click="createOpen = false" aria-label="关闭工单表单">×</button></header><form @submit.prevent="createTicket"><label for="ticket-order">选择订单</label><select id="ticket-order" v-model="ticketDraft.orderId" required :disabled="createBusy"><option value="" disabled>请选择自己的订单</option><option v-for="order in orders" :key="order.id" :value="order.id" :disabled="order.status === 'cancelled'">{{ order.number }} · {{ order.itemName }}（{{ orderLabels[order.status] }}）</option></select><label for="ticket-subject">工单主题</label><input id="ticket-subject" v-model="ticketDraft.subject" required maxlength="100" :disabled="createBusy" placeholder="用一句话描述需要帮助的问题" /><label for="ticket-description">问题描述</label><textarea id="ticket-description" v-model="ticketDraft.body" required maxlength="2000" rows="6" :disabled="createBusy" placeholder="描述出现的问题和希望了解的处理方式。演示环境中请勿填写真实联系方式。"></textarea><p class="field-help">{{ ticketDraft.body.length }} / 2000 字 · 本项目不执行付款或退款。</p><p v-if="createError" class="alert error" role="alert">{{ createError }}</p><footer><button type="button" class="secondary" :disabled="createBusy" @click="createOpen = false">取消</button><button class="primary" :disabled="createBusy">{{ createBusy ? '正在提交…' : '提交工单' }}</button></footer></form></section></div>

    <div v-if="policyEditor" class="modal-backdrop" @click.self="!policyBusy && (policyEditor = null)"><section class="dialog wide-dialog" role="dialog" aria-modal="true" aria-labelledby="policy-editor-title"><header><div><p class="project-label">企业共享资料</p><h2 id="policy-editor-title">{{ policyEditor.id ? '编辑售后资料' : '新增售后资料' }}</h2></div><button class="icon-button" :disabled="policyBusy" @click="policyEditor = null" aria-label="关闭资料表单">×</button></header><form @submit.prevent="savePolicy"><div class="form-row"><div><label for="policy-title">资料标题</label><input id="policy-title" v-model="policyEditor.title" required maxlength="100" :disabled="policyBusy" /></div><div><label for="policy-filename">文件名</label><input id="policy-filename" v-model="policyEditor.filename" required maxlength="80" :disabled="policyBusy" placeholder="例如：换货说明.md" /></div></div><p class="field-help">文件名使用 .md 或 .txt，不包含目录。正文会按原文保存。</p><label for="policy-content">流程正文</label><textarea id="policy-content" v-model="policyEditor.content" required maxlength="4000" rows="12" :disabled="policyBusy"></textarea><p class="field-help">{{ policyEditor.content.length }} / 4000 字</p><p v-if="policyError" class="alert error" role="alert">{{ policyError }}<button v-if="policyError.includes('更新')" type="button" @click="policyEditor = null; loadPolicies()">关闭并重新加载资料</button></p><footer><button type="button" class="secondary" :disabled="policyBusy" @click="policyEditor = null">取消</button><button class="primary" :disabled="policyBusy">{{ policyBusy ? '正在保存…' : '保存资料' }}</button></footer></form></section></div>

    <div v-if="publishConfirm && selectedRun" class="modal-backdrop" @click.self="!busyTicket && (publishConfirm = null)"><section class="dialog confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="publish-title"><header><div><p class="project-label">需要你的确认</p><h2 id="publish-title">将这段回复发送给客户？</h2></div><button class="icon-button" :disabled="!!busyTicket" @click="publishConfirm = null" aria-label="取消发送确认">×</button></header><p class="confirm-caption">回复将显示在 {{ ticket?.number }} 的沟通记录中。请核对事实、资料来源和措辞。</p><div class="confirm-body plain-text">{{ selectedRun.reply }}</div><p v-if="draftIssue" class="alert error" role="alert">{{ draftIssue }}</p><footer><button class="secondary" :disabled="!!busyTicket" @click="publishConfirm = null">继续核对</button><button class="primary" :disabled="!!draftIssue" @click="publishDraft">{{ busyTicket === 'publish' ? '正在发送…' : '确认发送给客户' }}</button></footer></section></div>
  </div>
</template>
