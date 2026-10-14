import test from 'node:test'
import assert from 'node:assert/strict'
import { statusActions, validateTicket, approvalIssue, createResponseScope } from '../src/domain.js'

const ticket = { id: 7, status: 'processing', version: 3 }
const pending = { id: 9, reply: '请提供包装状态。', state: 'pending', ticketVersion: 3, expiresAt: '2026-10-13T12:10:00Z' }
const now = Date.parse('2026-10-13T12:00:00Z')

test('staff can progress an open ticket only to processing', () => {
  assert.deepEqual(statusActions({ role: 'agent' }, { status: 'open' }), ['processing'])
})
test('customer can reopen a resolved ticket and cannot progress open tickets', () => {
  assert.deepEqual(statusActions({ role: 'customer' }, { status: 'resolved' }), ['open'])
  assert.deepEqual(statusActions({ role: 'customer' }, { status: 'open' }), [])
})
test('cancelled order cannot create an after-sales ticket', () => {
  assert.match(validateTicket({ orderId: 4, subject: '物流问题', body: '请协助' }, [{ id: 4, status: 'cancelled' }]), /取消/)
})
test('an order outside the loaded customer choices cannot be submitted', () => {
  assert.match(validateTicket({ orderId: 5, subject: '物流问题', body: '请协助' }, [{ id: 4, status: 'paid' }]), /订单/)
})
test('new ticket rejects blank and overlong text but accepts original line breaks', () => {
  assert.match(validateTicket({ orderId: 4, subject: ' ', body: '请协助' }, [{ id: 4, status: 'paid' }]), /主题/)
  assert.match(validateTicket({ orderId: 4, subject: '物流问题', body: '甲'.repeat(2001) }, [{ id: 4, status: 'paid' }]), /2000/)
  assert.equal(validateTicket({ orderId: 4, subject: '物流问题', body: '第一行\n第二行' }, [{ id: 4, status: 'paid' }]), '')
})
test('draft with the current version and a future expiry is publishable', () => {
  assert.equal(approvalIssue(pending, ticket, now, false), '')
})
test('stale draft cannot publish after a customer sends another message', () => {
  assert.match(approvalIssue(pending, { ...ticket, version: 4 }, now, false), /更新/)
})
test('draft cannot publish at the exact expiry boundary', () => {
  assert.match(approvalIssue(pending, ticket, Date.parse(pending.expiresAt), false), /过期/)
})
test('missing or malformed expiry fails closed', () => {
  assert.match(approvalIssue({ ...pending, expiresAt: null }, ticket, now, false), /有效期/)
  assert.match(approvalIssue({ ...pending, expiresAt: 'invalid' }, ticket, now, false), /有效期/)
})
test('draft cannot publish into a resolved ticket', () => {
  assert.match(approvalIssue(pending, { ...ticket, status: 'resolved' }, now, false), /解决/)
})
test('publication blocks while busy and after a draft was already published', () => {
  assert.match(approvalIssue(pending, ticket, now, true), /处理中/)
  assert.match(approvalIssue({ ...pending, state: 'published' }, ticket, now, false), /待确认/)
})
test('proposal must contain nonblank text to be publishable', () => {
  assert.match(approvalIssue({ ...pending, reply: ' ' }, ticket, now, false), /草稿/)
})
test('switching tickets discards the old detail response', () => {
  const scope = createResponseScope()
  const first = scope.begin('detail', 7)
  const next = scope.begin('detail', 8)
  assert.equal(scope.accepts(first), false)
  assert.equal(scope.accepts(next), true)
})
test('logout invalidates all channels even before a new request starts', () => {
  const scope = createResponseScope()
  const first = scope.begin('detail', 7)
  const policies = scope.begin('policies')
  scope.invalidate()
  assert.equal(scope.accepts(first), false)
  assert.equal(scope.accepts(policies), false)
})
test('independent inbox and detail requests do not cancel each other', () => {
  const scope = createResponseScope()
  const inbox = scope.begin('inbox')
  const detail = scope.begin('detail', 7)
  assert.equal(scope.accepts(inbox), true)
  assert.equal(scope.accepts(detail), true)
})
test('returning to the same ticket still discards work started before a selection change', () => {
  const scope = createResponseScope()
  const first = scope.begin('selection', 7)
  scope.begin('selection', 8)
  const returned = scope.begin('selection', 7)
  assert.equal(scope.accepts(first), false)
  assert.equal(scope.accepts(returned), true)
})
